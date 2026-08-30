package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyExitRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyExitServiceTest {
    @Test
    void refusesForcedDelistingWithoutAnAdministratorAuthorizationFact() throws Exception {
        try (Fixture f = Fixture.create()) {
            assertThatThrownBy(() -> f.service().forceDelist(UUID.randomUUID(), false, f.company, Money.ofMinor(10), "exit:forced"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void freezesListedCompanyThenCreditsEachLiquidationClaimOnlyOnce() throws Exception {
        try (Fixture f = Fixture.create()) {
            CompanyExitService service = f.service();
            UUID action = service.announceVoluntaryDelist(f.founder, f.company, Money.ofMinor(10), "exit:one");
            f.now = f.now.plusSeconds(43_200);
            service.begin(action);
            assertThat(service.releaseOpenOrders(action, 10)).isZero();
            assertThat(service.createClaims(action)).isEqualTo(2);
            assertThat(service.creditClaims(action)).isEqualTo(2);
            assertThat(service.creditClaims(action)).isZero();
            assertThat(f.cash(f.holderA)).isEqualTo(10L);
            assertThat(f.cash(f.holderB)).isEqualTo(20L);
        }
    }

    @Test
    void capsFundAssistanceAfterCompanyCashIsAllocatedFirst() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.setFund(15);
            CompanyExitService service=f.service(); UUID action=service.announceVoluntaryDelist(f.founder,f.company,Money.ofMinor(20),"exit:fund");
            f.now=f.now.plusSeconds(43_200); service.begin(action); service.releaseOpenOrders(action,10); service.createClaims(action);
            assertThat(service.creditClaims(action)).isEqualTo(2);
            assertThat(f.cash(f.holderA)).isEqualTo(20L);
            assertThat(f.cash(f.holderB)).isEqualTo(25L);
            assertThat(f.fund()).isZero();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path file; final Database db; final CompanyId company = new CompanyId(UUID.randomUUID()); final UUID founder = UUID.randomUUID();
        final UUID holderA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID holderB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant now = Instant.parse("2026-08-30T12:00:00Z");
        private Fixture(Path file, Database db) { this.file=file; this.db=db; }
        static Fixture create() throws Exception { Path file=Files.createTempFile("blockeco-exit-service-", ".db"); Database db=new Database("jdbc:sqlite:"+file); db.migrate(); Fixture f=new Fixture(file,db); db.inTransaction(c->{
            try (PreparedStatement s=c.prepareStatement("INSERT INTO companies VALUES (?,?,?,?, 'LISTED',0,1000,5000,?,0)")) { s.setString(1,f.company.value().toString());s.setString(2,"exit service");s.setString(3,"Exit Service");s.setString(4,f.founder.toString());s.setString(5,f.now.toString());s.executeUpdate(); }
            try (PreparedStatement s=c.prepareStatement("INSERT INTO company_cash_accounts VALUES (?,30,0,0,0,0)")) {s.setString(1,f.company.value().toString());s.executeUpdate();}
            try (PreparedStatement s=c.prepareStatement("INSERT INTO stock_listings VALUES (?,'EXITSVC',10,1000,?)")) {s.setString(1,f.company.value().toString());s.setString(2,f.now.toString());s.executeUpdate();}
            try (PreparedStatement s=c.prepareStatement("INSERT INTO share_holdings VALUES (?,?,?,0)")) {s.setString(1,f.company.value().toString());s.setString(2,f.holderA.toString());s.setLong(3,1);s.executeUpdate();s.setString(2,f.holderB.toString());s.setLong(3,2);s.executeUpdate();} return null; }); return f; }
        CompanyExitService service() { SqlSecuritiesCashRepository cash=new SqlSecuritiesCashRepository(db.dataSource()); return new CompanyExitService(new SqlCompanyRepository(db.dataSource()),new SqlCompanyExitRepository(db.dataSource()),new SqlSecondaryTradingRepository(db.dataSource(),cash),cash,db,()->now); }
        long cash(UUID player) throws Exception {try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT available_minor FROM securities_cash_accounts WHERE player_uuid=?")){s.setString(1,player.toString());var r=s.executeQuery();r.next();return r.getLong(1);}}
        void setFund(long amount) { db.inTransaction(c->{try(var s=c.prepareStatement("UPDATE compensation_fund SET balance_minor=? WHERE singleton=1")){s.setLong(1,amount);s.executeUpdate();}return null;}); }
        long fund() throws Exception {try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT balance_minor FROM compensation_fund WHERE singleton=1")){var r=s.executeQuery();r.next();return r.getLong(1);}}
        @Override public void close() throws Exception {db.close();Files.deleteIfExists(file);}
    }
}
