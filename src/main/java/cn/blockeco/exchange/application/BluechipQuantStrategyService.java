package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.bluechip.QuantDecision;
import cn.blockeco.exchange.domain.bluechip.QuantRiskState;
import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipParticipantRepository;
import cn.blockeco.exchange.ports.BluechipRepository;
import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Finite, auditable system participant that can only use ordinary secondary-market orders. */
public final class BluechipQuantStrategyService {
    private final BluechipRepository bluechips; private final SecondaryTradingRepository orders; private final SecondaryMarketService market;
    private final TransactionRunner transactions; private final Executor sql; private final Supplier<MarketSession> session; private final AppClock clock;
    private final UUID participant; private final int threshold; private final QuantSignalPolicy signals; private final QuantRiskPolicy riskPolicy;
    private final BluechipParticipantRepository participantLiquidity;
    private CompletionStage<Void> lifecycle = CompletableFuture.completedFuture(null); private long lastStep = Long.MIN_VALUE;
    private long lastLiquidityCheck = Long.MIN_VALUE;
    private static final long LIQUIDITY_CHECK_INTERVAL_SECONDS = 30L;
    private static final Money PARTICIPANT_CASH_FLOOR = Money.ofMinor(2_500_000L);
    private static final Money PARTICIPANT_CASH_TARGET = Money.ofMinor(10_000_000L);
    private static final long PARTICIPANT_SHARES_FLOOR = 50L;
    private static final long PARTICIPANT_SHARES_TARGET = 200L;

    public BluechipQuantStrategyService(BluechipRepository bluechips, SecondaryTradingRepository orders, SecondaryMarketService market,
                                        TransactionRunner transactions, Executor sql, Supplier<MarketSession> session, AppClock clock,
                                        UUID participant, BluechipParticipantRepository participantLiquidity, cn.blockeco.exchange.paper.BluechipQuantConfig configuration,
                                        QuantSignalPolicy signals, QuantRiskPolicy riskPolicy) {
        this.bluechips = Objects.requireNonNull(bluechips); this.orders = Objects.requireNonNull(orders); this.market = Objects.requireNonNull(market);
        this.transactions = Objects.requireNonNull(transactions); this.sql = Objects.requireNonNull(sql); this.session = Objects.requireNonNull(session); this.clock = Objects.requireNonNull(clock);
        this.participant = Objects.requireNonNull(participant); this.threshold = Objects.requireNonNull(configuration).targetConfidenceBps();
        this.participantLiquidity = Objects.requireNonNull(participantLiquidity);
        this.signals = Objects.requireNonNull(signals); this.riskPolicy = Objects.requireNonNull(riskPolicy);
    }

    /** A step is admitted once only, so concurrent scheduler callbacks cannot double-trade. */
    public synchronized CompletionStage<Integer> tick() {
        Instant now = clock.now(); long step = activityStep(now);
        if (!session.get().acceptsMatching() || step == lastStep) return CompletableFuture.completedFuture(0);
        lastStep = step;
        return enqueue(() -> CompletableFuture.supplyAsync(() -> ensureLiquidity(now), sql)
                .thenCompose(ignored -> CompletableFuture.supplyAsync(() -> snapshot(now, step), sql)).thenCompose(this::decide));
    }

    static long activityStep(Instant now) { return Objects.requireNonNull(now).getEpochSecond(); }

    private synchronized boolean ensureLiquidity(Instant now) {
        if (lastLiquidityCheck != Long.MIN_VALUE && now.getEpochSecond() - lastLiquidityCheck < LIQUIDITY_CHECK_INTERVAL_SECONDS) return false;
        List<BluechipRepository.BluechipCompany> companies = bluechips.all();
        if (companies.isEmpty()) return false;
        UUID maker = companies.getFirst().systemAccountId();
        boolean rebalanced = transactions.inTransaction(connection -> participantLiquidity.rebalanceBelowFloor(connection, maker, participant,
                PARTICIPANT_CASH_FLOOR, PARTICIPANT_CASH_TARGET, PARTICIPANT_SHARES_FLOOR, PARTICIPANT_SHARES_TARGET, companies));
        lastLiquidityCheck = now.getEpochSecond();
        return rebalanced;
    }

    private Snapshot snapshot(Instant now, long step) {
        List<BluechipRepository.BluechipCompany> all = bluechips.all().stream()
                .sorted(Comparator.comparing(company -> company.listing().stockCode())).toList();
        if (all.isEmpty()) return null;
        BluechipRepository.BluechipCompany company = all.get(Math.floorMod(step, all.size()));
        var book = new SecondaryMarketQueryService.OrderBook(orders.bids(company.listing().stockCode(), 5), orders.asks(company.listing().stockCode(), 5));
        QuantRiskState risk = bluechips.loadQuantRisk(company.listing().stockCode())
                .orElse(new QuantRiskState(company.listing().stockCode(), 0, 0, now, now));
        int eventImpact = bluechips.activeEventImpactBps(company.listing().stockCode(), company.industry(), now);
        return new Snapshot(company, book, risk, eventImpact, now, step);
    }

    private CompletionStage<Integer> decide(Snapshot snapshot) {
        if (snapshot == null) return CompletableFuture.completedFuture(0);
        var company = snapshot.company();
        QuantSignalPolicy.Signal observed = signals.evaluate(snapshot.book(), company.listing().issueReferencePrice(), company.modelPrice(), snapshot.eventImpactBps());
        QuantSignalPolicy.Signal signal = signals.projectForMarketActivity(observed, company.listing().stockCode(), snapshot.step(), threshold);
        long desired = 1L + Math.floorMod(snapshot.step() + company.listing().stockCode().hashCode(), 10L);
        if (signal.confidenceBps() < threshold || "NONE".equals(signal.direction())) {
            return audit(snapshot, signal, "NO_TRADE", 0, 0).thenApply(ignored -> 0);
        }
        boolean buying = "BUY".equals(signal.direction());
        long holding = orders.findHolding(company.companyId(), participant).map(value -> value.availableShares()).orElse(0L);
        Money limit = buying ? company.upperPrice() : company.lowerPrice();
        long shares = riskPolicy.orderShares(desired, market.availableCash(participant).minorUnits(), holding, limit, market.buyerFeeBps(), snapshot.risk(), buying);
        if (shares == 0) return audit(snapshot, signal, "RISK_LIMIT", 0, 0).thenApply(ignored -> 0);
        CompletionStage<OrderPlacementResult> placed = buying
                ? market.placeBuy(participant, company.listing().stockCode(), shares, limit)
                : market.placeSell(participant, company.listing().stockCode(), shares, limit);
        return placed.thenCompose(result -> {
            long filled = result.order().originalShares() - result.order().remainingShares();
            return audit(snapshot, signal, signal.direction(), shares, filled).thenApply(ignored -> 1);
        });
    }

    private CompletionStage<Void> audit(Snapshot snapshot, QuantSignalPolicy.Signal signal, String action, long requested, long filled) {
        return CompletableFuture.runAsync(() -> transactions.inTransaction(connection -> {
            bluechips.saveQuantRisk(connection, snapshot.risk());
            bluechips.recordQuantDecision(connection, new QuantDecision(UUID.randomUUID().toString(), snapshot.company().listing().stockCode(),
                    signal.direction(), signal.confidenceBps(), action, requested, filled, 0, snapshot.risk().riskLevel(), snapshot.now()));
            return null;
        }), sql);
    }

    private synchronized <T> CompletionStage<T> enqueue(java.util.function.Supplier<CompletionStage<T>> operation) {
        CompletionStage<T> scheduled = lifecycle.handle((ignored, failure) -> null).thenCompose(ignored -> operation.get());
        lifecycle = scheduled.handle((ignored, failure) -> null); return scheduled;
    }

    private record Snapshot(BluechipRepository.BluechipCompany company, SecondaryMarketQueryService.OrderBook book,
                            QuantRiskState risk, int eventImpactBps, Instant now, long step) { }
}
