package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

public interface CompanyOperationsRepository {
    RecordResult record(Connection connection, AssetBinding binding, VerifiedOperatingEvent event, Instant recordedAt) throws SQLException;

    Optional<FinancialSnapshot> snapshot(CompanyId companyId);

    boolean generateMonthlyReport(Connection connection, CompanyId companyId, YearMonth month, ZoneId zone, Instant generatedAt) throws SQLException;

    List<MonthlyReport> recentReports(CompanyId companyId, int limit);

    List<CompanyId> listedCompanyIds();

    FinanceDashboard financeDashboard(CompanyId companyId, Instant now, ZoneId zone);

    enum RecordResult { RECORDED, DUPLICATE }

    record FinancialSnapshot(CompanyId companyId, long cash, long retainedEarnings, long accumulatedLoss, long income, long expense) { }

    record MonthlyReport(CompanyId companyId, Instant periodStart, Instant periodEnd, long income, long expense,
                         long netProfit, long retainedEarnings, long cash, Instant generatedAt) { }
    record FinanceDashboard(FinancialSnapshot snapshot, Instant nextDividendAt, List<MonthlyReport> recentReports) { }
}
