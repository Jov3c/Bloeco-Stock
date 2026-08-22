package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public interface StockListingRepository {
    Optional<StockListing> findByCompany(CompanyId companyId);
    Optional<StockListing> findByCode(String stockCode);
    StockListing allocate(Connection connection, CompanyId companyId, Money issueReferencePrice, long issuedShares, Instant listedAt)
            throws SQLException;
}
