package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.finance.ShareHolding;
import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.domain.trading.Trade;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.LocalDate;
import cn.blockeco.exchange.application.*;

/** SQL facts for reservations and fills. Every write is part of the caller's transaction. */
public interface SecondaryTradingRepository {
    LimitOrder reserveBuy(Connection connection, LimitOrder order) throws SQLException;
    LimitOrder reserveSell(Connection connection, LimitOrder order) throws SQLException;
    /** Must be checked in the same transaction as the reservation/order insert. */
    boolean isListed(Connection connection, LimitOrder order) throws SQLException;
    Optional<StockListing> findListing(Connection connection, String stockCode) throws SQLException;
    /** Best active maker which crosses the supplied taker, using price-time priority. */
    Optional<LimitOrder> nextCrossingMaker(Connection connection, LimitOrder taker) throws SQLException;
    /** Active orders in their immutable global acceptance order for an opening catch-up. */
    List<LimitOrder> queuedOrders(Connection connection) throws SQLException;
    /** Records an observed session and returns whether this opening still needs its one catch-up. */
    boolean claimOpeningCatchUp(Connection connection, LocalDate tradingDay, boolean acceptsMatching) throws SQLException;
    Settlement settleTrade(Connection connection, Trade trade) throws SQLException;
    void releaseOrder(Connection connection, UUID orderId, LimitOrder.State terminalState) throws SQLException;
    void cancelTakerForSelfTrade(Connection connection, UUID orderId) throws SQLException;
    Optional<LimitOrder> findOrder(Connection connection, UUID orderId) throws SQLException;
    Optional<LimitOrder> findOrder(UUID orderId);
    Optional<ShareHolding> findHolding(cn.blockeco.exchange.domain.company.CompanyId companyId, UUID playerId);
    Money compensationFund();
    PortfolioView portfolio(UUID playerId);
    List<OrderView> orders(UUID playerId, int limit);
    List<TradeView> trades(UUID playerId, int limit);
    List<OrderBookLevel> bids(String stockCode, int depth);
    List<OrderBookLevel> asks(String stockCode, int depth);

    record Settlement(LimitOrder buyOrder, LimitOrder sellOrder, Money releasedCash) { }
    final class InsufficientCashException extends IllegalStateException { public InsufficientCashException(String message) { super(message); } }
    final class InsufficientSharesException extends IllegalStateException { public InsufficientSharesException(String message) { super(message); } }
    final class OptimisticStateException extends IllegalStateException { public OptimisticStateException(String message) { super(message); } }
}
