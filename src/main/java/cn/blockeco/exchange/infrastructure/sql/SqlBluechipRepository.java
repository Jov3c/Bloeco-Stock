package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.BluechipRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlBluechipRepository implements BluechipRepository {
    private static final String SELECT = """
            SELECT bc.company_id, bc.industry, bc.system_account_uuid, bc.model_price_minor, bc.lower_price_minor,
                   bc.upper_price_minor, bc.spread_bps, sl.stock_code, sl.issue_reference_price_minor, sl.issued_shares, sl.listed_at,
                   h.available_shares, COALESCE((SELECT SUM(cash_delta_minor) FROM bluechip_fund_audit fa
                       WHERE fa.company_id = bc.company_id), 0) AS fund_cash_minor
            FROM bluechip_companies bc
            JOIN stock_listings sl ON sl.company_id = bc.company_id
            JOIN share_holdings h ON h.company_id = bc.company_id AND h.holder_uuid = bc.system_account_uuid
            """;
    private static final String METADATA_SELECT = """
            SELECT bc.company_id, bc.industry, bc.system_account_uuid, bc.model_price_minor, bc.lower_price_minor,
                   bc.upper_price_minor, bc.spread_bps, bc.event_sensitivity_bps, bc.payout_bps, sl.stock_code,
                   sl.issue_reference_price_minor, sl.issued_shares, sl.listed_at
            FROM bluechip_companies bc JOIN stock_listings sl ON sl.company_id = bc.company_id
            """;
    private final DataSource dataSource;

    public SqlBluechipRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override public Optional<BluechipCompany> findByStockCode(String stockCode) { return findOne(SELECT + " WHERE sl.stock_code = ?", stockCode); }
    @Override public Optional<BluechipCompany> findByCompanyId(CompanyId companyId) { return findOne(SELECT + " WHERE bc.company_id = ?", companyId.value().toString()); }
    @Override public List<BluechipCompany> all() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(SELECT + " ORDER BY sl.stock_code"); ResultSet rows = statement.executeQuery()) {
            java.util.ArrayList<BluechipCompany> companies = new java.util.ArrayList<>(); while (rows.next()) companies.add(map(rows)); return List.copyOf(companies);
        } catch (SQLException exception) { throw new IllegalStateException("could not read bluechip companies", exception); }
    }
    @Override public List<CompanyId> bluechipCompanyIds() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT company_id FROM bluechip_companies ORDER BY company_id"); ResultSet rows = statement.executeQuery()) {
            java.util.ArrayList<CompanyId> ids = new java.util.ArrayList<>(); while (rows.next()) ids.add(new CompanyId(UUID.fromString(rows.getString(1)))); return List.copyOf(ids);
        } catch (SQLException exception) { throw new IllegalStateException("could not read bluechip company ids", exception); }
    }
    @Override public List<BluechipMetadata> allMetadata() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(METADATA_SELECT + " ORDER BY bc.company_id"); ResultSet rows = statement.executeQuery()) {
            java.util.ArrayList<BluechipMetadata> metadata = new java.util.ArrayList<>(); while (rows.next()) metadata.add(mapMetadata(rows)); return List.copyOf(metadata);
        } catch (SQLException exception) { throw new IllegalStateException("could not read bluechip metadata", exception); }
    }
    @Override public List<SeedAudit> initializedSeeds(CompanyId companyId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT cash_delta_minor, shares_delta FROM bluechip_fund_audit WHERE company_id = ? AND operation = 'BLUECHIP_INITIALIZED' ORDER BY id")) {
            statement.setString(1, companyId.value().toString()); try (ResultSet rows = statement.executeQuery()) { java.util.ArrayList<SeedAudit> audits = new java.util.ArrayList<>(); while (rows.next()) audits.add(new SeedAudit(Money.ofMinor(rows.getLong(1)), rows.getLong(2))); return List.copyOf(audits); }
        } catch (SQLException exception) { throw new IllegalStateException("could not read bluechip seed audit", exception); }
    }
    @Override public void recordLiquidityStatus(CompanyId companyId, boolean degraded, Instant occurredAt) {
        String eventType = degraded ? "BLUECHIP_LIQUIDITY_DEGRADED" : "BLUECHIP_LIQUIDITY_RESTORED";
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement statement = connection.prepareStatement("UPDATE bluechip_companies SET quotes_paused = ? WHERE company_id = ? AND quotes_paused <> ?")) {
                    statement.setInt(1, degraded ? 1 : 0); statement.setString(2, companyId.value().toString()); statement.setInt(3, degraded ? 1 : 0); changed = statement.executeUpdate();
                }
                if (changed == 1) try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at) VALUES (?, ?, NULL, ?, ?, ?)")) {
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, companyId.value().toString()); statement.setString(3, eventType);
                    statement.setString(4, "{\"degraded\":" + degraded + "}"); statement.setString(5, occurredAt.toString()); statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) { connection.rollback(); throw exception; }
            finally { connection.setAutoCommit(autoCommit); }
        } catch (SQLException exception) { throw new IllegalStateException("could not record bluechip liquidity status", exception); }
    }
    @Override public void adjustFund(Connection connection, String stockCode, String kind, long delta, Instant occurredAt) throws SQLException {
        requireTransaction(connection); BluechipCompany company=findByStockCode(stockCode).orElseThrow(()->new IllegalArgumentException("unknown bluechip"));
        if (!"cash".equals(kind) && !"shares".equals(kind)) throw new IllegalArgumentException("unknown fund kind");
        if ("cash".equals(kind)) {
            try (PreparedStatement statement=connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = available_minor + ? WHERE player_uuid = ? AND available_minor + ? >= 0")) { statement.setLong(1,delta);statement.setString(2,company.systemAccountId().toString());statement.setLong(3,delta);if(statement.executeUpdate()!=1)throw new IllegalArgumentException("negative cash"); }
        } else {
            try (PreparedStatement statement=connection.prepareStatement("UPDATE share_holdings SET available_shares = available_shares + ? WHERE company_id = ? AND holder_uuid = ? AND available_shares + ? >= 0")) { statement.setLong(1,delta);statement.setString(2,company.companyId().value().toString());statement.setString(3,company.systemAccountId().toString());statement.setLong(4,delta);if(statement.executeUpdate()!=1)throw new IllegalArgumentException("negative shares"); }
        }
        try (PreparedStatement statement=connection.prepareStatement("INSERT INTO bluechip_fund_audit (id, company_id, operation, cash_delta_minor, shares_delta, occurred_at) VALUES (?, ?, 'ADMIN_FUND_ADJUSTED', ?, ?, ?)")) { statement.setString(1,UUID.randomUUID().toString());statement.setString(2,company.companyId().value().toString());statement.setLong(3,"cash".equals(kind)?delta:0);statement.setLong(4,"shares".equals(kind)?delta:0);statement.setString(5,occurredAt.toString());statement.executeUpdate(); }
    }

    @Override public void insertInitial(Connection connection, BluechipSeed seed) throws SQLException {
        requireTransaction(connection);
        insertHolding(connection, seed);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bluechip_companies (company_id, industry, system_account_uuid, lower_price_minor, upper_price_minor,
                  model_price_minor, spread_bps, event_sensitivity_bps, payout_bps, next_event_at, next_dividend_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, seed.companyId().value().toString()); statement.setString(2, seed.industry()); statement.setString(3, seed.systemAccountId().toString());
            statement.setLong(4, seed.lowerPrice().minorUnits()); statement.setLong(5, seed.upperPrice().minorUnits()); statement.setLong(6, seed.referencePrice().minorUnits());
            statement.setInt(7, seed.spreadBps()); statement.setInt(8, seed.eventSensitivityBps()); statement.setInt(9, seed.payoutBps());
            statement.setString(10, seed.initializedAt().plus(6, ChronoUnit.HOURS).toString()); statement.setString(11, seed.initializedAt().plus(15, ChronoUnit.DAYS).toString()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO company_industry (company_id, industry) VALUES (?, ?)")) {
            statement.setString(1, seed.companyId().value().toString()); statement.setString(2, seed.industry()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO bluechip_fund_audit (id, company_id, operation, cash_delta_minor, shares_delta, occurred_at) VALUES (?, ?, 'BLUECHIP_INITIALIZED', ?, ?, ?)")) {
            statement.setString(1, deterministicId("fund-audit:", seed.companyId()).toString()); statement.setString(2, seed.companyId().value().toString());
            statement.setLong(3, seed.fundCash().minorUnits()); statement.setLong(4, seed.fundShares()); statement.setString(5, seed.initializedAt().toString()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at) VALUES (?, ?, ?, 'BLUECHIP_INITIALIZED', ?, ?)")) {
            statement.setString(1, deterministicId("audit:", seed.companyId()).toString()); statement.setString(2, seed.companyId().value().toString()); statement.setString(3, seed.systemAccountId().toString());
            statement.setString(4, "{\"fundCashMinor\":" + seed.fundCash().minorUnits() + ",\"fundShares\":" + seed.fundShares() + ",\"stockCode\":\"" + seed.listing().stockCode() + "\"}"); statement.setString(5, seed.initializedAt().toString()); statement.executeUpdate();
        }
    }

    @Override public void persistEvent(Connection connection, cn.blockeco.exchange.domain.bluechip.BluechipEvent event) throws SQLException {
        requireTransaction(connection);
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO bluechip_events (id, scope, company_id, industry, headline, body, price_impact_bps, profit_impact_bps, starts_at, ends_at, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1,event.id()); s.setString(2,event.scope()); s.setString(3,event.companyId()); s.setString(4,event.industry()); s.setString(5,event.headline()); s.setString(6,event.body()); s.setInt(7,event.priceImpactBps()); s.setInt(8,event.profitImpactBps()); s.setString(9,event.startsAt().toString()); s.setString(10,event.endsAt().toString()); s.setString(11,event.state()); s.executeUpdate();
        }
        if (event.companyId() != null) try (PreparedStatement s = connection.prepareStatement("INSERT INTO company_announcements (id, company_id, body, created_at) VALUES (?, ?, ?, ?)")) {
            s.setString(1, UUID.nameUUIDFromBytes(("event-announcement:"+event.id()).getBytes(StandardCharsets.UTF_8)).toString()); s.setString(2,event.companyId()); s.setString(3,event.headline()+" — "+event.body()); s.setString(4,event.startsAt().toString()); s.executeUpdate();
        }
        if ("INDUSTRY".equals(event.scope())) try (PreparedStatement companies = connection.prepareStatement("SELECT company_id FROM company_industry WHERE industry=?"); PreparedStatement announcement = connection.prepareStatement("INSERT INTO company_announcements (id, company_id, body, created_at) VALUES (?, ?, ?, ?)")) {
            companies.setString(1,event.industry()); try(ResultSet rows=companies.executeQuery()){while(rows.next()){String id=rows.getString(1);announcement.setString(1,UUID.nameUUIDFromBytes(("event-announcement:"+event.id()+":"+id).getBytes(StandardCharsets.UTF_8)).toString());announcement.setString(2,id);announcement.setString(3,event.headline()+" — "+event.body());announcement.setString(4,event.startsAt().toString());announcement.addBatch();} announcement.executeBatch();}
        }
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at) VALUES (?, ?, NULL, 'BLUECHIP_EVENT', ?, ?)")) {
            s.setString(1, UUID.nameUUIDFromBytes(("event-audit:"+event.id()).getBytes(StandardCharsets.UTF_8)).toString()); s.setString(2,event.companyId()); s.setString(3,"{\"scope\":\""+event.scope()+"\",\"impactBps\":"+event.priceImpactBps()+"}"); s.setString(4,event.startsAt().toString()); s.executeUpdate();
        }
    }
    @Override public void applyModelImpact(Connection connection, String scope, String companyId, String industry, int impactBps) throws SQLException {
        requireTransaction(connection); String predicate = "COMPANY".equals(scope) ? "company_id = ?" : "industry = ?";
        try (PreparedStatement select = connection.prepareStatement("SELECT company_id, model_price_minor, lower_price_minor, upper_price_minor, event_sensitivity_bps FROM bluechip_companies WHERE " + predicate); PreparedStatement update = connection.prepareStatement("UPDATE bluechip_companies SET model_price_minor=? WHERE company_id=?")) {
            select.setString(1, "COMPANY".equals(scope) ? companyId : industry); try (ResultSet rows=select.executeQuery()) { while(rows.next()) { long old=rows.getLong(2); long delta=Math.round(old * (impactBps / 10_000.0d) * (rows.getInt(5) / 10_000.0d)); long next=Math.max(rows.getLong(3)+1,Math.min(rows.getLong(4)-1,Math.addExact(old,delta))); update.setLong(1,next); update.setString(2,rows.getString(1)); update.addBatch(); } update.executeBatch(); }
        }
        if ("INDUSTRY".equals(scope)) try (PreparedStatement s=connection.prepareStatement("INSERT INTO company_market_expectations (company_id,profit_impact_bps) SELECT company_id, ? FROM company_industry WHERE industry=? ON CONFLICT(company_id) DO UPDATE SET profit_impact_bps=excluded.profit_impact_bps")) { s.setInt(1,impactBps/2);s.setString(2,industry);s.executeUpdate(); }
    }
    @Override public void decayModels(Connection connection) throws SQLException {
        requireTransaction(connection);
        try (PreparedStatement select=connection.prepareStatement("SELECT bc.company_id,bc.model_price_minor,sl.issue_reference_price_minor,bc.lower_price_minor,bc.upper_price_minor FROM bluechip_companies bc JOIN stock_listings sl ON sl.company_id=bc.company_id"); PreparedStatement update=connection.prepareStatement("UPDATE bluechip_companies SET model_price_minor=? WHERE company_id=?")) { try(ResultSet rows=select.executeQuery()) { while(rows.next()) { long model=rows.getLong(2),reference=rows.getLong(3), step=Math.max(1,Math.abs(model-reference)/4); long next=model==reference?model:(model>reference?Math.max(reference,model-step):Math.min(reference,model+step)); next=Math.max(rows.getLong(4)+1,Math.min(rows.getLong(5)-1,next)); update.setLong(1,next);update.setString(2,rows.getString(1));update.addBatch(); } update.executeBatch(); } }
    }
    @Override public void closeCandle(Connection connection, CompanyId companyId, LocalDate day) throws SQLException {
        requireTransaction(connection); long fallback;
        try(PreparedStatement s=connection.prepareStatement("SELECT model_price_minor FROM bluechip_companies WHERE company_id=?")){s.setString(1,companyId.value().toString());try(ResultSet r=s.executeQuery()){if(!r.next())return;fallback=r.getLong(1);}}
        long open=fallback,high=fallback,low=fallback,close=fallback,volume=0; try(PreparedStatement s=connection.prepareStatement("SELECT price_minor,shares FROM stock_trades WHERE company_id=? AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at,id")){s.setString(1,companyId.value().toString());s.setString(2,day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString());s.setString(3,day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString());try(ResultSet r=s.executeQuery()){boolean any=false;while(r.next()){long price=r.getLong(1);if(!any){open=high=low=close=price;any=true;}else{close=price;high=Math.max(high,price);low=Math.min(low,price);}volume=Math.addExact(volume,r.getLong(2));}}}
        try(PreparedStatement s=connection.prepareStatement("INSERT INTO market_candles (company_id,trading_day,open_minor,high_minor,low_minor,close_minor,volume_shares) VALUES (?,?,?,?,?,?,?) ON CONFLICT(company_id,trading_day) DO UPDATE SET open_minor=excluded.open_minor,high_minor=excluded.high_minor,low_minor=excluded.low_minor,close_minor=excluded.close_minor,volume_shares=excluded.volume_shares")){s.setString(1,companyId.value().toString());s.setString(2,day.toString());s.setLong(3,open);s.setLong(4,high);s.setLong(5,low);s.setLong(6,close);s.setLong(7,volume);s.executeUpdate();}
    }
    @Override public List<cn.blockeco.exchange.domain.bluechip.BluechipEvent> recentEvents(int requested) { int limit=Math.max(1,Math.min(50,requested)); try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT id,scope,company_id,industry,headline,body,price_impact_bps,profit_impact_bps,starts_at,ends_at,state FROM bluechip_events ORDER BY starts_at DESC,id DESC LIMIT ?")){s.setInt(1,limit);try(ResultSet r=s.executeQuery()){java.util.ArrayList<cn.blockeco.exchange.domain.bluechip.BluechipEvent> result=new java.util.ArrayList<>();while(r.next())result.add(event(r));return List.copyOf(result);}}catch(SQLException e){throw new IllegalStateException("could not read bluechip events",e);} }
    @Override public Optional<Candle> candle(CompanyId companyId,LocalDate day) { try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT open_minor,high_minor,low_minor,close_minor,volume_shares FROM market_candles WHERE company_id=? AND trading_day=?")){s.setString(1,companyId.value().toString());s.setString(2,day.toString());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(new Candle(Money.ofMinor(r.getLong(1)),Money.ofMinor(r.getLong(2)),Money.ofMinor(r.getLong(3)),Money.ofMinor(r.getLong(4)),r.getLong(5))):Optional.empty();}}catch(SQLException e){throw new IllegalStateException("could not read market candle",e);} }
    @Override public List<BluechipCompany> dueCompanyEvents(Instant now) { return bluechips(" WHERE next_event_at <= ? ORDER BY next_event_at, company_id", now.toString()); }
    @Override public boolean marketEventDue(Instant now) { try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT starts_at FROM bluechip_events WHERE scope IN ('INDUSTRY','MARKET') ORDER BY starts_at DESC LIMIT 1");ResultSet r=s.executeQuery()){return !r.next() || !Instant.parse(r.getString(1)).plus(24,ChronoUnit.HOURS).isAfter(now);}catch(SQLException e){throw new IllegalStateException("could not inspect market event schedule",e);} }
    @Override public void scheduleNextCompanyEvent(Connection connection,CompanyId companyId,Instant next) throws SQLException { requireTransaction(connection);try(PreparedStatement s=connection.prepareStatement("UPDATE bluechip_companies SET next_event_at=? WHERE company_id=?")){s.setString(1,next.toString());s.setString(2,companyId.value().toString());s.executeUpdate();} }
    @Override public int profitExpectationBps(CompanyId companyId) { try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT profit_impact_bps FROM company_market_expectations WHERE company_id=?")){s.setString(1,companyId.value().toString());try(ResultSet r=s.executeQuery()){return r.next()?r.getInt(1):0;}}catch(SQLException e){throw new IllegalStateException("could not read company market expectation",e);} }
    @Override public Optional<MarketSchedule> marketSchedule() { try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT next_company_event_at,next_market_event_at FROM market_event_schedule WHERE singleton=1");ResultSet r=s.executeQuery()){return r.next()?Optional.of(new MarketSchedule(Instant.parse(r.getString(1)),Instant.parse(r.getString(2)))):Optional.empty();}catch(SQLException e){throw new IllegalStateException("could not read market schedule",e);} }
    @Override public void saveMarketSchedule(Connection connection,MarketSchedule schedule) throws SQLException { requireTransaction(connection);try(PreparedStatement s=connection.prepareStatement("UPDATE market_event_schedule SET next_company_event_at=?,next_market_event_at=? WHERE singleton=1")){s.setString(1,schedule.nextCompanyEventAt().toString());s.setString(2,schedule.nextMarketEventAt().toString());s.executeUpdate();} }
    @Override public void expireEvents(Connection connection,Instant now) throws SQLException { requireTransaction(connection);try(PreparedStatement s=connection.prepareStatement("UPDATE bluechip_events SET state='EXPIRED' WHERE state='ACTIVE' AND ends_at <= ?")){s.setString(1,now.toString());s.executeUpdate();} }

    @Override public List<DividendSettlement> settleDueDividendRuns(Connection connection, Instant now, long bluechipBaseProfitMinor) throws SQLException {
        requireTransaction(connection);
        List<DividendSettlement> settled = new java.util.ArrayList<>();
        try (PreparedStatement companies = connection.prepareStatement("""
                SELECT c.id, c.dividend_basis_points, sl.listed_at, bc.system_account_uuid, bc.industry,
                       bc.payout_bps, bc.next_dividend_at, f.cash_minor, f.reserved_minor, f.retained_earnings_minor
                FROM companies c JOIN stock_listings sl ON sl.company_id = c.id
                LEFT JOIN bluechip_companies bc ON bc.company_id = c.id
                LEFT JOIN company_cash_accounts f ON f.company_id = c.id
                ORDER BY c.id
                """)) {
            try (ResultSet rows = companies.executeQuery()) {
                while (rows.next()) {
                    CompanyId companyId = new CompanyId(UUID.fromString(rows.getString(1)));
                    String systemAccount = rows.getString(4);
                    Instant cycleAt = nextCycle(connection, companyId, systemAccount == null ? null : Instant.parse(rows.getString(7)), Instant.parse(rows.getString(3)));
                    if (cycleAt.isAfter(now) || !claimRun(connection, companyId, cycleAt, now)) continue;
                    long profit = systemAccount == null
                            ? rows.getLong(10)
                            : adjustedBluechipProfit(connection, companyId, rows.getString(5), now, bluechipBaseProfitMinor);
                    long source = systemAccount == null ? availableCompanyCash(rows.getLong(8), rows.getLong(9)) : fundCash(connection, companyId);
                    int payoutBps = systemAccount == null ? rows.getInt(2) : rows.getInt(6);
                    long distributable = profit <= 0 ? 0 : Math.min(source, multiplyBps(profit, payoutBps));
                    Distribution distribution = distributable == 0 ? Distribution.none() : distribute(connection, companyId, systemAccount, distributable);
                    if (distribution.total() > 0) debitSource(connection, companyId, systemAccount, distribution.total(), cycleAt, now);
                    completeRun(connection, companyId, cycleAt, distribution.total(), now);
                    if (systemAccount != null) scheduleNextDividend(connection, companyId, cycleAt.plus(15, ChronoUnit.DAYS));
                    writeReport(connection, companyId, cycleAt, profit, distribution, now);
                    settled.add(new DividendSettlement(companyId, Money.ofMinor(profit), Money.ofMinor(distribution.total()), distribution.paymentCount(), idempotencyKey(companyId, cycleAt)));
                }
            }
        }
        return List.copyOf(settled);
    }

    private static Instant nextCycle(Connection connection, CompanyId companyId, Instant bluechipNext, Instant listedAt) throws SQLException {
        if (bluechipNext != null) return bluechipNext;
        try (PreparedStatement statement = connection.prepareStatement("SELECT dividend_at FROM dividend_runs WHERE company_id = ? AND state = 'COMPLETED' ORDER BY dividend_at DESC LIMIT 1")) {
            statement.setString(1, companyId.value().toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Instant.parse(rows.getString(1)).plus(15, ChronoUnit.DAYS) : listedAt.plus(15, ChronoUnit.DAYS);
            }
        }
    }

    private static boolean claimRun(Connection connection, CompanyId companyId, Instant cycleAt, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO dividend_runs (company_id, dividend_at, state, total_payout_minor, created_at) VALUES (?, ?, 'PENDING', 0, ?) ON CONFLICT(company_id, dividend_at) DO NOTHING")) {
            statement.setString(1, companyId.value().toString()); statement.setString(2, cycleAt.toString()); statement.setString(3, now.toString());
            return statement.executeUpdate() == 1;
        }
    }

    private static long adjustedBluechipProfit(Connection connection, CompanyId companyId, String industry, Instant now, long baseProfit) throws SQLException {
        int impacts = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(profit_impact_bps), 0) FROM bluechip_events
                WHERE state = 'ACTIVE' AND starts_at <= ? AND ends_at > ?
                  AND (company_id = ? OR (scope = 'INDUSTRY' AND industry = ?) OR scope = 'MARKET')
                """)) {
            statement.setString(1, now.toString()); statement.setString(2, now.toString()); statement.setString(3, companyId.value().toString()); statement.setString(4, industry);
            try (ResultSet rows = statement.executeQuery()) { if (rows.next()) impacts = rows.getInt(1); }
        }
        return multiplyBps(baseProfit, 10_000 + impacts);
    }

    private static long availableCompanyCash(long cash, long reserved) { return Math.max(0, Math.subtractExact(cash, reserved)); }
    private static long multiplyBps(long amount, int bps) {
        try { return Math.floorDiv(Math.multiplyExact(amount, (long) bps), 10_000L); }
        catch (ArithmeticException exception) { throw new IllegalStateException("dividend amount exceeds Money range", exception); }
    }
    private static long fundCash(Connection connection, CompanyId companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(cash_delta_minor), 0) FROM bluechip_fund_audit WHERE company_id = ?")) {
            statement.setString(1, companyId.value().toString()); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Math.max(0, rows.getLong(1)) : 0; }
        }
    }

    private static Distribution distribute(Connection connection, CompanyId companyId, String excludedHolder, long distributable) throws SQLException {
        List<Holder> holders = new java.util.ArrayList<>(); long shares = 0;
        String sql = "SELECT holder_uuid, available_shares + reserved_shares FROM share_holdings WHERE company_id = ? AND available_shares + reserved_shares > 0" + (excludedHolder == null ? "" : " AND holder_uuid <> ?") + " ORDER BY holder_uuid";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, companyId.value().toString()); if (excludedHolder != null) statement.setString(2, excludedHolder);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { long holding = rows.getLong(2); holders.add(new Holder(UUID.fromString(rows.getString(1)), holding)); shares = Math.addExact(shares, holding); } }
        }
        if (shares == 0) return Distribution.none();
        long total = 0; int payments = 0;
        for (Holder holder : holders) {
            long credit = Math.floorDiv(Math.multiplyExact(distributable, holder.shares()), shares);
            if (credit == 0) continue;
            creditSecuritiesCash(connection, holder.id(), credit); total = Math.addExact(total, credit); payments++;
        }
        return new Distribution(total, payments);
    }

    private static void creditSecuritiesCash(Connection connection, UUID holder, long credit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO securities_cash_accounts (player_uuid, available_minor, reserved_minor) VALUES (?, ?, 0) ON CONFLICT(player_uuid) DO UPDATE SET available_minor = available_minor + excluded.available_minor")) {
            statement.setString(1, holder.toString()); statement.setLong(2, credit); statement.executeUpdate();
        }
    }
    private static void debitSource(Connection connection, CompanyId companyId, String systemAccount, long amount, Instant cycleAt, Instant now) throws SQLException {
        if (systemAccount == null) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE company_cash_accounts SET cash_minor = cash_minor - ?, retained_earnings_minor = retained_earnings_minor - ? WHERE company_id = ? AND cash_minor - reserved_minor >= ? AND retained_earnings_minor >= ?")) {
                statement.setLong(1, amount); statement.setLong(2, amount); statement.setString(3, companyId.value().toString()); statement.setLong(4, amount); statement.setLong(5, amount);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("company dividend source is no longer available");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = available_minor - ? WHERE player_uuid = ? AND available_minor >= ?")) {
            statement.setLong(1, amount); statement.setString(2, systemAccount); statement.setLong(3, amount);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("bluechip fund source is no longer available");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO bluechip_fund_audit (id, company_id, operation, cash_delta_minor, shares_delta, occurred_at) VALUES (?, ?, 'DIVIDEND_PAID', ?, 0, ?)")) {
            statement.setString(1, UUID.nameUUIDFromBytes(("dividend-fund:" + idempotencyKey(companyId, cycleAt)).getBytes(StandardCharsets.UTF_8)).toString()); statement.setString(2, companyId.value().toString()); statement.setLong(3, -amount); statement.setString(4, now.toString()); statement.executeUpdate();
        }
    }
    private static void completeRun(Connection connection, CompanyId companyId, Instant cycleAt, long payout, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE dividend_runs SET state = 'COMPLETED', total_payout_minor = ?, completed_at = ? WHERE company_id = ? AND dividend_at = ? AND state = 'PENDING'")) {
            statement.setLong(1, payout); statement.setString(2, now.toString()); statement.setString(3, companyId.value().toString()); statement.setString(4, cycleAt.toString());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("dividend run state conflict");
        }
    }
    private static void scheduleNextDividend(Connection connection, CompanyId companyId, Instant next) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE bluechip_companies SET next_dividend_at = ? WHERE company_id = ?")) {
            statement.setString(1, next.toString()); statement.setString(2, companyId.value().toString()); statement.executeUpdate();
        }
    }
    private static void writeReport(Connection connection, CompanyId companyId, Instant cycleAt, long profit, Distribution distribution, Instant now) throws SQLException {
        boolean paid = distribution.total() > 0;
        String key = idempotencyKey(companyId, cycleAt); String type = paid ? "DIVIDEND_PAID" : "NO_DIVIDEND";
        String body = (paid ? "DIVIDEND_PAID:" : "NO_DIVIDEND:") + " profitMinor=" + profit + ", distributedMinor=" + distribution.total() + ", payments=" + distribution.paymentCount();
        try (PreparedStatement announcement = connection.prepareStatement("INSERT INTO company_announcements (id, company_id, body, created_at) VALUES (?, ?, ?, ?)"); PreparedStatement audit = connection.prepareStatement("INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at) VALUES (?, ?, NULL, ?, ?, ?)")) {
            announcement.setString(1, UUID.nameUUIDFromBytes(("dividend-announcement:" + key).getBytes(StandardCharsets.UTF_8)).toString()); announcement.setString(2, companyId.value().toString()); announcement.setString(3, body); announcement.setString(4, now.toString()); announcement.executeUpdate();
            audit.setString(1, UUID.nameUUIDFromBytes(("dividend-audit:" + key).getBytes(StandardCharsets.UTF_8)).toString()); audit.setString(2, companyId.value().toString()); audit.setString(3, type); audit.setString(4, "{\"profitMinor\":" + profit + ",\"distributedMinor\":" + distribution.total() + ",\"paymentCount\":" + distribution.paymentCount() + "}"); audit.setString(5, now.toString()); audit.executeUpdate();
        }
    }
    private static String idempotencyKey(CompanyId companyId, Instant cycleAt) { return companyId.value() + ":" + cycleAt; }
    private record Holder(UUID id, long shares) { }
    private record Distribution(long total, int paymentCount) { static Distribution none() { return new Distribution(0, 0); } }

    private Optional<BluechipCompany> findOne(String sql, String value) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(map(rows)) : Optional.empty(); }
        } catch (SQLException exception) { throw new IllegalStateException("could not read bluechip company", exception); }
    }
    private List<BluechipCompany> bluechips(String suffix,String value) { try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(SELECT+suffix)){s.setString(1,value);try(ResultSet r=s.executeQuery()){java.util.ArrayList<BluechipCompany> result=new java.util.ArrayList<>();while(r.next())result.add(map(r));return List.copyOf(result);}}catch(SQLException e){throw new IllegalStateException("could not read due bluechip events",e);} }
    private static BluechipCompany map(ResultSet row) throws SQLException {
        CompanyId companyId = new CompanyId(UUID.fromString(row.getString("company_id")));
        StockListing listing = new StockListing(companyId, row.getString("stock_code"), Money.ofMinor(row.getLong("issue_reference_price_minor")), row.getLong("issued_shares"), Instant.parse(row.getString("listed_at")));
        return new BluechipCompany(companyId, listing, row.getString("industry"), UUID.fromString(row.getString("system_account_uuid")), listing.issueReferencePrice(), Money.ofMinor(row.getLong("model_price_minor")), Money.ofMinor(row.getLong("lower_price_minor")), Money.ofMinor(row.getLong("upper_price_minor")), row.getInt("spread_bps"), row.getLong("available_shares"), Money.ofMinor(row.getLong("fund_cash_minor")));
    }
    private static BluechipMetadata mapMetadata(ResultSet row) throws SQLException {
        CompanyId companyId = new CompanyId(UUID.fromString(row.getString("company_id")));
        StockListing listing = new StockListing(companyId, row.getString("stock_code"), Money.ofMinor(row.getLong("issue_reference_price_minor")), row.getLong("issued_shares"), Instant.parse(row.getString("listed_at")));
        return new BluechipMetadata(companyId, listing, row.getString("industry"), UUID.fromString(row.getString("system_account_uuid")), Money.ofMinor(row.getLong("model_price_minor")), Money.ofMinor(row.getLong("lower_price_minor")), Money.ofMinor(row.getLong("upper_price_minor")), row.getInt("spread_bps"), row.getInt("event_sensitivity_bps"), row.getInt("payout_bps"));
    }
    private static cn.blockeco.exchange.domain.bluechip.BluechipEvent event(ResultSet r)throws SQLException{return new cn.blockeco.exchange.domain.bluechip.BluechipEvent(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getInt(7),r.getInt(8),Instant.parse(r.getString(9)),Instant.parse(r.getString(10)),r.getString(11));}
    private static void insertHolding(Connection connection, BluechipSeed seed) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO share_holdings (company_id, holder_uuid, available_shares, reserved_shares) VALUES (?, ?, ?, 0)")) {
            statement.setString(1, seed.companyId().value().toString()); statement.setString(2, seed.systemAccountId().toString()); statement.setLong(3, seed.fundShares()); statement.executeUpdate();
        }
    }
    private static UUID deterministicId(String prefix, CompanyId companyId) { return UUID.nameUUIDFromBytes((prefix + companyId.value()).getBytes(StandardCharsets.UTF_8)); }
    private static void requireTransaction(Connection connection) throws SQLException { if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required"); }
}
