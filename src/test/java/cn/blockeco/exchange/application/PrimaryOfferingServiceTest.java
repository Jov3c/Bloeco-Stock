package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlPrimaryOfferingRepository;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.TreasuryEscrowGateway;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.Optional;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import cn.blockeco.exchange.domain.finance.*;
import cn.blockeco.exchange.ports.PrimaryOfferingRepository;
import org.junit.jupiter.api.Test;

class PrimaryOfferingServiceTest {
 @Test void first_subscription_opens_announced_offering_in_its_transaction_before_money_moves() throws Exception {
  Path file=Files.createTempFile("ipo-sub-open-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate(); CompanyId c=Fixtures.company(db,100); UUID founder=Fixtures.founder(db,c); Fixtures.activeAsset(db,c,founder); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,new Escrow(),Runnable::run,clock); var offer=service.announce(c,founder,Money.ofMinor(100),Money.ofMinor(10)).toCompletableFuture().join(); clock.now=offer.opensAt(); service.subscribe(UUID.randomUUID(),offer.id(),1).toCompletableFuture().join(); assertThat(repo.find(offer.id()).orElseThrow().state()).isEqualTo(cn.blockeco.exchange.domain.finance.PrimaryOfferingState.OPEN); assertThat(auditCount(db,offer.id(),"OPEN")).isEqualTo(1); }finally{Files.deleteIfExists(file);} }
 @Test void never_replays_a_persisted_prepared_subscription_after_a_crash_window() throws Exception {
  Path file=Files.createTempFile("ipo-recovery-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId c=Fixtures.company(db,100); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); UUID founder=Fixtures.founder(db,c);
   Fixtures.activeAsset(db,c,founder); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,new Escrow(),Runnable::run,clock);
   var offer=service.announce(c,founder,Money.ofMinor(100),Money.ofMinor(10)).toCompletableFuture().join(); clock.now=offer.opensAt(); UUID subscriber=UUID.randomUUID(); UUID subscription=UUID.nameUUIDFromBytes((offer.id()+":"+subscriber+":2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
   db.inTransaction(x->{repo.prepareSubscription(x,subscription,offer,subscriber,2,clock.now);return null;});
   assertThat(service.subscribe(subscriber,offer.id(),2).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.RECOVERY_REQUIRED);
  } finally {Files.deleteIfExists(file);} }
 @Test void requires_active_asset_caps_target_and_honors_exact_open_close_boundaries() throws Exception {
  Path file=Files.createTempFile("ipo-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId c=Fixtures.company(db,100_000); UUID founder=Fixtures.founder(db,c); MutableClock clock=new MutableClock();
   PrimaryOfferingService service=new PrimaryOfferingService(new SqlPrimaryOfferingRepository(db.dataSource()), db, new Escrow(), Runnable::run, clock);
   assertThatThrownBy(()->service.announce(c,founder,Money.ofMinor(1),Money.ofMinor(1)).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
   Fixtures.activeAsset(db,c,founder);
   assertThatThrownBy(()->service.announce(c,founder,Money.ofMinor(500_001),Money.ofMinor(1)).toCompletableFuture().join()).hasCauseInstanceOf(IllegalArgumentException.class);
   var offer=service.announce(c,founder,Money.ofMinor(500_000),Money.ofMinor(100)).toCompletableFuture().join(); UUID buyer=UUID.randomUUID();
   assertThat(service.subscribe(buyer,offer.id(),1).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.NOT_OPEN);
   clock.now=offer.opensAt(); service.subscribe(buyer,offer.id(),2).toCompletableFuture().join();
   clock.now=offer.closesAt(); assertThat(service.subscribe(UUID.randomUUID(),offer.id(),1).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.NOT_OPEN);
  } finally {Files.deleteIfExists(file);} }
 @Test void subscription_does_not_list_company_until_the_offering_closes() throws Exception {
  Path file=Files.createTempFile("ipo-list-on-close-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId c=Fixtures.company(db,100); UUID founder=Fixtures.founder(db,c); Fixtures.activeAsset(db,c,founder); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,new Escrow(),Runnable::run,clock);
   var offer=service.announce(c,founder,Money.ofMinor(100),Money.ofMinor(10)).toCompletableFuture().join(); clock.now=offer.opensAt(); service.subscribe(UUID.randomUUID(),offer.id(),1).toCompletableFuture().join();
   assertThat(companyStatus(db,c)).isEqualTo("PENDING_ASSET_BINDING");
   clock.now=offer.closesAt(); service.closeExpired(clock.now).toCompletableFuture().join();
   assertThat(companyStatus(db,c)).isEqualTo("LISTED");
  } finally {Files.deleteIfExists(file);} }
 @Test void escrowed_subscription_completed_after_expiry_lists_once_even_when_scheduler_saw_zero_completed_shares() throws Exception {
  Path file=Files.createTempFile("ipo-expiry-completion-race-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId c=Fixtures.company(db,100); UUID founder=Fixtures.founder(db,c); Fixtures.activeAsset(db,c,founder); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,new Escrow(),Runnable::run,clock);
   var offer=service.announce(c,founder,Money.ofMinor(100),Money.ofMinor(10)).toCompletableFuture().join(); UUID subscriber=UUID.randomUUID(); UUID subscription=UUID.nameUUIDFromBytes((offer.id()+":"+subscriber+":1").getBytes(java.nio.charset.StandardCharsets.UTF_8)); clock.now=offer.opensAt(); db.inTransaction(x->{repo.prepareSubscription(x,subscription,offer,subscriber,1,clock.now);repo.markWithdrawn(x,subscription,clock.now);repo.markEscrowDeposited(x,subscription,clock.now);return null;});
   clock.now=offer.closesAt(); service.closeExpired(clock.now).toCompletableFuture().join(); assertThat(companyStatus(db,c)).isEqualTo("PENDING_ASSET_BINDING"); assertThat(ipoListedAuditCount(db,offer.id())).isZero();
   db.inTransaction(x->{repo.completeSubscription(x,subscription,clock.now);repo.completeSubscription(x,subscription,clock.now);return null;}); service.closeExpired(clock.now).toCompletableFuture().join();
   assertThat(companyStatus(db,c)).isEqualTo("LISTED"); assertThat(ipoListedAuditCount(db,offer.id())).isEqualTo(1);
  } finally {Files.deleteIfExists(file);} }
 @Test void sold_out_is_a_typed_result_and_does_not_call_vault_for_the_rejected_buyer() throws Exception {
  Path file=Files.createTempFile("ipo-sold-out-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId c=Fixtures.company(db,100); UUID founder=Fixtures.founder(db,c); Fixtures.activeAsset(db,c,founder); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); CountingEscrow escrow=new CountingEscrow(); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,escrow,Runnable::run,clock);
   var offer=service.announce(c,founder,Money.ofMinor(10),Money.ofMinor(10)).toCompletableFuture().join(); clock.now=offer.opensAt();
   assertThat(service.subscribe(UUID.randomUUID(),offer.id(),1).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.SUCCESS);
   assertThat(service.subscribe(UUID.randomUUID(),offer.id(),1).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.SOLD_OUT);
   assertThat(escrow.withdrawCalls).isEqualTo(1);
  } finally {Files.deleteIfExists(file);} }
 @Test void unknown_prepare_failure_is_provider_failure_not_not_open_and_never_calls_vault() {
  CountingEscrow escrow=new CountingEscrow(); PrimaryOfferingRepository repo=new FailingPrepareRepository(new IllegalStateException("database down"));
  PrimaryOfferingService service=new PrimaryOfferingService(repo,new DirectTransactions(),escrow,Runnable::run,()->Instant.parse("2026-08-14T12:00:00Z"));
  assertThat(service.subscribe(UUID.randomUUID(),UUID.randomUUID(),1).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.PROVIDER_FAILURE);
  assertThat(escrow.withdrawCalls).isZero();
 }
 @Test void persisted_withdrawn_or_escrow_deposited_subscription_never_replays_vault() throws Exception {
  Path file=Files.createTempFile("ipo-no-replay-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate(); CompanyId c=Fixtures.company(db,100); UUID founder=Fixtures.founder(db,c); Fixtures.activeAsset(db,c,founder); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); CountingEscrow escrow=new CountingEscrow(); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,escrow,Runnable::run,clock); var offer=service.announce(c,founder,Money.ofMinor(100),Money.ofMinor(10)).toCompletableFuture().join(); clock.now=offer.opensAt(); UUID buyer=UUID.randomUUID(); UUID id=UUID.nameUUIDFromBytes((offer.id()+":"+buyer+":1").getBytes(java.nio.charset.StandardCharsets.UTF_8)); db.inTransaction(tx->{repo.prepareSubscription(tx,id,offer,buyer,1,clock.now);repo.markWithdrawn(tx,id,clock.now);return null;});
   assertThat(service.subscribe(buyer,offer.id(),1).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.RECOVERY_REQUIRED); assertThat(escrow.withdrawCalls).isZero();
  }finally{Files.deleteIfExists(file);} }
 @Test void post_vault_failures_return_recovery_and_record_ambiguous_without_replaying_a_second_vault_call() {
  UUID subscriber=UUID.randomUUID(),offering=UUID.randomUUID(); ScenarioRepository repo=new ScenarioRepository(); ThrowingEscrow escrow=new ThrowingEscrow(); repo.failWithdrawn=true;
  PrimaryOfferingService service=new PrimaryOfferingService(repo,new DirectTransactions(),escrow,Runnable::run,()->Instant.parse("2026-08-14T12:00:00Z"));
  assertThat(service.subscribe(subscriber,offering,1).toCompletableFuture().join().status()).isEqualTo(SubscriptionResult.Status.RECOVERY_REQUIRED); assertThat(repo.ambiguous).isTrue(); assertThat(escrow.withdrawCalls).isEqualTo(1); assertThat(escrow.depositCalls).isZero();
 }
 private static String companyStatus(Database db,CompanyId company){try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT status FROM companies WHERE id=?")){s.setString(1,company.value().toString());try(var r=s.executeQuery()){r.next();return r.getString(1);}}catch(Exception e){throw new RuntimeException(e);}}
 private static long ipoListedAuditCount(Database db,UUID offering){try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT COUNT(*) FROM audit_events WHERE event_type='IPO_LISTED' AND payload_json LIKE ?")){s.setString(1,"%"+offering+"%");try(var r=s.executeQuery()){r.next();return r.getLong(1);}}catch(Exception e){throw new RuntimeException(e);}}
 static final class MutableClock implements AppClock { Instant now=Instant.parse("2026-08-14T12:00:00Z"); public Instant now(){return now;} }
 static class Escrow implements TreasuryEscrowGateway { public EconomyGateway.Result withdrawPlayer(UUID p,Money m,UUID i){return EconomyGateway.Result.success("");} public EconomyGateway.Result depositEscrow(Money m,UUID i){return EconomyGateway.Result.success("");} public EconomyGateway.Result withdrawEscrow(Money m,UUID i){return EconomyGateway.Result.success("");} public EconomyGateway.Result refundPlayer(UUID p,Money m,UUID i){return EconomyGateway.Result.success("");} }
 static class CountingEscrow extends Escrow { int withdrawCalls; @Override public EconomyGateway.Result withdrawPlayer(UUID p,Money m,UUID i){withdrawCalls++;return super.withdrawPlayer(p,m,i);} }
 static final class ThrowingEscrow extends CountingEscrow { int depositCalls; boolean throwDeposit; @Override public EconomyGateway.Result depositEscrow(Money m,UUID i){depositCalls++;if(throwDeposit)throw new IllegalStateException("deposit failed");return super.depositEscrow(m,i);} }
 static final class DirectTransactions implements cn.blockeco.exchange.ports.TransactionRunner { public <T>T inTransaction(SqlWork<T> work){try{return work.execute(null);}catch(Exception e){throw new RuntimeException(e);}} }
 static class FailingPrepareRepository implements PrimaryOfferingRepository { private final RuntimeException failure; FailingPrepareRepository(RuntimeException failure){this.failure=failure;} public void announce(Connection c,PrimaryOffering o)throws SQLException{} public Optional<PrimaryOffering> find(UUID id){return Optional.of(PrimaryOffering.plan(new CompanyId(UUID.randomUUID()),Money.ofMinor(10),Money.ofMinor(10),Instant.parse("2026-08-14T12:00:00Z")));} public long paidInCapital(CompanyId id){return 0;} public boolean hasActiveAsset(CompanyId id){return false;} public boolean isFounder(CompanyId id,UUID founder){return false;} public SubscriptionPreparation prepareSubscription(Connection c,UUID id,PrimaryOffering o,UUID s,long shares,Instant now){throw failure;} public void markWithdrawn(Connection c,UUID id,Instant now)throws SQLException{} public void markEscrowDeposited(Connection c,UUID id,Instant now)throws SQLException{} public void completeSubscription(Connection c,UUID id,Instant now)throws SQLException{} public void markAmbiguous(Connection c,UUID id,String stage,String reason,Instant now)throws SQLException{} public void cancelPrepared(Connection c,UUID id,Instant now)throws SQLException{} public void closeExpired(Connection c,Instant now)throws SQLException{} }
 static final class ScenarioRepository extends FailingPrepareRepository { boolean failWithdrawn,ambiguous; ScenarioRepository(){super(new IllegalStateException());} @Override public SubscriptionPreparation prepareSubscription(Connection c,UUID id,PrimaryOffering o,UUID s,long shares,Instant now){return new SubscriptionPreparation(new TreasuryOperation(id,o.companyId(),s,Money.ofMinor(10),id.toString(),TreasuryOperationState.PREPARED,now,now),true);}@Override public void markWithdrawn(Connection c,UUID id,Instant now){if(failWithdrawn)throw new IllegalStateException("mark failed");}@Override public void markAmbiguous(Connection c,UUID id,String stage,String reason,Instant now){ambiguous=true;} }
 static long auditCount(Database db,UUID offering,String to){try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT COUNT(*) FROM audit_events WHERE event_type='IPO_STATE_CHANGED' AND payload_json LIKE ? AND payload_json LIKE ?")){s.setString(1,"%"+offering+"%");s.setString(2,"%\"to\":\""+to+"\"%");try(var r=s.executeQuery()){r.next();return r.getLong(1);}}catch(Exception e){throw new RuntimeException(e);}}
}
