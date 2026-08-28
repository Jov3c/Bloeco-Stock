package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public interface CompanyOperationsRepository {
    RecordResult record(Connection connection, AssetBinding binding, VerifiedOperatingEvent event, Instant recordedAt) throws SQLException;

    Optional<FinancialSnapshot> snapshot(CompanyId companyId);

    enum RecordResult { RECORDED, DUPLICATE }

    record FinancialSnapshot(CompanyId companyId, long cash, long retainedEarnings, long accumulatedLoss, long income, long expense) { }
}
