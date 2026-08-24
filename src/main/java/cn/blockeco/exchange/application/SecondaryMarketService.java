package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.trading.FeePolicy;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.domain.trading.Trade;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Serial, transactionally committed GTC limit order placement and matching. */
public final class SecondaryMarketService {
    private final SecondaryTradingRepository orders;
    private final TransactionRunner transactions;
    private final Executor sql;
    private final AppClock clock;
    private final int feeBps;
    private final Supplier<MarketSession> session;

    public SecondaryMarketService(SecondaryTradingRepository orders, TransactionRunner transactions, Executor sql, AppClock clock, int feeBps) {
        this(orders, transactions, sql, clock, feeBps, () -> new MarketSession(true));
    }

    public SecondaryMarketService(SecondaryTradingRepository orders, TransactionRunner transactions, Executor sql, AppClock clock, int feeBps, Supplier<MarketSession> session) {
        this.orders = Objects.requireNonNull(orders, "orders"); this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.sql = Objects.requireNonNull(sql, "sql"); this.clock = Objects.requireNonNull(clock, "clock");
        this.session = Objects.requireNonNull(session, "session");
        if (feeBps < 0 || feeBps > 10_000) throw new IllegalArgumentException("feeBps must be between 0 and 10000");
        this.feeBps = feeBps;
    }

    /** Matches all resting orders once, strictly in their accepted priority order. */
    public CompletionStage<Integer> matchQueuedOrders() {
        if (!session.get().acceptsMatching()) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> transactions.inTransaction(connection -> {
            int matches=0;
            for (LimitOrder queued : orders.queuedOrders(connection)) {
                LimitOrder current=orders.findOrder(connection,queued.id()).orElse(null);
                if (current!=null) matches+=match(connection,current);
            }
            return matches;
        }), sql);
    }

    public CompletionStage<OrderPlacementResult> placeBuy(UUID player, String stockCode, long shares, Money limit) {
        return place(player, stockCode, shares, limit, LimitOrder.Side.BUY);
    }

    public CompletionStage<OrderPlacementResult> placeSell(UUID player, String stockCode, long shares, Money limit) {
        return place(player, stockCode, shares, limit, LimitOrder.Side.SELL);
    }

    public CompletionStage<OrderPlacementResult> cancel(UUID player, UUID orderId) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(orderId, "orderId");
        return CompletableFuture.supplyAsync(() -> transactions.inTransaction(connection -> {
            LimitOrder current = orders.findOrder(connection, orderId).orElseThrow(() -> new IllegalArgumentException("order not found"));
            if (!current.playerId().equals(player)) throw new IllegalArgumentException("only the order owner may cancel it");
            if (current.state() == LimitOrder.State.CANCELLED || current.state() == LimitOrder.State.SELF_TRADE_PREVENTED || current.state() == LimitOrder.State.FILLED) return new OrderPlacementResult(current);
            orders.releaseOrder(connection, orderId, LimitOrder.State.CANCELLED);
            return new OrderPlacementResult(terminal(current, LimitOrder.State.CANCELLED));
        }), sql);
    }

    private CompletionStage<OrderPlacementResult> place(UUID player, String stockCode, long shares, Money limit, LimitOrder.Side side) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(stockCode, "stockCode"); Objects.requireNonNull(limit, "limit");
        return CompletableFuture.supplyAsync(() -> transactions.inTransaction(connection -> {
            StockListing listing = orders.findListing(connection, stockCode).orElseThrow(() -> new IllegalArgumentException("stock is not listed"));
            Instant acceptedAt = clock.now();
            LimitOrder candidate = newOrder(player, listing, shares, limit, side, acceptedAt);
            if (!orders.isListed(connection, candidate)) throw new IllegalArgumentException("stock is not listed");
            LimitOrder taker = side == LimitOrder.Side.BUY ? orders.reserveBuy(connection, candidate) : orders.reserveSell(connection, candidate);
            if (session.get().acceptsMatching()) match(connection,taker);
            taker=orders.findOrder(connection,taker.id()).orElseThrow();
            return new OrderPlacementResult(taker);
        }), sql);
    }

    private int match(java.sql.Connection connection, LimitOrder taker) throws java.sql.SQLException {
        int matches=0;
        while (taker.state() == LimitOrder.State.OPEN || taker.state() == LimitOrder.State.PARTIALLY_FILLED) {
            LimitOrder maker = orders.nextCrossingMaker(connection, taker).orElse(null);
            if (maker == null) break;
            if (maker.playerId().equals(taker.playerId())) { orders.cancelTakerForSelfTrade(connection, taker.id()); break; }
            LimitOrder buy = taker.side() == LimitOrder.Side.BUY ? taker : maker;
            LimitOrder sell = taker.side() == LimitOrder.Side.SELL ? taker : maker;
            long filledShares = Math.min(buy.remainingShares(), sell.remainingShares()); Money price = maker.limitPrice();
            Money notional = Money.ofMinor(Math.multiplyExact(price.minorUnits(), filledShares)); Money nextFee = FeePolicy.cumulativeFee(buy.filledNotional().plus(notional), buy.feeBps());
            Trade trade = new Trade(UUID.randomUUID(), buy.companyId(), buy.stockCode(), buy.id(), sell.id(), filledShares, price, notional, nextFee.minus(buy.feeCharged()), clock.now());
            SecondaryTradingRepository.Settlement settlement = orders.settleTrade(connection, trade); taker = taker.id().equals(settlement.buyOrder().id()) ? settlement.buyOrder() : settlement.sellOrder(); matches++;
        }
        return matches;
    }

    private LimitOrder newOrder(UUID player, StockListing listing, long shares, Money limit, LimitOrder.Side side, Instant acceptedAt) {
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        long reserve = side == LimitOrder.Side.BUY
                ? Math.addExact(Math.multiplyExact(shares, limit.minorUnits()), FeePolicy.cumulativeFee(Money.ofMinor(Math.multiplyExact(shares, limit.minorUnits())), feeBps).minorUnits()) : 0;
        return new LimitOrder(UUID.randomUUID(), listing.companyId(), listing.stockCode(), player, side, limit, shares, shares, 1,
                Money.ofMinor(reserve), Money.zero(), Money.zero(), side == LimitOrder.Side.BUY ? feeBps : 0, acceptedAt, LimitOrder.State.OPEN);
    }

    private static LimitOrder terminal(LimitOrder order, LimitOrder.State state) {
        return new LimitOrder(order.id(), order.companyId(), order.stockCode(), order.playerId(), order.side(), order.limitPrice(), order.originalShares(),
                order.remainingShares(), order.prioritySequence(), Money.zero(), order.filledNotional(), order.feeCharged(), order.feeBps(), order.acceptedAt(), state);
    }
}
