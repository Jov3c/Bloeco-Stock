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
import org.junit.jupiter.api.Test;

class PrimaryOfferingServiceTest {
 @Test void first_subscription_opens_announced_offering_in_its_transaction_before_money_moves() throws Exception {
  Path file=Files.createTempFile("ipo-sub-open-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate(); CompanyId c=Fixtures.company(db,100); UUID founder=Fixtures.founder(db,c); Fixtures.activeAsset(db,c,founder); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,new Escrow(),Runnable::run,clock); var offer=service.announce(c,founder,Money.ofMinor(100),Money.ofMinor(10)).toCompletableFuture().join(); clock.now=offer.opensAt(); service.subscribe(UUID.randomUUID(),offer.id(),1).toCompletableFuture().join(); assertThat(repo.find(offer.id()).orElseThrow().state()).isEqualTo(cn.blockeco.exchange.domain.finance.PrimaryOfferingState.OPEN); assertThat(auditCount(db,offer.id(),"OPEN")).isEqualTo(1); }finally{Files.deleteIfExists(file);} }
 @Test void never_replays_a_persisted_prepared_subscription_after_a_crash_window() throws Exception {
  Path file=Files.createTempFile("ipo-recovery-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId c=Fixtures.company(db,100); MutableClock clock=new MutableClock(); SqlPrimaryOfferingRepository repo=new SqlPrimaryOfferingRepository(db.dataSource()); UUID founder=Fixtures.founder(db,c);
   Fixtures.activeAsset(db,c,founder); PrimaryOfferingService service=new PrimaryOfferingService(repo,db,new Escrow(),Runnable::run,clock);
   var offer=service.announce(c,founder,Money.ofMinor(100),Money.ofMinor(10)).toCompletableFuture().join(); clock.now=offer.opensAt(); UUID subscriber=UUID.randomUUID(); UUID subscription=UUID.nameUUIDFromBytes((offer.id()+":"+subscriber+":2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
   db.inTransaction(x->{repo.prepareSubscription(x,subscription,offer,subscriber,2,clock.now);return null;});
   assertThatThrownBy(()->service.subscribe(subscriber,offer.id(),2).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class).hasMessageContaining("recovery");
  } finally {Files.deleteIfExists(file);} }
 @Test void requires_active_asset_caps_target_and_honors_exact_open_close_boundaries() throws Exception {
  Path file=Files.createTempFile("ipo-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId c=Fixtures.company(db,100_000); UUID founder=Fixtures.founder(db,c); MutableClock clock=new MutableClock();
   PrimaryOfferingService service=new PrimaryOfferingService(new SqlPrimaryOfferingRepository(db.dataSource()), db, new Escrow(), Runnable::run, clock);
   assertThatThrownBy(()->service.announce(c,founder,Money.ofMinor(1),Money.ofMinor(1)).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
   Fixtures.activeAsset(db,c,founder);
   assertThatThrownBy(()->service.announce(c,founder,Money.ofMinor(500_001),Money.ofMinor(1)).toCompletableFuture().join()).hasCauseInstanceOf(IllegalArgumentException.class);
   var offer=service.announce(c,founder,Money.ofMinor(500_000),Money.ofMinor(100)).toCompletableFuture().join(); UUID buyer=UUID.randomUUID();
   assertThatThrownBy(()->service.subscribe(buyer,offer.id(),1).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
   clock.now=offer.opensAt(); service.subscribe(buyer,offer.id(),2).toCompletableFuture().join();
   clock.now=offer.closesAt(); assertThatThrownBy(()->service.subscribe(UUID.randomUUID(),offer.id(),1).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
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
 private static String companyStatus(Database db,CompanyId company){try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT status FROM companies WHERE id=?")){s.setString(1,company.value().toString());try(var r=s.executeQuery()){r.next();return r.getString(1);}}catch(Exception e){throw new RuntimeException(e);}}
 private static long ipoListedAuditCount(Database db,UUID offering){try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT COUNT(*) FROM audit_events WHERE event_type='IPO_LISTED' AND payload_json LIKE ?")){s.setString(1,"%"+offering+"%");try(var r=s.executeQuery()){r.next();return r.getLong(1);}}catch(Exception e){throw new RuntimeException(e);}}
 static final class MutableClock implements AppClock { Instant now=Instant.parse("2026-08-14T12:00:00Z"); public Instant now(){return now;} }
 static final class Escrow implements TreasuryEscrowGateway { public EconomyGateway.Result withdrawPlayer(UUID p,Money m,UUID i){return EconomyGateway.Result.success("");} public EconomyGateway.Result depositEscrow(Money m,UUID i){return EconomyGateway.Result.success("");} public EconomyGateway.Result withdrawEscrow(Money m,UUID i){return EconomyGateway.Result.success("");} public EconomyGateway.Result refundPlayer(UUID p,Money m,UUID i){return EconomyGateway.Result.success("");} }
 static long auditCount(Database db,UUID offering,String to){try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT COUNT(*) FROM audit_events WHERE event_type='IPO_STATE_CHANGED' AND payload_json LIKE ? AND payload_json LIKE ?")){s.setString(1,"%"+offering+"%");s.setString(2,"%\"to\":\""+to+"\"%");try(var r=s.executeQuery()){r.next();return r.getLong(1);}}catch(Exception e){throw new RuntimeException(e);}}
}
