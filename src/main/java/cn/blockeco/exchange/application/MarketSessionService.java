package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Persists market observations and runs the opening queue catch-up at most once per trading day. */
public final class MarketSessionService {
    private final SecondaryMarketService market; private final SecondaryTradingRepository orders; private final TransactionRunner transactions;
    private final Executor sql; private final AppClock clock; private final ZoneId zone; private final Supplier<MarketSession> session;

    public MarketSessionService(SecondaryMarketService market, SecondaryTradingRepository orders, TransactionRunner transactions, Executor sql, AppClock clock, ZoneId zone, Supplier<MarketSession> session) {
        this.market=Objects.requireNonNull(market,"market");this.orders=Objects.requireNonNull(orders,"orders");this.transactions=Objects.requireNonNull(transactions,"transactions");this.sql=Objects.requireNonNull(sql,"sql");this.clock=Objects.requireNonNull(clock,"clock");this.zone=Objects.requireNonNull(zone,"zone");this.session=Objects.requireNonNull(session,"session");
    }
    public CompletionStage<Integer> onSessionTransition() {
        MarketSession observed=session.get();
        return CompletableFuture.supplyAsync(() -> transactions.inTransaction(c -> orders.claimOpeningCatchUp(c,clock.now().atZone(zone).toLocalDate(),observed.acceptsMatching())),sql)
                .thenCompose(opening -> opening ? market.matchQueuedOrders() : CompletableFuture.completedFuture(0));
    }
}
