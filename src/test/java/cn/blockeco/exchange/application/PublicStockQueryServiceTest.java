package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.PublicStockRepository;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlPublicStockRepository;
import cn.blockeco.exchange.domain.company.CompanyId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class PublicStockQueryServiceTest {
    @Test void market_is_dispatched_to_the_sql_executor() {
        PublicStockRepository repository = mock(PublicStockRepository.class);
        PublicMarketRow row = new PublicMarketRow("红石工业", "BS000001", Money.ofMinor(10), Money.ofMinor(10000), 1000, CompanyStatus.LISTED);
        when(repository.market()).thenReturn(List.of(row));
        Executor sqlExecutor = mock(Executor.class);
        doExecuteInline(sqlExecutor);

        PublicStockQueryService service = new PublicStockQueryService(repository, sqlExecutor);

        assertThat(service.market()).isCompletedWithValue(List.of(row));
        verify(sqlExecutor).execute(any(Runnable.class));
    }

    @Test void sql_public_queries_normalize_names_accept_codes_and_clamp_announcements() throws Exception {
        Path file = Files.createTempFile("public-stock-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            CompanyId company = Fixtures.company(database, 100);
            UUID offering = UUID.randomUUID();
            database.inTransaction(connection -> {
                try (var listing = connection.prepareStatement("INSERT INTO stock_listings VALUES (?,?,?,?,?)");
                     var update = connection.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?");
                     var open = connection.prepareStatement("INSERT INTO primary_offerings VALUES (?,?,?,?,?,?,?,?,?)");
                     var announcement = connection.prepareStatement("INSERT INTO company_announcements VALUES (?,?,?,?,?)")) {
                    listing.setString(1, company.value().toString()); listing.setString(2, "BS000001"); listing.setLong(3, 10); listing.setLong(4, 1000); listing.setString(5, Instant.EPOCH.toString()); listing.executeUpdate();
                    update.setString(1, company.value().toString()); update.executeUpdate();
                    open.setString(1, offering.toString()); open.setString(2, company.value().toString()); open.setLong(3, 100); open.setLong(4, 10); open.setLong(5, 10); open.setString(6, Instant.EPOCH.toString()); open.setString(7, Instant.EPOCH.toString()); open.setString(8, Instant.MAX.toString()); open.setString(9, "OPEN"); open.executeUpdate();
                    announcement.setString(1, UUID.randomUUID().toString()); announcement.setString(2, company.value().toString()); announcement.setString(3, offering.toString()); announcement.setString(4, "公开公告"); announcement.setString(5, Instant.EPOCH.toString()); announcement.executeUpdate();
                }
                return null;
            });
            SqlPublicStockRepository repository = new SqlPublicStockRepository(database.dataSource());
            assertThat(repository.market()).extracting(PublicMarketRow::stockCode, PublicMarketRow::marketCapitalization).containsExactly(org.assertj.core.groups.Tuple.tuple("BS000001", Money.ofMinor(10_000)));
            assertThat(repository.findInfo(" ipo test ")).isPresent();
            assertThat(repository.findInfo("bs000001")).isPresent();
            assertThat(repository.findOpenOfferingByCompanyOrCode("BS000001")).contains(offering);
            assertThat(repository.findAnnouncements("BS000001", 100)).hasSize(1);
        } finally { Files.deleteIfExists(file); }
    }

    private static void doExecuteInline(Executor executor) {
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }
}
