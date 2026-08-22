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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Intent-first, deliberately non-retrying two-leg personal cash saga. */
public final class SecuritiesCashService {
    private final SecuritiesCashRepository repository; private final TransactionRunner transactions;
    private final SecuritiesCashGateway gateway; private final Executor sql; private final AppClock clock;
    public SecuritiesCashService(SecuritiesCashRepository repository, TransactionRunner transactions, SecuritiesCashGateway gateway, Executor sql, AppClock clock) {
        this.repository=Objects.requireNonNull(repository); this.transactions=Objects.requireNonNull(transactions); this.gateway=Objects.requireNonNull(gateway); this.sql=Objects.requireNonNull(sql); this.clock=Objects.requireNonNull(clock);
    }
    public CompletionStage<SecuritiesCashResult> deposit(UUID player, Money amount) { return start(player,amount,SecuritiesCashDirection.DEPOSIT); }
    public CompletionStage<SecuritiesCashResult> withdraw(UUID player, Money amount) { return start(player,amount,SecuritiesCashDirection.WITHDRAW); }
    private CompletionStage<SecuritiesCashResult> start(UUID player,Money amount,SecuritiesCashDirection direction) {
        Objects.requireNonNull(player); requireAmount(amount); UUID id=UUID.randomUUID();
        return CompletableFuture.supplyAsync(() -> prepare(id,player,amount,direction),sql).thenCompose(operation -> {
            if(operation.state()!=SecuritiesCashOperationState.PREPARED) return CompletableFuture.completedFuture(result(operation));
            return direction==SecuritiesCashDirection.DEPOSIT ? depositLegOne(operation) : withdrawLegOne(operation);
        });
    }
    private SecuritiesCashOperation prepare(UUID id,UUID player,Money amount,SecuritiesCashDirection direction) {
        if(repository.findActiveOperation(player).isPresent()) throw new IllegalStateException("player already has a nonterminal cash operation");
        Instant now=clock.now(); SecuritiesCashOperation operation=new SecuritiesCashOperation(id,player,amount,direction,SecuritiesCashOperationState.PREPARED,null,"prepared",now,now);
        transactions.inTransaction(c->{ if(direction==SecuritiesCashDirection.WITHDRAW) repository.reserve(c,player,amount); repository.prepareOperation(c,operation); return null; }); return operation;
    }
    private CompletionStage<SecuritiesCashResult> depositLegOne(SecuritiesCashOperation o) {
        return gateway.withdrawPlayer(o.playerId(),o.amount()).handle((r,t)->new External(r,t)).thenCompose(x -> {
            if(!success(x)) return afterFailure(o,SecuritiesCashOperationState.PREPARED,null,x,false);
            return durable(o,SecuritiesCashOperationState.PREPARED,SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.PLAYER_WITHDRAWN,"player withdrawal confirmed")
                .thenCompose(v -> gateway.depositEscrow(o.amount()).handle((r,t)->new External(r,t)))
                .thenCompose(y -> { if(!success(y)) return afterFailure(o,SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.PLAYER_WITHDRAWN,y,true);
                    return durable(o,SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.ESCROW_DEPOSITED,SecuritiesCashOperationState.ESCROW_DEPOSITED,"escrow deposit confirmed")
                        .thenCompose(v -> sql(() -> { transactions.inTransaction(c->{repository.completeDeposit(c,require(o.id()),clock.now());return null;}); return new SecuritiesCashResult(o.id(),SecuritiesCashOperationState.COMPLETED,"completed"); })); });
        });
    }
    private CompletionStage<SecuritiesCashResult> withdrawLegOne(SecuritiesCashOperation o) {
        return gateway.withdrawEscrow(o.amount()).handle((r,t)->new External(r,t)).thenCompose(x -> {
            if(!success(x)) return afterFailure(o,SecuritiesCashOperationState.PREPARED,null,x,false);
            return durable(o,SecuritiesCashOperationState.PREPARED,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.ESCROW_WITHDRAWN,"escrow withdrawal confirmed")
                .thenCompose(v -> gateway.depositPlayer(o.playerId(),o.amount()).handle((r,t)->new External(r,t)))
                .thenCompose(y -> { if(!success(y)) return afterFailure(o,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.ESCROW_WITHDRAWN,y,true);
                    return durable(o,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.PLAYER_DEPOSITED,SecuritiesCashOperationState.PLAYER_DEPOSITED,"player deposit confirmed")
                        .thenCompose(v -> sql(() -> { transactions.inTransaction(c->{repository.completeWithdrawal(c,require(o.id()),clock.now());return null;});return new SecuritiesCashResult(o.id(),SecuritiesCashOperationState.COMPLETED,"completed"); })); });
        });
    }
    private CompletionStage<SecuritiesCashResult> afterFailure(SecuritiesCashOperation o,SecuritiesCashOperationState expected,SecuritiesCashOperationState last,External external,boolean priorExternalEffect) {
        boolean called=external.result!=null && external.result.providerWasCalled();
        if(external.failure!=null) called=true; // a thrown completion has crossed invocation boundary
        boolean ambiguous=called || priorExternalEffect;
        String detail=external.detail();
        return sql(() -> { transactions.inTransaction(c->{ if(ambiguous) repository.transitionOperation(c,o.id(),expected,SecuritiesCashOperationState.AMBIGUOUS,last,detail,clock.now()); else { if(o.direction()==SecuritiesCashDirection.WITHDRAW) repository.release(c,o.playerId(),o.amount()); repository.transitionOperation(c,o.id(),expected,SecuritiesCashOperationState.FAILED,null,detail,clock.now()); } return null;}); return new SecuritiesCashResult(o.id(),ambiguous?SecuritiesCashOperationState.AMBIGUOUS:SecuritiesCashOperationState.FAILED,detail); });
    }
    /** Startup is local-only: final externally durable states can safely finish their internal leg. */
    public CompletionStage<List<SecuritiesCashRecoveryRecord>> recoverDurableFinalStages() { return sql(() -> {
        List<SecuritiesCashRecoveryRecord> out=new java.util.ArrayList<>(); for(SecuritiesCashOperation o:repository.findRecoveryCandidates()) { if(o.direction()==SecuritiesCashDirection.DEPOSIT&&o.state()==SecuritiesCashOperationState.ESCROW_DEPOSITED) transactions.inTransaction(c->{repository.completeDeposit(c,o,clock.now());return null;}); else if(o.direction()==SecuritiesCashDirection.WITHDRAW&&o.state()==SecuritiesCashOperationState.PLAYER_DEPOSITED) transactions.inTransaction(c->{repository.completeWithdrawal(c,o,clock.now());return null;}); SecuritiesCashOperation current=repository.findOperation(o.id()).orElse(o); out.add(new SecuritiesCashRecoveryRecord(current.id(),current.playerId(),current.amount(),current.direction(),current.state(),current.lastConfirmedExternalStage(),current.detail())); } return List.copyOf(out); }); }
    private CompletionStage<Void> durable(SecuritiesCashOperation o,SecuritiesCashOperationState expected,SecuritiesCashOperationState state,SecuritiesCashOperationState stage,String detail){return sql(() -> {transactions.inTransaction(c->{repository.transitionOperation(c,o.id(),expected,state,stage,detail,clock.now());return null;});return null;}).exceptionallyCompose(failure -> sql(() -> {try {transactions.inTransaction(c->{repository.transitionOperation(c,o.id(),expected,SecuritiesCashOperationState.AMBIGUOUS,null,"durable stage persistence failed: "+failure,clock.now());return null;});} catch (RuntimeException ignored) { /* DB may be unavailable; never issue another external leg. */ } return null;}).thenCompose(v -> CompletableFuture.failedFuture(failure)));}
    private SecuritiesCashOperation require(UUID id){return repository.findOperation(id).orElseThrow(()->new IllegalStateException("cash operation disappeared: "+id));}
    private <T> CompletionStage<T> sql(java.util.concurrent.Callable<T> work){return CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}},sql);}
    private static boolean success(External external){return external.failure==null&&external.result!=null&&external.result.outcome()== EconomyGateway.Outcome.SUCCESS;}
    private static SecuritiesCashResult result(SecuritiesCashOperation o){return new SecuritiesCashResult(o.id(),o.state(),o.detail());}
    private static void requireAmount(Money amount){Objects.requireNonNull(amount);if(amount.minorUnits()<=0)throw new IllegalArgumentException("amount must be positive");}
    private record External(EconomyGateway.Result result,Throwable failure){String detail(){return failure!=null?failure.toString():result==null?"provider returned null":result.message();}}
}
