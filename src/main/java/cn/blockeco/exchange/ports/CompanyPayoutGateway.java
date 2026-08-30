package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

/** External Vault deposit boundary for a founder cash-out. */
@FunctionalInterface
public interface CompanyPayoutGateway {
    Result depositFounder(UUID recipient, Money amount, UUID operationId);

    record Result(Outcome outcome, String detail) {
        public Result { Objects.requireNonNull(outcome, "outcome"); detail = detail == null ? "" : detail; }
        public static Result success(String detail) { return new Result(Outcome.SUCCESS, detail); }
        /** The provider definitively did not debit escrow. The local reserve can be released. */
        public static Result knownFailure(String detail) { return new Result(Outcome.KNOWN_FAILURE, detail); }
        /** The Vault outcome cannot be proven. It requires manual recovery and is never replayed. */
        public static Result unknown(String detail) { return new Result(Outcome.UNKNOWN, detail); }
    }

    enum Outcome { SUCCESS, KNOWN_FAILURE, UNKNOWN }
}
