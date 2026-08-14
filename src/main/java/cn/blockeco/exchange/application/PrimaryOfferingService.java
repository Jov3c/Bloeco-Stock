package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.PrimaryOffering;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

/** Primary subscriptions use a durable expected-state treasury operation before each external Vault call. */
public class PrimaryOfferingService {
 private final PrimaryOfferingRepository offerings; private final TransactionRunner transactions; private final TreasuryEscrowGateway escrow; private final Executor executor; private final AppClock clock;
 public PrimaryOfferingService(PrimaryOfferingRepository offerings,TransactionRunner transactions,TreasuryEscrowGateway escrow,Executor executor,AppClock clock){this.offerings=offerings;this.transactions=transactions;this.escrow=escrow;this.executor=executor;this.clock=clock;}
 public CompletionStage<PrimaryOffering> announce(CompanyId company,UUID founder,Money target,Money price){return CompletableFuture.supplyAsync(()->{if(!offerings.isFounder(company,founder))throw new IllegalArgumentException("only the founder may announce an offering");if(!offerings.hasActiveAsset(company))throw new IllegalStateException("an active asset binding is required"); long capital=offerings.paidInCapital(company); if(target.minorUnits()>Math.multiplyExact(capital,5))throw new IllegalArgumentException("target exceeds five times paid-in capital"); PrimaryOffering offer=PrimaryOffering.plan(company,target,price,clock.now()); transactions.inTransaction(c->{offerings.announce(c,offer);return null;});return offer;},executor);}
 public CompletionStage<Void> subscribe(UUID subscriber,UUID offeringId,long shares){return CompletableFuture.supplyAsync(()->{PrimaryOffering offering=offerings.find(offeringId).orElseThrow(()->new IllegalArgumentException("offering not found")); UUID id=UUID.nameUUIDFromBytes((offeringId+":"+subscriber+":"+shares).getBytes(StandardCharsets.UTF_8)); return transactions.inTransaction(c->offerings.prepareSubscription(c,id,offering,subscriber,shares,clock.now()));},executor).thenCompose(prepared->{TreasuryOperation op=prepared.operation();if(op.state().name().equals("COMPLETED"))return CompletableFuture.completedFuture(null);if(!prepared.newlyPrepared())return ambiguous(op,op.state().name()); return CompletableFuture.supplyAsync(()->escrow.withdrawPlayer(subscriber,op.amount(),op.id()),executor).thenCompose(result->{if(result.outcome()!=EconomyGateway.Outcome.SUCCESS)return ambiguous(op,"PREPARED"); return CompletableFuture.supplyAsync(()->{transactions.inTransaction(c->{offerings.markWithdrawn(c,op.id(),clock.now());return null;});return escrow.depositEscrow(op.amount(),op.id());},executor).thenCompose(deposit->{if(deposit.outcome()!=EconomyGateway.Outcome.SUCCESS)return ambiguous(op,"PLAYER_WITHDRAWN");return CompletableFuture.runAsync(()->transactions.inTransaction(c->{offerings.markEscrowDeposited(c,op.id(),clock.now());offerings.completeSubscription(c,op.id(),clock.now());return null;}),executor).exceptionallyCompose(e->ambiguous(op,"ESCROW_DEPOSITED"));});});});}
 private CompletionStage<Void> ambiguous(TreasuryOperation op,String expected){return CompletableFuture.runAsync(()->transactions.inTransaction(c->{offerings.markAmbiguous(c,op.id(),expected,clock.now());return null;}),executor).thenCompose(v->CompletableFuture.failedFuture(new IllegalStateException("subscription requires recovery")));}
 public CompletionStage<Void> closeExpired(Instant now){return CompletableFuture.runAsync(()->transactions.inTransaction(c->{offerings.closeExpired(c,now);return null;}),executor);}
}
