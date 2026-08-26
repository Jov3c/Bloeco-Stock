package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import java.util.Objects;
import java.util.UUID;

/** Immutable, owner-bound GUI state. A fresh ID invalidates delayed callbacks from prior pages. */
public record StockGuiSession(UUID id, UUID owner, Page page, int pageIndex, String stockCode, Draft draft, ChartMode chartMode) {
    public StockGuiSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(chartMode, "chartMode");
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must not be negative");
    }

    public StockGuiSession(UUID id, UUID owner, Page page, int pageIndex, String stockCode, Draft draft) {
        this(id, owner, page, pageIndex, stockCode, draft, ChartMode.INTRADAY);
    }

    public static StockGuiSession open(UUID owner) {
        return new StockGuiSession(UUID.randomUUID(), Objects.requireNonNull(owner, "owner"), Page.HOME, 0, null, null, ChartMode.INTRADAY);
    }

    public StockGuiSession next(Page nextPage, int nextPageIndex, String nextStockCode, Draft nextDraft) {
        return new StockGuiSession(UUID.randomUUID(), owner, nextPage, nextPageIndex, nextStockCode, nextDraft, chartMode);
    }

    public StockGuiSession withChartMode(ChartMode nextChartMode) {
        return new StockGuiSession(UUID.randomUUID(), owner, page, pageIndex, stockCode, draft, nextChartMode);
    }

    public boolean belongsTo(UUID playerId) {
        return owner.equals(playerId);
    }

    public enum Page { HOME, MARKET, DETAIL, NEWS, CASH, PORTFOLIO, ORDERS, TRADES, INPUT, CONFIRM, SUBMITTING, RESULT }
    public enum ChartMode { INTRADAY, DAILY }

    public sealed interface Draft permits CashTransfer, LimitOrderDraft, CancelOrder, InputDraft {
    }

    public record CashTransfer(boolean deposit, Money amount) implements Draft {
        public CashTransfer { Objects.requireNonNull(amount, "amount"); }
    }

    public record LimitOrderDraft(String stockCode, LimitOrder.Side side, long shares, Money limitPrice) implements Draft {
        public LimitOrderDraft {
            Objects.requireNonNull(stockCode, "stockCode"); Objects.requireNonNull(side, "side"); Objects.requireNonNull(limitPrice, "limitPrice");
            if (stockCode.isBlank() || shares <= 0 || limitPrice.minorUnits() <= 0) throw new IllegalArgumentException("invalid limit order draft");
        }
    }

    public record CancelOrder(UUID orderId) implements Draft {
        public CancelOrder { Objects.requireNonNull(orderId, "orderId"); }
    }

    public record InputDraft(InputKind kind, boolean deposit, String stockCode, LimitOrder.Side side, long shares) implements Draft {
        public InputDraft { Objects.requireNonNull(kind, "kind"); if (kind != InputKind.CASH_AMOUNT) { Objects.requireNonNull(stockCode, "stockCode"); Objects.requireNonNull(side, "side"); } }
    }

    public enum InputKind { CASH_AMOUNT, ORDER_SHARES, ORDER_PRICE }
}
