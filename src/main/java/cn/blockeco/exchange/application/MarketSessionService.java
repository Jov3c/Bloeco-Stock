package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.ZoneId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Persists market observations and runs the opening queue catch-up at most once per trading day. */
public final class MarketSessionService {
    private final SecondaryMarketService market; private final SecondaryTradingRepository orders; private final TransactionRunner transactions;
    private final Executor sql; private final AppClock clock; private final ZoneId zone; private final Supplier<MarketSession> session;
    private final OpeningMatcher openingMatcher;

    public MarketSessionService(SecondaryMarketService market, SecondaryTradingRepository orders, TransactionRunner transactions, Executor sql, AppClock clock, ZoneId zone, Supplier<MarketSession> session) {
        this(market,orders,transactions,sql,clock,zone,session,market::matchQueuedOrders);
    }
    MarketSessionService(SecondaryMarketService market, SecondaryTradingRepository orders, TransactionRunner transactions, Executor sql, AppClock clock, ZoneId zone, Supplier<MarketSession> session, OpeningMatcher openingMatcher) {
        this.market=Objects.requireNonNull(market,"market");this.orders=Objects.requireNonNull(orders,"orders");this.transactions=Objects.requireNonNull(transactions,"transactions");this.sql=Objects.requireNonNull(sql,"sql");this.clock=Objects.requireNonNull(clock,"clock");this.zone=Objects.requireNonNull(zone,"zone");this.session=Objects.requireNonNull(session,"session");
        this.openingMatcher=Objects.requireNonNull(openingMatcher,"openingMatcher");
    }
    public CompletionStage<Integer> onSessionTransition() {
        MarketSession observed=session.get();
        return CompletableFuture.supplyAsync(() -> transactions.inTransaction(c -> {
            boolean opening=orders.claimOpeningCatchUp(c,clock.now().atZone(zone).toLocalDate(),observed.acceptsMatching());
            return opening ? openingMatcher.match(c) : 0;
        }),sql);
    }

    @FunctionalInterface interface OpeningMatcher { int match(Connection connection) throws SQLException; }
}
