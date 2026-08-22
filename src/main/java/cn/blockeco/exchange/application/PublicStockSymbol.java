package cn.blockeco.exchange.application;

import java.util.Objects;
import java.util.Optional;

/** Cache-safe name/code pair used for Paper tab completion. */
public record PublicStockSymbol(String companyName, Optional<String> stockCode) {
    public PublicStockSymbol { Objects.requireNonNull(companyName); Objects.requireNonNull(stockCode); }
}
