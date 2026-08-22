package cn.blockeco.exchange.infrastructure.sql;

/** Raised when every bounded public-market stock code has been allocated. */
public final class StockCodeExhaustedException extends RuntimeException {
    public StockCodeExhaustedException() {
        super("股票代码已分配完毕，IPO关闭未完成上市。");
    }
}
