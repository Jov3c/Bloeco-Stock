package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.FeePolicy;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Places finite, owner-scoped bluechip liquidity through the ordinary limit-order book. */
public final class BluechipMarketMakerService {
    private static final int LEVELS = 5;
    /** Fixed, finite depth at each displayed price level. */
    private static final long SHARES_PER_LEVEL = 10;
    /** Full-book re-quoting is deliberately much slower than the one-second market display refresh. */
    private static final Duration POST_TRADE_REPLENISH_INTERVAL = Duration.ofSeconds(15);
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
    private final Map<String, Money> quotedModelPrices = new HashMap<>();
    private Instant lastPostTradeReplenishmentAt;

    public BluechipMarketMakerService(BluechipRepository bluechips, SecondaryMarketService market, Supplier<MarketSession> session, AppClock clock) {
        // The opening queued sweep settles real pre-open player orders.  It must therefore emit the
        // ordinary post-commit notification so this pass can schedule one bounded quote refill.
        // systemQuotePassActive coalesces that callback and prevents the refill from recursing.
        this(bluechips, market, session, clock, market::matchQueuedOrdersAfterNotificationDispatch);
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
        return enqueue(() -> closeRequested ? CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0)) : refreshOpenQuotes(false, false));
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
        Instant now = clock.now();
        if (lastPostTradeReplenishmentAt != null && now.isBefore(lastPostTradeReplenishmentAt.plus(POST_TRADE_REPLENISH_INTERVAL))) {
            return CompletableFuture.completedFuture(null);
        }
        lastPostTradeReplenishmentAt = now;
        return enqueue(() -> closeRequested ? CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0)) : refreshOpenQuotes(true, true))
                .thenApply(ignored -> null);
    }

    private CompletionStage<QuoteRefreshResult> refreshOpenQuotes(boolean avoidRestingPlayerCross, boolean forceRequote) {
        if (!session.get().acceptsMatching()) return CompletableFuture.completedFuture(new QuoteRefreshResult(Set.of(), 0));
        synchronized (this) { systemQuotePassActive = true; }
        List<String> degraded = new ArrayList<>();
        CompletionStage<Integer> chain = CompletableFuture.completedFuture(0);
        for (BluechipRepository.BluechipCompany bluechip : bluechips.all()) {
            chain = chain.thenCompose(total -> needsRequote(bluechip, forceRequote).thenCompose(needs -> {
                if (!needs) return CompletableFuture.completedFuture(total);
                return refresh(bluechip, avoidRestingPlayerCross).thenApply(result -> {
                    quotedModelPrices.put(bluechip.listing().stockCode(), bluechip.modelPrice());
                    if (result.degraded()) degraded.add(bluechip.listing().stockCode());
                    return total + result.orders();
                });
            }));
        }
        return chain.thenCompose(orders -> {
            QuoteRefreshResult result = new QuoteRefreshResult(Set.copyOf(new LinkedHashSet<>(degraded)), orders);
            // The opening catch-up can run before these system orders exist. Match once only after the full quote batch.
            return orders > 0 && session.get().acceptsMatching()
                    ? queuedMatcher.get().thenApply(ignored -> result)
                    : CompletableFuture.completedFuture(result);
        }).whenComplete((ignored, failure) -> finishSystemQuotePass());
    }

    private CompletionStage<Boolean> needsRequote(BluechipRepository.BluechipCompany bluechip, boolean forceRequote) {
        if (forceRequote || !bluechip.modelPrice().equals(quotedModelPrices.get(bluechip.listing().stockCode()))) {
            return CompletableFuture.completedFuture(true);
        }
        return market.openOrderCount(bluechip.systemAccountId(), bluechip.listing().stockCode())
                .thenApply(openOrders -> openOrders < LEVELS * 2);
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
        if (paused) {
            closeRequested = true;
            return enqueue(this::cancelSystemQuotes);
        }
        // "恢复" must make the book usable now, not merely clear a flag and wait
        // for the next scheduled refresh.  The refresh remains serialized behind
        // any preceding cancellation through lifecycle.
        return refreshQuotes().thenApply(QuoteRefreshResult::placedOrders);
    }

    private CompletionStage<Integer> cancelSystemQuotes() {
        CompletionStage<Integer> chain = CompletableFuture.completedFuture(0);
        for (BluechipRepository.BluechipCompany bluechip : bluechips.all()) {
            chain = chain.thenCompose(total -> market.cancelOpenOrders(bluechip.systemAccountId(), bluechip.listing().stockCode()).thenApply(cancelled -> total + cancelled));
        }
        return chain.thenApply(cancelled -> { quotedModelPrices.clear(); lastPostTradeReplenishmentAt = null; return cancelled; });
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
                long notional = Math.multiplyExact(bid, SHARES_PER_LEVEL);
                long cost = Math.addExact(notional, FeePolicy.cumulativeFee(Money.ofMinor(notional), market.buyerFeeBps()).minorUnits());
                if (cash >= cost) { quotes.add(new Quote(Side.BUY, Money.ofMinor(bid), SHARES_PER_LEVEL)); cash -= cost; }
            }
            long ask = bluechip.modelPrice().minorUnits() + Math.multiplyExact(step, level);
            if (boundaries.highestBid().isPresent()) ask = Math.max(ask, Math.addExact(boundaries.highestBid().get().minorUnits(), Math.multiplyExact(step, level)));
            if (ask < bluechip.upperPrice().minorUnits() && shares >= SHARES_PER_LEVEL) { quotes.add(new Quote(Side.SELL, Money.ofMinor(ask), SHARES_PER_LEVEL)); shares -= SHARES_PER_LEVEL; }
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
