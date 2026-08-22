package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

public interface EconomyGateway {
    Result withdraw(UUID playerId, Money amount);
    Result deposit(UUID playerId, Money amount);
    default Money balance(UUID playerId) { throw new UnsupportedOperationException("balance not implemented by this economy gateway"); }

    record Result(Outcome outcome, String message, boolean providerWasCalled) {
        public Result { Objects.requireNonNull(outcome); message = message == null ? "" : message; }
        public static Result success(String message) { return new Result(Outcome.SUCCESS, message, true); }
        public static Result insufficientFunds(String message) { return new Result(Outcome.INSUFFICIENT_FUNDS, message, false); }
        public static Result providerFailure(String message) { return new Result(Outcome.PROVIDER_FAILURE, message, true); }
        /** The provider has not been invoked (for example a pre-withdrawal balance check). */
        public static Result notCalledFailure(String message) { return new Result(Outcome.PROVIDER_FAILURE, message, false); }
    }
    enum Outcome { SUCCESS, INSUFFICIENT_FUNDS, PROVIDER_FAILURE }
}
