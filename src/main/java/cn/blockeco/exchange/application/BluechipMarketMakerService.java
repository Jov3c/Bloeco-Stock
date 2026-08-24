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
import java.util.Optional;
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
    private final Supplier<CompletionStage<Integer>> queuedMatcher;
    private CompletionStage<Void> lifecycle = CompletableFuture.completedFuture(null);
    private boolean closeRequested;
    private boolean operatorPaused;
    /** Guards the post-trade callback while ordinary system order placement is in progress. */
    private boolean systemQuotePassActive;
    /** Coalesces settlement callbacks that arrive while the opening/system quote batch is active. */
    private boolean deferredReplenishmentRequested;

    public BluechipMarketMakerService(BluechipRepository bluechips, SecondaryMarketService market, Supplier<MarketSession> session, AppClock clock) {
        this(bluechips, market, session, clock, market::matchQueuedOrdersSilently);
    }

    BluechipMarketMakerService(BluechipRepository bluechips, SecondaryMarketService market, Supplier<MarketSession> session, AppClock clock,
                                Supplier<CompletionStage<Integer>> queuedMatcher) {
        this.bluechips = Objects.requireNonNull(bluechips, "bluechips"); this.market = Objects.requireNonNull(market, "market");
        this.session = Objects.requireNonNull(session, "session"); this.clock = Objects.requireNonNull(clock, "clock");
        this.queuedMatcher = Objects.requireNonNull(queuedMatcher, "queuedMatcher");
    }

    public synchronized CompletionStage<QuoteRefreshResult> refreshQuotes() {
        if (operatorPaused || !session.get().acceptsMatching()) return CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0));
        // A close is a session transition, not a permanent operator pause. The first open refresh re-arms quotes.
        closeRequested = false;
        return enqueue(() -> closeRequested ? CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0)) : refreshOpenQuotes(false));
    }

    /**
     * Non-blocking post-commit hook for player/opening trades.  A system quote pass is explicitly
     * excluded, so the limit-order engine remains the sole matcher and replenishment cannot recurse.
     */
    public synchronized CompletionStage<Void> replenishAfterMatch() {
        if (systemQuotePassActive) {
            deferredReplenishmentRequested = true;
            return CompletableFuture.completedFuture(null);
        }
        if (operatorPaused || !session.get().acceptsMatching()) return CompletableFuture.completedFuture(null);
        // A real opening trade proves the session is open; re-arm the normal first-open behaviour.
        closeRequested = false;
        return enqueue(() -> closeRequested ? CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0)) : refreshOpenQuotes(true))
                .thenApply(ignored -> null);
    }

    private CompletionStage<QuoteRefreshResult> refreshOpenQuotes(boolean avoidRestingPlayerCross) {
        if (!session.get().acceptsMatching()) return CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0));
        synchronized (this) { systemQuotePassActive = true; }
        List<String> degraded = new ArrayList<>();
        CompletionStage<Integer> chain = CompletableFuture.completedFuture(0);
        for (BluechipRepository.BluechipCompany bluechip : bluechips.all()) {
            chain = chain.thenCompose(total -> refresh(bluechip, avoidRestingPlayerCross).thenApply(result -> { if (result.degraded()) degraded.add(bluechip.listing().stockCode()); return total + result.orders(); }));
        }
        return chain.thenCompose(orders -> {
            QuoteRefreshResult result = new QuoteRefreshResult(Set.copyOf(new LinkedHashSet<>(degraded)), orders);
            // The opening catch-up can run before these system orders exist. Match once only after the full quote batch.
            return session.get().acceptsMatching()
                    ? queuedMatcher.get().thenApply(ignored -> result)
                    : CompletableFuture.completedFuture(result);
        }).whenComplete((ignored, failure) -> finishSystemQuotePass());
    }

    private void finishSystemQuotePass() {
        boolean replenish;
        synchronized (this) {
            systemQuotePassActive = false;
            replenish = deferredReplenishmentRequested;
            deferredReplenishmentRequested = false;
        }
        if (replenish) {
            // enqueue() puts this behind the completed batch, so this call never waits on its own lifecycle stage.
            // Its returned stage is deliberately detached from settlement/player work.
            replenishAfterMatch();
        }
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

    private CompletionStage<CompanyQuoteResult> refresh(BluechipRepository.BluechipCompany original, boolean avoidRestingPlayerCross) {
        return market.cancelOpenOrders(original.systemAccountId(), original.listing().stockCode()).thenCompose(ignored -> {
            BluechipRepository.BluechipCompany bluechip = bluechips.findByCompanyId(original.companyId()).orElseThrow();
            QuoteBoundaries boundaries = avoidRestingPlayerCross
                    ? new QuoteBoundaries(market.bestBid(bluechip.listing().stockCode()), market.bestAsk(bluechip.listing().stockCode()))
                    : QuoteBoundaries.none();
            List<Quote> quotes = quotes(bluechip, market.availableCash(bluechip.systemAccountId()), boundaries);
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

    private List<Quote> quotes(BluechipRepository.BluechipCompany bluechip, Money availableCash, QuoteBoundaries boundaries) {
        List<Quote> quotes = new ArrayList<>(); long cash = availableCash.minorUnits(), shares = bluechip.fundShares();
        long step = Math.max(1, ceilDiv(Math.multiplyExact(bluechip.modelPrice().minorUnits(), Math.max(1, bluechip.spreadBps())), 20_000));
        for (int level = 1; level <= LEVELS; level++) {
            long bid = bluechip.modelPrice().minorUnits() - Math.multiplyExact(step, level);
            if (boundaries.lowestAsk().isPresent()) bid = Math.min(bid, Math.subtractExact(boundaries.lowestAsk().get().minorUnits(), Math.multiplyExact(step, level)));
            if (bid > bluechip.lowerPrice().minorUnits()) {
                long cost = Math.addExact(bid, FeePolicy.cumulativeFee(Money.ofMinor(bid), market.buyerFeeBps()).minorUnits());
                if (cash >= cost) { quotes.add(new Quote(Side.BUY, Money.ofMinor(bid), 1)); cash -= cost; }
            }
            long ask = bluechip.modelPrice().minorUnits() + Math.multiplyExact(step, level);
            if (boundaries.highestBid().isPresent()) ask = Math.max(ask, Math.addExact(boundaries.highestBid().get().minorUnits(), Math.multiplyExact(step, level)));
            if (ask < bluechip.upperPrice().minorUnits() && shares > 0) { quotes.add(new Quote(Side.SELL, Money.ofMinor(ask), 1)); shares--; }
        }
        return List.copyOf(quotes);
    }

    private static long ceilDiv(long value, long divisor) { return Math.addExact(value, divisor - 1) / divisor; }

    public record QuoteRefreshResult(Set<String> liquidityDegradedStockCodes, int placedOrders) { }
    private record CompanyQuoteResult(int orders, boolean degraded) { }
    private record Quote(Side side, Money price, long shares) { }
    private record QuoteBoundaries(Optional<Money> highestBid, Optional<Money> lowestAsk) { static QuoteBoundaries none() { return new QuoteBoundaries(Optional.empty(), Optional.empty()); } }
    private enum Side { BUY, SELL }
}
