package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class PublicStockQueryServiceTest {
    @Test void every_read_returns_a_stage_and_defers_repository_work_to_the_sql_executor() {
        PublicStockRepository repository = mock(PublicStockRepository.class);
        PublicMarketRow row = new PublicMarketRow("红石工业", "BS000001", Money.ofMinor(10), Money.ofMinor(10000), 1000, CompanyStatus.LISTED);
        when(repository.market(any(),any())).thenReturn(List.of(row));
        when(repository.listOfferings(2)).thenReturn(List.of());
        when(repository.findInfo("红石工业")).thenReturn(Optional.empty());
        when(repository.findAnnouncements("红石工业", 2)).thenReturn(List.of());
        when(repository.findOpenOfferingByCompanyOrCode("红石工业")).thenReturn(Optional.empty());
        when(repository.symbols()).thenReturn(List.of());
        QueuedExecutor sqlExecutor = new QueuedExecutor();

        PublicStockQueryService service = new PublicStockQueryService(repository, sqlExecutor, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), ZoneOffset.UTC);

        CompletionStage<List<PublicMarketRow>> market = service.market();
        CompletionStage<?> ipo = service.ipo(2);
        CompletionStage<?> info = service.info("红石工业");
        CompletionStage<?> announcements = service.announcements("红石工业", 2);
        CompletionStage<?> resolve = service.resolveOpenOffering("红石工业");
        CompletionStage<?> symbols = service.symbols();
        assertThat(market).isNotNull(); assertThat(ipo).isNotNull(); assertThat(info).isNotNull(); assertThat(announcements).isNotNull(); assertThat(resolve).isNotNull(); assertThat(symbols).isNotNull();
        verifyNoInteractions(repository);
        assertThat(sqlExecutor.tasks).hasSize(6);

        sqlExecutor.runAll();

        assertThat(market).isCompletedWithValue(List.of(row));
        assertThat(ipo).isCompleted(); assertThat(info).isCompleted(); assertThat(announcements).isCompleted(); assertThat(resolve).isCompleted(); assertThat(symbols).isCompleted();
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
                    for (int index = 1; index <= 50; index++) {
                        Instant announced = Instant.EPOCH.minusSeconds(index);
                        open.setString(1, UUID.randomUUID().toString()); open.setString(2, company.value().toString()); open.setLong(3, 100); open.setLong(4, 10); open.setLong(5, 10); open.setString(6, announced.toString()); open.setString(7, announced.toString()); open.setString(8, Instant.MAX.toString()); open.setString(9, "ANNOUNCED"); open.executeUpdate();
                    }
                    announcement.setString(1, UUID.randomUUID().toString()); announcement.setString(2, company.value().toString()); announcement.setString(3, offering.toString()); announcement.setString(4, "公开公告"); announcement.setString(5, Instant.EPOCH.toString()); announcement.executeUpdate();
                    announcement.setString(1, UUID.randomUUID().toString()); announcement.setString(2, company.value().toString()); announcement.setString(3, offering.toString()); announcement.setString(4, "最新公告"); announcement.setString(5, Instant.EPOCH.plusSeconds(1).toString()); announcement.executeUpdate();
                }
                return null;
            });
            SqlPublicStockRepository repository = new SqlPublicStockRepository(database.dataSource());
            assertThat(repository.market()).extracting(PublicMarketRow::stockCode, PublicMarketRow::marketCapitalization).containsExactly(org.assertj.core.groups.Tuple.tuple("BS000001", Money.ofMinor(10_000)));
            assertThat(repository.listOfferings(0)).hasSize(1);
            assertThat(repository.listOfferings(100)).hasSize(50);
            assertThat(repository.symbols()).containsExactly(new PublicStockSymbol("Ipo Test", Optional.of("BS000001")));
            assertThat(repository.findInfo("\u00a0IPO\u3000\u3000test\u00a0")).isPresent();
            assertThat(repository.findInfo("x")).isEmpty();
            assertThat(repository.findAnnouncements("x", 1)).isEmpty();
            assertThat(repository.findOpenOfferingByCompanyOrCode("x")).isEmpty();
            assertThat(repository.findInfo("bs000001")).isPresent();
            assertThat(repository.findOpenOfferingByCompanyOrCode("BS000001")).contains(offering);
            assertThat(repository.findAnnouncements("\u00a0IPO\u3000test\u00a0", 100)).extracting(PublicAnnouncement::body).containsExactly("最新公告", "公开公告");
            assertThat(repository.findAnnouncements("BS000001", 0)).hasSize(1);
        } finally { Files.deleteIfExists(file); }
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
