package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.SecuritiesCashDirection;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.SecuritiesCashGateway;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Intent-first, deliberately non-retrying two-leg personal cash saga. */
public final class SecuritiesCashService {
    private final SecuritiesCashRepository repository; private final TransactionRunner transactions;
    private final SecuritiesCashGateway gateway; private final Executor sql; private final AppClock clock; private final Duration externalTimeout; private final java.util.function.BooleanSupplier accepting;
    public SecuritiesCashService(SecuritiesCashRepository repository, TransactionRunner transactions, SecuritiesCashGateway gateway, Executor sql, AppClock clock) {
        this(repository,transactions,gateway,sql,clock,Duration.ofSeconds(15),()->true);
    }
    public SecuritiesCashService(SecuritiesCashRepository repository, TransactionRunner transactions, SecuritiesCashGateway gateway, Executor sql, AppClock clock, Duration externalTimeout) { this(repository,transactions,gateway,sql,clock,externalTimeout,()->true); }
    public SecuritiesCashService(SecuritiesCashRepository repository, TransactionRunner transactions, SecuritiesCashGateway gateway, Executor sql, AppClock clock, Duration externalTimeout, java.util.function.BooleanSupplier accepting) { this.repository=Objects.requireNonNull(repository); this.transactions=Objects.requireNonNull(transactions); this.gateway=Objects.requireNonNull(gateway); this.sql=Objects.requireNonNull(sql); this.clock=Objects.requireNonNull(clock); this.externalTimeout=Objects.requireNonNull(externalTimeout); this.accepting=Objects.requireNonNull(accepting); if(externalTimeout.isNegative()||externalTimeout.isZero())throw new IllegalArgumentException("external timeout must be positive"); }
    public CompletionStage<SecuritiesCashResult> deposit(UUID player, Money amount) { return start(player,amount,SecuritiesCashDirection.DEPOSIT); }
    public CompletionStage<SecuritiesCashResult> withdraw(UUID player, Money amount) { return start(player,amount,SecuritiesCashDirection.WITHDRAW); }
    private CompletionStage<SecuritiesCashResult> start(UUID player,Money amount,SecuritiesCashDirection direction) {
        Objects.requireNonNull(player); requireAmount(amount); UUID id=UUID.randomUUID();
        if(!accepting.getAsBoolean()) return CompletableFuture.failedFuture(new IllegalStateException("cash operations are not accepting"));
        return CompletableFuture.supplyAsync(() -> prepare(id,player,amount,direction),sql).thenCompose(operation -> {
            if(operation.state()!=SecuritiesCashOperationState.PREPARED) return CompletableFuture.completedFuture(result(operation));
            if(!accepting.getAsBoolean()) return afterFailure(operation,SecuritiesCashOperationState.PREPARED,null,new External(EconomyGateway.Result.notCalledFailure("runtime stopped after prepare"),null,false),false);
            return direction==SecuritiesCashDirection.DEPOSIT ? depositLegOne(operation) : withdrawLegOne(operation);
        });
    }
    private SecuritiesCashOperation prepare(UUID id,UUID player,Money amount,SecuritiesCashDirection direction) {
        if(repository.findActiveOperation(player).isPresent()) throw new IllegalStateException("player already has a nonterminal cash operation");
        Instant now=clock.now(); SecuritiesCashOperation operation=new SecuritiesCashOperation(id,player,amount,direction,SecuritiesCashOperationState.PREPARED,null,"prepared",now,now);
        transactions.inTransaction(c->{ if(direction==SecuritiesCashDirection.WITHDRAW) repository.reserve(c,player,amount); repository.prepareOperation(c,operation); return null; }); return operation;
    }
    private CompletionStage<SecuritiesCashResult> depositLegOne(SecuritiesCashOperation o) {
        return external(() -> gateway.withdrawPlayer(o.playerId(),o.amount(),accepting)).thenCompose(x -> {
            if(!success(x)) return afterFailure(o,SecuritiesCashOperationState.PREPARED,null,x,false);
            return durable(o,SecuritiesCashOperationState.PREPARED,SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.PLAYER_WITHDRAWN,"player withdrawal confirmed")
                .thenCompose(v -> external(() -> gateway.depositEscrow(o.amount(),accepting)))
                .thenCompose(y -> { if(!success(y)) return afterFailure(o,SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.PLAYER_WITHDRAWN,y,true);
                    return durable(o,SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.ESCROW_DEPOSITED,SecuritiesCashOperationState.ESCROW_DEPOSITED,"escrow deposit confirmed")
                        .thenCompose(v -> sql(() -> { SecuritiesCashOperation latest=require(o.id()); transactions.inTransaction(c->{repository.completeDeposit(c,latest,clock.now());return null;}); return new SecuritiesCashResult(o.id(),SecuritiesCashOperationState.COMPLETED,"completed"); })); });
        });
    }
    private CompletionStage<SecuritiesCashResult> withdrawLegOne(SecuritiesCashOperation o) {
        return external(() -> gateway.withdrawEscrow(o.amount(),accepting)).thenCompose(x -> {
            if(!success(x)) return afterFailure(o,SecuritiesCashOperationState.PREPARED,null,x,false);
            return durable(o,SecuritiesCashOperationState.PREPARED,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.ESCROW_WITHDRAWN,"escrow withdrawal confirmed")
                .thenCompose(v -> external(() -> gateway.depositPlayer(o.playerId(),o.amount(),accepting)))
                .thenCompose(y -> { if(!success(y)) return afterFailure(o,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.ESCROW_WITHDRAWN,y,true);
                    return durable(o,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.PLAYER_DEPOSITED,SecuritiesCashOperationState.PLAYER_DEPOSITED,"player deposit confirmed")
                        .thenCompose(v -> sql(() -> { SecuritiesCashOperation latest=require(o.id()); transactions.inTransaction(c->{repository.completeWithdrawal(c,latest,clock.now());return null;});return new SecuritiesCashResult(o.id(),SecuritiesCashOperationState.COMPLETED,"completed"); })); });
        });
    }
    private CompletionStage<SecuritiesCashResult> afterFailure(SecuritiesCashOperation o,SecuritiesCashOperationState expected,SecuritiesCashOperationState last,External external,boolean priorExternalEffect) {
        // A gateway result can prove the provider was *not* called.  Every other
        // path (null result/stage, throw, timeout, or providerWasCalled=true) has
        // crossed an invocation boundary and is deliberately unretryable.
        boolean called=external.invocationAttempted && (external.result==null || external.result.providerWasCalled());
        boolean ambiguous=called || priorExternalEffect;
        String detail=external.detail();
        return sql(() -> { transactions.inTransaction(c->{ if(ambiguous) repository.transitionOperation(c,o.id(),expected,SecuritiesCashOperationState.AMBIGUOUS,last,detail,clock.now()); else { if(o.direction()==SecuritiesCashDirection.WITHDRAW) repository.release(c,o.playerId(),o.amount()); repository.transitionOperation(c,o.id(),expected,SecuritiesCashOperationState.FAILED,null,detail,clock.now()); } return null;}); return new SecuritiesCashResult(o.id(),ambiguous?SecuritiesCashOperationState.AMBIGUOUS:SecuritiesCashOperationState.FAILED,detail); });
    }
    /** Startup is local-only: final externally durable states can safely finish their internal leg. */
    public CompletionStage<List<SecuritiesCashRecoveryRecord>> recoverDurableFinalStages() { return sql(() -> {
        List<SecuritiesCashRecoveryRecord> out=new java.util.ArrayList<>(); for(SecuritiesCashOperation o:repository.findRecoveryCandidates()) { if(o.direction()==SecuritiesCashDirection.DEPOSIT&&o.state()==SecuritiesCashOperationState.ESCROW_DEPOSITED) transactions.inTransaction(c->{repository.completeDeposit(c,o,clock.now());return null;}); else if(o.direction()==SecuritiesCashDirection.WITHDRAW&&o.state()==SecuritiesCashOperationState.PLAYER_DEPOSITED) transactions.inTransaction(c->{repository.completeWithdrawal(c,o,clock.now());return null;}); SecuritiesCashOperation current=repository.findOperation(o.id()).orElse(o); out.add(new SecuritiesCashRecoveryRecord(current.id(),current.playerId(),current.amount(),current.direction(),current.state(),current.lastConfirmedExternalStage(),current.detail())); } return List.copyOf(out); }); }
    private CompletionStage<Void> durable(SecuritiesCashOperation o,SecuritiesCashOperationState expected,SecuritiesCashOperationState state,SecuritiesCashOperationState stage,String detail){return this.<Void>sql(() -> {transactions.inTransaction(c->{repository.transitionOperation(c,o.id(),expected,state,stage,detail,clock.now());return null;});return null;}).exceptionallyCompose(failure -> this.<Void>sql(() -> {try {transactions.inTransaction(c->{repository.transitionOperation(c,o.id(),expected,SecuritiesCashOperationState.AMBIGUOUS,stage,"durable stage persistence failed after confirmed "+stage+": "+failure,clock.now());return null;});} catch (RuntimeException ignored) { /* DB may be unavailable; never issue another external leg. */ } return null;}).thenCompose(v -> CompletableFuture.<Void>failedFuture(failure)));}
    private SecuritiesCashOperation require(UUID id){return repository.findOperation(id).orElseThrow(()->new IllegalStateException("cash operation disappeared: "+id));}
    private <T> CompletionStage<T> sql(java.util.concurrent.Callable<T> work){return CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}},sql);}
    private static boolean success(External external){return external.failure==null&&external.result!=null&&external.result.outcome()== EconomyGateway.Outcome.SUCCESS;}
    /**
     * The gateway boundary is deliberately total: an adapter may throw before it
     * returns a stage, or (incorrectly) return null.  Both cases happen after this
     * service has attempted to invoke the external leg, so must be ambiguous rather
     * than escaping the saga and leaving a PREPARED intent orphaned.
     */
    private CompletionStage<External> external(java.util.function.Supplier<CompletionStage<EconomyGateway.Result>> invoke){
        try {
            CompletionStage<EconomyGateway.Result> stage=invoke.get();
            if(stage==null)return CompletableFuture.completedFuture(new External(null,new IllegalStateException("gateway returned null stage"),true));
            return stage.toCompletableFuture().orTimeout(externalTimeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS).handle((result,failure)->new External(result,failure,true));
        } catch(RuntimeException failure) {
            return CompletableFuture.completedFuture(new External(null,failure,true));
        }
    }
    private static SecuritiesCashResult result(SecuritiesCashOperation o){return new SecuritiesCashResult(o.id(),o.state(),o.detail());}
    private static void requireAmount(Money amount){Objects.requireNonNull(amount);if(amount.minorUnits()<=0)throw new IllegalArgumentException("amount must be positive");}
    private record External(EconomyGateway.Result result,Throwable failure,boolean invocationAttempted){String detail(){return failure!=null?failure.toString():result==null?"provider returned null":result.message();}}
}
