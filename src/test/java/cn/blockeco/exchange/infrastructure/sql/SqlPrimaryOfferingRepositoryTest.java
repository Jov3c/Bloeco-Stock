package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import cn.blockeco.exchange.application.Fixtures;
import cn.blockeco.exchange.domain.money.Money;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlPrimaryOfferingRepositoryTest {
 @Test void close_expired_does_not_issue_unsold_shares() throws Exception { Path file=Files.createTempFile("ipo-sql-", ".db"); try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate(); var company=Fixtures.company(db,100); var repo=new SqlPrimaryOfferingRepository(db.dataSource()); var offer=cn.blockeco.exchange.domain.finance.PrimaryOffering.plan(company,Money.ofMinor(100),Money.ofMinor(10),Instant.parse("2026-08-14T12:00:00Z")); db.inTransaction(c->{repo.announce(c,offer);return null;}); db.inTransaction(c->{repo.closeExpired(c,offer.closesAt());return null;}); assertThat(repo.find(offer.id()).orElseThrow().state()).isEqualTo(cn.blockeco.exchange.domain.finance.PrimaryOfferingState.CLOSED); assertThat(repo.companyTotalShares(company)).isEqualTo(1000); }finally{Files.deleteIfExists(file);} }
}
