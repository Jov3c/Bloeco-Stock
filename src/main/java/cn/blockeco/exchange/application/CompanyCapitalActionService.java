package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.governance.CompanyGovernanceAction;
import cn.blockeco.exchange.domain.governance.CompanyPayoutOperation;
import cn.blockeco.exchange.domain.governance.GovernanceActionState;
import cn.blockeco.exchange.domain.governance.GovernanceActionType;
import cn.blockeco.exchange.domain.governance.PayoutOperationState;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.CompanyExitRepository;
import cn.blockeco.exchange.ports.CompanyPayoutGateway;
import cn.blockeco.exchange.ports.CompanyRepository;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Announces and safely executes founder-controlled buybacks and cash-outs. */
public final class CompanyCapitalActionService {
    private static final Duration ANNOUNCEMENT_PERIOD = Duration.ofHours(12);
    private final CompanyRepository companies;
    private final CompanyExitRepository exits;
    private final SecuritiesCashRepository securitiesCash;
    private final TransactionRunner transactions;
    private final CompanyPayoutGateway payoutGateway;
    private final AppClock clock;

    public CompanyCapitalActionService(CompanyRepository companies, CompanyExitRepository exits,
            SecuritiesCashRepository securitiesCash, TransactionRunner transactions,
            CompanyPayoutGateway payoutGateway, AppClock clock) {
        this.companies = Objects.requireNonNull(companies, "companies");
        this.exits = Objects.requireNonNull(exits, "exits");
        this.securitiesCash = Objects.requireNonNull(securitiesCash, "securitiesCash");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.payoutGateway = Objects.requireNonNull(payoutGateway, "payoutGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UUID announceBuyback(UUID founder, CompanyId companyId, Money budget, Money pricePerShare, String correlationKey) {
        requirePositive(budget, "buyback budget"); requirePositive(pricePerShare, "buyback price");
        return announce(founder, companyId, GovernanceActionType.BUYBACK, budget.minorUnits(), pricePerShare.minorUnits(), correlationKey, "公司回购公告");
    }

    public UUID announceFounderCashOut(UUID founder, CompanyId companyId, Money amount, String correlationKey) {
        requirePositive(amount, "cash-out amount");
        return announce(founder, companyId, GovernanceActionType.FOUNDER_CASH_OUT, amount.minorUnits(), 0, correlationKey, "创始人套现公告");
    }

    public ExecutionResult execute(UUID founder, UUID actionId) {
        CompanyGovernanceAction action = exits.findAction(actionId).orElseThrow(() -> new IllegalArgumentException("capital action does not exist"));
        Company company = requireFounderOfListedCompany(founder, action.companyId());
        if (!action.actorUuid().equals(founder) || !company.founderId().equals(action.actorUuid())) throw new IllegalArgumentException("only the announcing founder may execute this action");
        if (action.state() != GovernanceActionState.ANNOUNCED) throw new IllegalStateException("capital action is already started or finished");
        Instant now = clock.now();
        if (now.isBefore(action.executableAt())) throw new IllegalStateException("capital action announcement period has not ended");

        CompanyPayoutOperation payout = transactions.inTransaction(connection -> {
            if (!exits.transitionAction(connection, action.id(), GovernanceActionState.ANNOUNCED, GovernanceActionState.EXECUTION_READY, now))
                throw new IllegalStateException("capital action state changed");
            if (!exits.reserveCompanyCash(connection, action.companyId(), action.amountMinor()))
                throw new IllegalStateException("company authoritative cash is insufficient");
            if (!exits.transitionAction(connection, action.id(), GovernanceActionState.EXECUTION_READY, GovernanceActionState.EXECUTING, now))
                throw new IllegalStateException("capital action state changed");
            if (action.type() != GovernanceActionType.FOUNDER_CASH_OUT) return null;
            CompanyPayoutOperation prepared = new CompanyPayoutOperation(UUID.randomUUID(), action.companyId(), action.id(), founder,
                    action.amountMinor(), "founder-cash-out:" + action.correlationKey(), PayoutOperationState.PREPARED, now, now, "awaiting Vault deposit");
            exits.createPayout(connection, prepared);
            return prepared;
        });
        if (action.type() == GovernanceActionType.BUYBACK) return ExecutionResult.EXECUTING;
        return executePayout(action, payout);
    }

    public boolean acceptBuyback(UUID shareholder, UUID actionId, long shares, String correlationKey) {
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        CompanyGovernanceAction action = exits.findAction(actionId).orElseThrow(() -> new IllegalArgumentException("buyback action does not exist"));
        if (action.type() != GovernanceActionType.BUYBACK || action.state() != GovernanceActionState.EXECUTING)
            throw new IllegalStateException("buyback is not executing");
        requireListedCompany(action.companyId());
        return transactions.inTransaction(connection -> exits.acceptBuyback(connection, action.id(), action.companyId(), shareholder,
                shares, correlationKey, securitiesCash, clock.now()));
    }

    /** Returns durable recovery records only; it never invokes Vault again. */
    public List<CompanyPayoutOperation> recoverablePayouts() { return exits.recoverablePayouts(100); }

    private UUID announce(UUID founder, CompanyId companyId, GovernanceActionType type, long amountMinor,
            long pricePerShareMinor, String correlationKey, String announcement) {
        requireListedCompanyFounder(founder, companyId);
        if (correlationKey == null || correlationKey.isBlank()) throw new IllegalArgumentException("correlation key is required");
        Instant now = clock.now(); UUID id = UUID.randomUUID();
        CompanyGovernanceAction action = new CompanyGovernanceAction(id, companyId, founder, type, amountMinor, pricePerShareMinor,
                now, now.plus(ANNOUNCEMENT_PERIOD), GovernanceActionState.ANNOUNCED, correlationKey);
        transactions.inTransaction(connection -> { exits.createAction(connection, action, announcement, now); return null; });
        return id;
    }

    private ExecutionResult executePayout(CompanyGovernanceAction action, CompanyPayoutOperation payout) {
        CompanyPayoutGateway.Result result;
        try { result = payoutGateway.depositFounder(payout.recipientUuid(), Money.ofMinor(payout.amountMinor()), payout.id()); }
        catch (RuntimeException failure) { return ambiguous(payout, "Vault call threw: " + failure.getMessage()); }
        if (result.outcome() == CompanyPayoutGateway.Outcome.KNOWN_FAILURE) return failed(action, payout, result.detail());
        if (result.outcome() == CompanyPayoutGateway.Outcome.UNKNOWN) return ambiguous(payout, result.detail());
        try {
            transactions.inTransaction(connection -> {
                Instant now = clock.now();
                if (!exits.transitionPayout(connection, payout.id(), PayoutOperationState.PREPARED, PayoutOperationState.EXTERNAL_DEBIT_CONFIRMED, result.detail(), now))
                    throw new IllegalStateException("payout state changed before external confirmation was recorded");
                exits.completePayout(connection, payout.id(), now);
                if (!exits.transitionAction(connection, action.id(), GovernanceActionState.EXECUTING, GovernanceActionState.EXECUTED, now))
                    throw new IllegalStateException("capital action state changed before completion");
                return null;
            });
            return ExecutionResult.COMPLETED;
        } catch (RuntimeException failure) {
            // An external success has happened; never issue a second Vault request. PREPARED is made explicit;
            // EXTERNAL_DEBIT_CONFIRMED stays recoverable if the confirmation transaction had already committed.
            markAmbiguousIfPrepared(payout, "Vault succeeded but local completion failed: " + failure.getMessage());
            return ExecutionResult.AMBIGUOUS;
        }
    }

    private ExecutionResult failed(CompanyGovernanceAction action, CompanyPayoutOperation payout, String detail) {
        transactions.inTransaction(connection -> {
            Instant now = clock.now();
            if (!exits.transitionPayout(connection, payout.id(), PayoutOperationState.PREPARED, PayoutOperationState.FAILED, detail, now))
                throw new IllegalStateException("payout state changed before failure was recorded");
            if (!exits.releaseCompanyCash(connection, payout.companyId(), payout.amountMinor()))
                throw new IllegalStateException("company payout reserve is missing");
            if (!exits.transitionAction(connection, action.id(), GovernanceActionState.EXECUTING, GovernanceActionState.CANCELLED, now))
                throw new IllegalStateException("capital action state changed before cancellation");
            return null;
        });
        return ExecutionResult.FAILED;
    }

    private ExecutionResult ambiguous(CompanyPayoutOperation payout, String detail) {
        markAmbiguousIfPrepared(payout, detail);
        return ExecutionResult.AMBIGUOUS;
    }

    private void markAmbiguousIfPrepared(CompanyPayoutOperation payout, String detail) {
        try { transactions.inTransaction(connection -> { exits.transitionPayout(connection, payout.id(), PayoutOperationState.PREPARED, PayoutOperationState.AMBIGUOUS, detail, clock.now()); return null; }); }
        catch (RuntimeException ignored) { /* A database outage cannot justify replaying the external payment. */ }
    }

    private Company requireFounderOfListedCompany(UUID founder, CompanyId companyId) {
        Company company = requireListedCompany(companyId);
        if (!company.founderId().equals(founder)) throw new IllegalArgumentException("only the company founder may announce or execute capital actions");
        return company;
    }

    private void requireListedCompanyFounder(UUID founder, CompanyId companyId) { requireFounderOfListedCompany(founder, companyId); }

    private Company requireListedCompany(CompanyId companyId) {
        Company company = companies.findById(companyId).orElseThrow(() -> new IllegalArgumentException("company does not exist"));
        if (company.status() != CompanyStatus.LISTED) throw new IllegalStateException("company must be listed");
        return company;
    }

    private static void requirePositive(Money value, String field) {
        Objects.requireNonNull(value, field); if (value.minorUnits() <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    public enum ExecutionResult { EXECUTING, COMPLETED, FAILED, AMBIGUOUS }
}
