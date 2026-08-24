package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Funds the finite system market-maker account once. Each provider call has a durable
 * requested state before it is issued; startup never repeats an uncertain call.
 */
public final class BluechipBootstrapFundingService {
    private final UUID system; private final BluechipBootstrapFundingRepository repository; private final TransactionRunner transactions;
    private final EscrowEconomy economy; private final MainThreadExecutor main; private final AppClock clock;
    public BluechipBootstrapFundingService(UUID system, BluechipBootstrapFundingRepository repository, TransactionRunner transactions, EscrowEconomy economy, MainThreadExecutor main, AppClock clock) {
        this.system=Objects.requireNonNull(system);this.repository=Objects.requireNonNull(repository);this.transactions=Objects.requireNonNull(transactions);this.economy=Objects.requireNonNull(economy);this.main=Objects.requireNonNull(main);this.clock=Objects.requireNonNull(clock);
    }
    public BluechipBootstrapFundingRepository.Funding ensureEscrowFunded(Money amount) {
        UUID id=UUID.nameUUIDFromBytes(("blockstock-bluechip-bootstrap-funding:"+system+":"+amount.minorUnits()).getBytes(StandardCharsets.UTF_8));
        var funding=repository.find(id).orElseGet(()->prepare(id,amount));
        if (!funding.systemAccountId().equals(system)||!funding.amount().equals(amount)) throw new IllegalStateException("bluechip bootstrap funding metadata mismatch");
        while (true) {
            funding=repository.find(id).orElseThrow();
            switch (funding.state()) {
                case COMPLETED, ESCROW_DEPOSITED -> { return funding; }
                case PREPARED -> issue(funding, BluechipBootstrapFundingRepository.State.SOURCE_CREDIT_REQUESTED, BluechipBootstrapFundingRepository.State.SOURCE_CREDITED, "source credit", () -> economy.deposit(system, amount));
                case SOURCE_CREDITED -> issue(funding, BluechipBootstrapFundingRepository.State.SOURCE_DEBIT_REQUESTED, BluechipBootstrapFundingRepository.State.SOURCE_DEBITED, "source debit", () -> economy.withdraw(system, amount));
                case SOURCE_DEBITED -> issue(funding, BluechipBootstrapFundingRepository.State.ESCROW_DEPOSIT_REQUESTED, BluechipBootstrapFundingRepository.State.ESCROW_DEPOSITED, "escrow deposit", () -> economy.depositEscrow(amount));
                case SOURCE_CREDIT_REQUESTED, SOURCE_DEBIT_REQUESTED, ESCROW_DEPOSIT_REQUESTED -> throw new IllegalStateException("bluechip bootstrap funding requires manual recovery: request may have reached provider: "+funding.detail());
                case AMBIGUOUS -> throw new IllegalStateException("bluechip bootstrap funding requires manual recovery: "+funding.detail());
            }
        }
    }
    /** Kept out of EconomyGateway: the escrow account is supplied by the small adapter below. */
    public interface EscrowEconomy extends EconomyGateway { EconomyGateway.Result depositEscrow(Money amount); }
    private BluechipBootstrapFundingRepository.Funding prepare(UUID id, Money amount) { Instant now=clock.now(); var f=new BluechipBootstrapFundingRepository.Funding(id,system,amount,BluechipBootstrapFundingRepository.State.PREPARED,"prepared",now,now); transactions.inTransaction(c->{repository.prepare(c,f);return null;});return f; }
    private void issue(BluechipBootstrapFundingRepository.Funding f, BluechipBootstrapFundingRepository.State requested, BluechipBootstrapFundingRepository.State confirmed, String label, java.util.function.Supplier<EconomyGateway.Result> call) {
        request(f, requested, label + " requested");
        confirm(repository.find(f.id()).orElseThrow(), requested, confirmed, label, call);
    }
    private void request(BluechipBootstrapFundingRepository.Funding f, BluechipBootstrapFundingRepository.State next, String detail) { transactions.inTransaction(c->{repository.transition(c,f.id(),f.state(),next,detail,clock.now());return null;}); }
    private void confirm(BluechipBootstrapFundingRepository.Funding f, BluechipBootstrapFundingRepository.State expected, BluechipBootstrapFundingRepository.State next, String label, java.util.function.Supplier<EconomyGateway.Result> call) {
        EconomyGateway.Result result;
        try { result=main.submit(call).toCompletableFuture().join(); } catch (RuntimeException failure) { ambiguous(f, expected, label+" invocation failed: "+failure); throw new IllegalStateException("bluechip bootstrap funding requires manual recovery",failure); }
        if (result == null || result.outcome()!=EconomyGateway.Outcome.SUCCESS) { ambiguous(f,expected,label+" outcome: "+(result==null?"null":result.message())); throw new IllegalStateException("bluechip bootstrap funding requires manual recovery: "+label); }
        try { transactions.inTransaction(c->{repository.transition(c,f.id(),expected,next,"confirmed "+label,clock.now());return null;}); }
        catch(RuntimeException failure) { ambiguous(f,expected,"confirmed "+label+" could not be persisted: "+failure); throw new IllegalStateException("bluechip bootstrap funding requires manual recovery",failure); }
    }
    private void ambiguous(BluechipBootstrapFundingRepository.Funding f, BluechipBootstrapFundingRepository.State expected, String detail) { try { transactions.inTransaction(c->{repository.transition(c,f.id(),expected,BluechipBootstrapFundingRepository.State.AMBIGUOUS,detail,clock.now());return null;}); } catch(RuntimeException ignored) { } }
}
