package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import java.util.List;
import java.util.Objects;

public record PortfolioView(Money availableCash, Money reservedCash, List<SecondaryMarketRow> holdings) {
    public PortfolioView { Objects.requireNonNull(availableCash); Objects.requireNonNull(reservedCash); holdings=List.copyOf(holdings); }
}
