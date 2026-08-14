package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

public interface EconomyGateway {
    Result withdraw(UUID playerId, Money amount);
    Result deposit(UUID playerId, Money amount);

    record Result(Outcome outcome, String message) {
        public Result { Objects.requireNonNull(outcome); message = message == null ? "" : message; }
        public static Result success(String message) { return new Result(Outcome.SUCCESS, message); }
        public static Result insufficientFunds(String message) { return new Result(Outcome.INSUFFICIENT_FUNDS, message); }
        public static Result providerFailure(String message) { return new Result(Outcome.PROVIDER_FAILURE, message); }
    }
    enum Outcome { SUCCESS, INSUFFICIENT_FUNDS, PROVIDER_FAILURE }
}
