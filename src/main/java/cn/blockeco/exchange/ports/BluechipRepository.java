package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for system-operated bluechip companies and their finite seed fund. */
public interface BluechipRepository {
    Optional<BluechipCompany> findByStockCode(String stockCode);
    Optional<BluechipCompany> findByCompanyId(CompanyId companyId);
    List<BluechipCompany> all();
    List<CompanyId> bluechipCompanyIds();
    List<BluechipMetadata> allMetadata();
    List<SeedAudit> initializedSeeds(CompanyId companyId);
    /** Append a protection transition only when its state differs from the last observed state. */
    void recordLiquidityStatus(CompanyId companyId, boolean degraded, Instant occurredAt);
    void insertInitial(Connection connection, BluechipSeed seed) throws SQLException;
    void persistEvent(Connection connection, cn.blockeco.exchange.domain.bluechip.BluechipEvent event) throws SQLException;
    void applyModelImpact(Connection connection, String scope, String companyId, String industry, int impactBps) throws SQLException;
    void decayModels(Connection connection) throws SQLException;
    void closeCandle(Connection connection, CompanyId companyId, LocalDate day) throws SQLException;
    List<cn.blockeco.exchange.domain.bluechip.BluechipEvent> recentEvents(int limit);
    Optional<Candle> candle(CompanyId companyId, LocalDate day);
    List<BluechipCompany> dueCompanyEvents(Instant now);
    boolean marketEventDue(Instant now);
    void scheduleNextCompanyEvent(Connection connection, CompanyId companyId, Instant nextEventAt) throws SQLException;
    int profitExpectationBps(CompanyId companyId);
    Optional<MarketSchedule> marketSchedule();
    void saveMarketSchedule(Connection connection, MarketSchedule schedule) throws SQLException;
    void expireEvents(Connection connection, Instant now) throws SQLException;
    /** Settles every listed company's next due dividend cycle in the caller's transaction. */
    List<DividendSettlement> settleDueDividendRuns(Connection connection, Instant now, long bluechipBaseProfitMinor) throws SQLException;

    record BluechipCompany(CompanyId companyId, StockListing listing, String industry, UUID systemAccountId,
                           Money referencePrice, Money modelPrice, Money lowerPrice, Money upperPrice, int spreadBps, long fundShares, Money fundCash) { }

    record BluechipMetadata(CompanyId companyId, StockListing listing, String industry, UUID systemAccountId,
                            Money referencePrice, Money lowerPrice, Money upperPrice, int spreadBps,
                            int eventSensitivityBps, int payoutBps) { }

    record SeedAudit(Money cash, long shares) { }
    record Candle(Money open, Money high, Money low, Money close, long volumeShares) { }
    record MarketSchedule(Instant nextCompanyEventAt, Instant nextMarketEventAt) { }
    record DividendSettlement(CompanyId companyId, Money profit, Money distributed, int paymentCount, String idempotencyKey) { }

    record BluechipSeed(CompanyId companyId, StockListing listing, String industry, UUID systemAccountId,
                        Money referencePrice, Money lowerPrice, Money upperPrice, int spreadBps,
                        int eventSensitivityBps, int payoutBps, long fundShares, Money fundCash, Instant initializedAt) { }
}
