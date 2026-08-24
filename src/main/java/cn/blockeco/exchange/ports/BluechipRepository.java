package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for system-operated bluechip companies and their finite seed fund. */
public interface BluechipRepository {
    Optional<BluechipCompany> findByStockCode(String stockCode);
    Optional<BluechipCompany> findByCompanyId(CompanyId companyId);
    List<BluechipCompany> all();
    void insertInitial(Connection connection, BluechipSeed seed) throws SQLException;

    record BluechipCompany(CompanyId companyId, StockListing listing, String industry, UUID systemAccountId,
                           Money referencePrice, Money lowerPrice, Money upperPrice, long fundShares, Money fundCash) { }

    record BluechipSeed(CompanyId companyId, StockListing listing, String industry, UUID systemAccountId,
                        Money referencePrice, Money lowerPrice, Money upperPrice, int spreadBps,
                        int eventSensitivityBps, int payoutBps, long fundShares, Money fundCash, Instant initializedAt) { }
}
