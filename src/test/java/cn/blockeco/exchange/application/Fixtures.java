package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.*;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.infrastructure.sql.SqlAssetBindingRepository;

public final class Fixtures {
    private Fixtures() { }
    public static CompanyId company(Database db, long paidIn) throws Exception {
        CompanyId id = new CompanyId(UUID.randomUUID()); UUID founder = UUID.randomUUID();
        db.inTransaction(c -> { try (PreparedStatement s = c.prepareStatement("INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor, total_shares, dividend_basis_points, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, id.value().toString()); s.setString(2, "ipo test"); s.setString(3, "Ipo Test"); s.setString(4, founder.toString()); s.setString(5, CompanyStatus.PENDING_ASSET_BINDING.name()); s.setLong(6, paidIn); s.setLong(7, 1000); s.setInt(8, 5000); s.setString(9, Instant.parse("2026-08-14T12:00:00Z").toString()); s.executeUpdate(); }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO company_cash_accounts (company_id, cash_minor, paid_in_capital_minor, retained_earnings_minor, reserved_minor) VALUES (?, ?, ?, ?, ?)")) { s.setString(1, id.value().toString()); s.setLong(2, paidIn); s.setLong(3, paidIn); s.setLong(4, 0); s.setLong(5, 0); s.executeUpdate(); }
            return null; }); return id;
    }
    public static UUID founder(Database db, CompanyId company) throws Exception {
        try (var c = db.dataSource().getConnection(); var s = c.prepareStatement("SELECT founder_uuid FROM companies WHERE id=?")) {
            s.setString(1, company.value().toString()); try (var rows=s.executeQuery()) { rows.next(); return UUID.fromString(rows.getString(1)); }
        }
    }
    public static void activeAsset(Database db, CompanyId company, UUID owner) {
        db.inTransaction(c -> { new SqlAssetBindingRepository(db.dataSource()).insertActive(c, new AssetBinding(UUID.randomUUID(), company, "test", UUID.randomUUID().toString(), owner, AssetBindingState.ACTIVE, Instant.parse("2026-08-14T12:00:00Z"))); return null; });
    }
}
