package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.FeePolicy;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipRepository;
import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** A finite account which occasionally takes existing maker liquidity through ordinary limit orders. */
public final class BluechipSystemParticipantService {
    private final BluechipRepository bluechips; private final SecondaryTradingRepository orders; private final SecondaryMarketService market;
    private final Supplier<MarketSession> session; private final AppClock clock; private final UUID participantAccountId;
    private CompletionStage<Void> lifecycle = CompletableFuture.completedFuture(null);

    public BluechipSystemParticipantService(BluechipRepository bluechips, SecondaryTradingRepository orders, SecondaryMarketService market,
                                            Supplier<MarketSession> session, AppClock clock, UUID participantAccountId) {
        this.bluechips = Objects.requireNonNull(bluechips); this.orders = Objects.requireNonNull(orders); this.market = Objects.requireNonNull(market);
        this.session = Objects.requireNonNull(session); this.clock = Objects.requireNonNull(clock); this.participantAccountId = requireParticipant(participantAccountId);
    }

    public synchronized CompletionStage<Integer> tick() {
        return enqueue(() -> session.get().acceptsMatching() ? placeOne(clock.now()) : CompletableFuture.completedFuture(0));
    }

    /** Cancels only this participant's residual ordinary orders at a session close. */
    public synchronized CompletionStage<Integer> close() {
        return enqueue(() -> {
            CompletionStage<Integer> cancelled = CompletableFuture.completedFuture(0);
            for (BluechipRepository.BluechipCompany bluechip : bluechips.all()) {
                cancelled = cancelled.thenCompose(total -> market.cancelOpenOrders(participantAccountId, bluechip.listing().stockCode())
                        .thenApply(count -> total + count));
            }
            return cancelled;
        });
    }

    private CompletionStage<Integer> placeOne(Instant now) {
        List<BluechipRepository.BluechipCompany> all = bluechips.all().stream()
                .sorted(Comparator.comparing(company -> company.listing().stockCode())).toList();
        if (all.isEmpty()) return CompletableFuture.completedFuture(0);
        long step = activityStep(now);
        List<BluechipRepository.BluechipCompany> due = all.stream().filter(company -> due(company, step)).toList();
        if (due.isEmpty()) return CompletableFuture.completedFuture(0);
        BluechipRepository.BluechipCompany chosen = due.get(Math.floorMod(step, due.size()));
        long wanted = 1L + Math.floorMod(step + chosen.listing().stockCode().hashCode(), 10L);
        if ((step & 1L) == 0) return buy(chosen, wanted);
        return sell(chosen, wanted);
    }

    private CompletionStage<Integer> buy(BluechipRepository.BluechipCompany bluechip, long wanted) {
        Money cash = market.availableCash(participantAccountId); long shares = affordableShares(cash, bluechip.upperPrice(), wanted);
        if (shares == 0) return CompletableFuture.completedFuture(0);
        return market.placeBuy(participantAccountId, bluechip.listing().stockCode(), shares, bluechip.upperPrice()).thenApply(ignored -> 1);
    }
    private CompletionStage<Integer> sell(BluechipRepository.BluechipCompany bluechip, long wanted) {
        long available = orders.findHolding(bluechip.companyId(), participantAccountId).map(holding -> holding.availableShares()).orElse(0L);
        long shares = Math.min(wanted, available);
        if (shares == 0) return CompletableFuture.completedFuture(0);
        return market.placeSell(participantAccountId, bluechip.listing().stockCode(), shares, bluechip.lowerPrice()).thenApply(ignored -> 1);
    }
    private int affordableShares(Money cash, Money limit, long wanted) {
        for (long shares = wanted; shares > 0; shares--) {
            long notional = Math.multiplyExact(shares, limit.minorUnits());
            long total = Math.addExact(notional, FeePolicy.cumulativeFee(Money.ofMinor(notional), market.buyerFeeBps()).minorUnits());
            if (cash.minorUnits() >= total) return Math.toIntExact(shares);
        }
        return 0;
    }
    static long activityStep(Instant now) { return Math.floorDiv(Objects.requireNonNull(now).getEpochSecond(), 8L); }
    private static boolean due(BluechipRepository.BluechipCompany bluechip, long step) {
        int cadence = 1 + Math.floorMod(bluechip.listing().stockCode().hashCode(), 5);
        int offset = Math.floorMod(bluechip.listing().stockCode().hashCode() / 5, cadence);
        return Math.floorMod(step, cadence) == offset;
    }
    private <T> CompletionStage<T> enqueue(java.util.function.Supplier<CompletionStage<T>> operation) {
        CompletionStage<T> scheduled = lifecycle.handle((ignored, failure) -> null).thenCompose(ignored -> operation.get());
        lifecycle = scheduled.handle((ignored, failure) -> null); return scheduled;
    }
    private static UUID requireParticipant(UUID value) { Objects.requireNonNull(value); if (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0) throw new IllegalArgumentException("participant account must not be zero"); return value; }
}
