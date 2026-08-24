package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.FeePolicy;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Places finite, owner-scoped bluechip liquidity through the ordinary limit-order book. */
public final class BluechipMarketMakerService {
    private static final int LEVELS = 5;
    private final BluechipRepository bluechips;
    private final SecondaryMarketService market;
    private final Supplier<MarketSession> session;
    private final AppClock clock;
    private CompletionStage<Void> lifecycle = CompletableFuture.completedFuture(null);
    private boolean closeRequested;
    private boolean operatorPaused;

    public BluechipMarketMakerService(BluechipRepository bluechips, SecondaryMarketService market, Supplier<MarketSession> session, AppClock clock) {
        this.bluechips = Objects.requireNonNull(bluechips, "bluechips"); this.market = Objects.requireNonNull(market, "market");
        this.session = Objects.requireNonNull(session, "session"); this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized CompletionStage<QuoteRefreshResult> refreshQuotes() {
        if (operatorPaused || !session.get().acceptsMatching()) return CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0));
        // A close is a session transition, not a permanent operator pause. The first open refresh re-arms quotes.
        closeRequested = false;
        return enqueue(() -> closeRequested ? CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0)) : refreshOpenQuotes());
    }

    private CompletionStage<QuoteRefreshResult> refreshOpenQuotes() {
        if (!session.get().acceptsMatching()) return CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0));
        List<String> degraded = new ArrayList<>();
        CompletionStage<Integer> chain = CompletableFuture.completedFuture(0);
        for (BluechipRepository.BluechipCompany bluechip : bluechips.all()) {
            chain = chain.thenCompose(total -> refresh(bluechip).thenApply(result -> { if (result.degraded()) degraded.add(bluechip.listing().stockCode()); return total + result.orders(); }));
        }
        return chain.thenApply(orders -> new QuoteRefreshResult(Set.copyOf(new LinkedHashSet<>(degraded)), orders));
    }

    public synchronized CompletionStage<Integer> cancelSystemQuotesAtClose() {
        closeRequested = true;
        return enqueue(this::cancelSystemQuotes);
    }
    /** Operator control affects system quotes only; it never cancels player GTC orders. */
    public synchronized CompletionStage<Integer> setQuotesPaused(boolean paused) {
        operatorPaused = paused;
        if (paused) closeRequested = true;
        return paused ? enqueue(this::cancelSystemQuotes) : CompletableFuture.completedFuture(0);
    }

    private CompletionStage<Integer> cancelSystemQuotes() {
        CompletionStage<Integer> chain = CompletableFuture.completedFuture(0);
        for (BluechipRepository.BluechipCompany bluechip : bluechips.all()) {
            chain = chain.thenCompose(total -> market.cancelOpenOrders(bluechip.systemAccountId(), bluechip.listing().stockCode()).thenApply(cancelled -> total + cancelled));
        }
        return chain;
    }

    private <T> CompletionStage<T> enqueue(java.util.function.Supplier<CompletionStage<T>> operation) {
        CompletionStage<T> scheduled = lifecycle.handle((ignored, failure) -> null).thenCompose(ignored -> operation.get());
        lifecycle = scheduled.handle((ignored, failure) -> null);
        return scheduled;
    }

    private CompletionStage<CompanyQuoteResult> refresh(BluechipRepository.BluechipCompany original) {
        return market.cancelOpenOrders(original.systemAccountId(), original.listing().stockCode()).thenCompose(ignored -> {
            BluechipRepository.BluechipCompany bluechip = bluechips.findByCompanyId(original.companyId()).orElseThrow();
            List<Quote> quotes = quotes(bluechip, market.availableCash(bluechip.systemAccountId()));
            boolean degraded = quotes.stream().filter(quote -> quote.side() == Side.BUY).count() < LEVELS
                    || quotes.stream().filter(quote -> quote.side() == Side.SELL).count() < LEVELS;
            bluechips.recordLiquidityStatus(bluechip.companyId(), degraded, clock.now());
            CompletionStage<Integer> placements = CompletableFuture.completedFuture(0);
            for (Quote quote : quotes) placements = placements.thenCompose(total -> (quote.side() == Side.BUY
                    ? market.placeBuy(bluechip.systemAccountId(), bluechip.listing().stockCode(), quote.shares(), quote.price())
                    : market.placeSell(bluechip.systemAccountId(), bluechip.listing().stockCode(), quote.shares(), quote.price()))
                    .thenApply(ignoredPlacement -> total + 1));
            return placements.thenApply(count -> new CompanyQuoteResult(count, degraded));
        });
    }

    private List<Quote> quotes(BluechipRepository.BluechipCompany bluechip, Money availableCash) {
        List<Quote> quotes = new ArrayList<>(); long cash = availableCash.minorUnits(), shares = bluechip.fundShares();
        long step = Math.max(1, ceilDiv(Math.multiplyExact(bluechip.modelPrice().minorUnits(), Math.max(1, bluechip.spreadBps())), 20_000));
        for (int level = 1; level <= LEVELS; level++) {
            long bid = bluechip.modelPrice().minorUnits() - Math.multiplyExact(step, level);
            if (bid > bluechip.lowerPrice().minorUnits()) {
                long cost = Math.addExact(bid, FeePolicy.cumulativeFee(Money.ofMinor(bid), market.buyerFeeBps()).minorUnits());
                if (cash >= cost) { quotes.add(new Quote(Side.BUY, Money.ofMinor(bid), 1)); cash -= cost; }
            }
            long ask = bluechip.modelPrice().minorUnits() + Math.multiplyExact(step, level);
            if (ask < bluechip.upperPrice().minorUnits() && shares > 0) { quotes.add(new Quote(Side.SELL, Money.ofMinor(ask), 1)); shares--; }
        }
        return List.copyOf(quotes);
    }

    private static long ceilDiv(long value, long divisor) { return Math.addExact(value, divisor - 1) / divisor; }

    public record QuoteRefreshResult(Set<String> liquidityDegradedStockCodes, int placedOrders) { }
    private record CompanyQuoteResult(int orders, boolean degraded) { }
    private record Quote(Side side, Money price, long shares) { }
    private enum Side { BUY, SELL }
}
