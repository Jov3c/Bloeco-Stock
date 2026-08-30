package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.CompanyGovernanceAction;
import cn.blockeco.exchange.domain.governance.CompanyPayoutOperation;
import cn.blockeco.exchange.domain.governance.GovernanceActionState;
import cn.blockeco.exchange.domain.governance.GovernanceActionType;
import cn.blockeco.exchange.domain.governance.OrderReleaseProgress;
import cn.blockeco.exchange.domain.governance.PayoutOperationState;
import cn.blockeco.exchange.domain.governance.CompanyExitSnapshot;
import cn.blockeco.exchange.domain.governance.CompanyLiquidationClaim;
import cn.blockeco.exchange.domain.governance.LiquidationClaimState;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.ports.CompanyExitRepository;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** SQL persistence for announced exit actions and recoverable external company payouts. */
public final class SqlCompanyExitRepository implements CompanyExitRepository {
    private final DataSource dataSource;

    public SqlCompanyExitRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void createAction(Connection connection, CompanyGovernanceAction action, String announcementBody, Instant recordedAt) throws SQLException {
        requireTransaction(connection);
        Objects.requireNonNull(action, "action"); Objects.requireNonNull(announcementBody, "announcementBody"); Objects.requireNonNull(recordedAt, "recordedAt");
        requirePlayerCompany(connection, action.companyId());
        String payload = actionPayload(action);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_governance_actions
                (id,company_id,actor_uuid,action_type,amount_minor,price_per_share_minor,announced_at,executable_at,state,correlation_key,payload_json,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, action.id().toString()); statement.setString(2, action.companyId().value().toString()); statement.setString(3, action.actorUuid().toString());
            statement.setString(4, action.type().name()); statement.setLong(5, action.amountMinor()); statement.setLong(6, action.pricePerShareMinor());
            statement.setString(7, action.announcedAt().toString()); statement.setString(8, action.executableAt().toString()); statement.setString(9, action.state().name());
            statement.setString(10, action.correlationKey()); statement.setString(11, payload); statement.setString(12, recordedAt.toString()); statement.executeUpdate();
        }
        String factId = action.id() + ":GOVERNANCE_ANNOUNCED";
        try (PreparedStatement announcement = connection.prepareStatement("INSERT INTO company_announcements (id,company_id,offering_id,body,created_at) VALUES (?,?,NULL,?,?)");
             PreparedStatement audit = connection.prepareStatement("INSERT INTO audit_events (event_id,company_id,actor_uuid,event_type,payload_json,occurred_at) VALUES (?,?,?,?,?,?)")) {
            announcement.setString(1, factId); announcement.setString(2, action.companyId().value().toString()); announcement.setString(3, announcementBody); announcement.setString(4, recordedAt.toString()); announcement.executeUpdate();
            audit.setString(1, factId); audit.setString(2, action.companyId().value().toString()); audit.setString(3, action.actorUuid().toString()); audit.setString(4, "COMPANY_GOVERNANCE_ANNOUNCED"); audit.setString(5, payload); audit.setString(6, recordedAt.toString()); audit.executeUpdate();
        }
    }

    @Override
    public boolean transitionAction(Connection connection, UUID actionId, GovernanceActionState expected, GovernanceActionState next, Instant updatedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(next, "next"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (!legalActionTransition(expected, next)) throw new IllegalArgumentException("illegal company governance transition: " + expected + " -> " + next);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE company_governance_actions SET state=?,updated_at=? WHERE id=? AND state=?")) {
            statement.setString(1, next.name()); statement.setString(2, updatedAt.toString()); statement.setString(3, actionId.toString()); statement.setString(4, expected.name()); return statement.executeUpdate() == 1;
        }
    }

    @Override
    public Optional<CompanyGovernanceAction> findAction(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM company_governance_actions WHERE id=?")) {
            statement.setString(1, actionId.toString()); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(action(rows)) : Optional.empty(); }
        } catch (SQLException exception) { throw new IllegalStateException("could not read governance action", exception); }
    }

    @Override public List<CompanyGovernanceAction> activeBuybacks(CompanyId companyId) {
        Objects.requireNonNull(companyId, "companyId");
        try (Connection connection=dataSource.getConnection(); PreparedStatement statement=connection.prepareStatement("SELECT * FROM company_governance_actions WHERE company_id=? AND action_type='BUYBACK' AND state IN ('ANNOUNCED','EXECUTION_READY','EXECUTING') ORDER BY announced_at,id")) {
            statement.setString(1,companyId.value().toString()); try(ResultSet rows=statement.executeQuery()){List<CompanyGovernanceAction> actions=new ArrayList<>();while(rows.next())actions.add(action(rows));return List.copyOf(actions);}
        } catch(SQLException exception) { throw new IllegalStateException("could not read active buybacks",exception); }
    }

    @Override
    public void createPayout(Connection connection, CompanyPayoutOperation payout) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(payout, "payout");
        if (payout.state() != PayoutOperationState.PREPARED) throw new IllegalArgumentException("company payout must start prepared");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_payout_operations
                (id,company_id,governance_action_id,recipient_uuid,amount_minor,correlation_key,state,detail,created_at,updated_at)
                SELECT ?,?,?,?,?,?,?,?,?,?
                WHERE EXISTS (SELECT 1 FROM company_governance_actions WHERE id=? AND company_id=? AND action_type='FOUNDER_CASH_OUT' AND state='EXECUTING' AND actor_uuid=?)
                """)) {
            statement.setString(1, payout.id().toString()); statement.setString(2, payout.companyId().value().toString()); statement.setString(3, payout.governanceActionId().toString());
            statement.setString(4, payout.recipientUuid().toString()); statement.setLong(5, payout.amountMinor()); statement.setString(6, payout.correlationKey()); statement.setString(7, payout.state().name());
            statement.setString(8, payout.detail()); statement.setString(9, payout.createdAt().toString()); statement.setString(10, payout.updatedAt().toString());
            statement.setString(11, payout.governanceActionId().toString()); statement.setString(12, payout.companyId().value().toString()); statement.setString(13, payout.recipientUuid().toString());
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("payout action and company do not match");
        }
    }

    @Override
    public boolean transitionPayout(Connection connection, UUID payoutId, PayoutOperationState expected, PayoutOperationState next, String detail, Instant updatedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(payoutId, "payoutId"); Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(next, "next"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (!legalPayoutTransition(expected, next)) throw new IllegalArgumentException("illegal company payout transition: " + expected + " -> " + next);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE company_payout_operations SET state=?,detail=?,updated_at=? WHERE id=? AND state=?")) {
            statement.setString(1, next.name()); statement.setString(2, detail); statement.setString(3, updatedAt.toString()); statement.setString(4, payoutId.toString()); statement.setString(5, expected.name()); return statement.executeUpdate() == 1;
        }
    }

    @Override public boolean reserveCompanyCash(Connection connection, CompanyId companyId, long amountMinor) throws SQLException {
        requireTransaction(connection); requirePositive(amountMinor); Objects.requireNonNull(companyId, "companyId");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE company_cash_accounts SET reserved_minor=reserved_minor+? WHERE company_id=? AND cash_minor-reserved_minor>=? AND reserved_minor<=?")) {
            statement.setLong(1, amountMinor); statement.setString(2, companyId.value().toString()); statement.setLong(3, amountMinor); statement.setLong(4, Math.subtractExact(Long.MAX_VALUE, amountMinor)); return statement.executeUpdate() == 1;
        }
    }

    @Override public boolean reserveFounderCashOut(Connection connection, CompanyId companyId, long amountMinor) throws SQLException {
        requireTransaction(connection); requirePositive(amountMinor); Objects.requireNonNull(companyId, "companyId");
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE company_cash_accounts SET reserved_minor=reserved_minor+?
                WHERE company_id=?
                  AND cash_minor-reserved_minor-paid_in_capital_minor
                      -CASE WHEN retained_earnings_minor>0 THEN retained_earnings_minor ELSE 0 END >= ?
                  AND reserved_minor<=?
                """)) {
            statement.setLong(1, amountMinor); statement.setString(2, companyId.value().toString());
            statement.setLong(3, amountMinor); statement.setLong(4, Math.subtractExact(Long.MAX_VALUE, amountMinor));
            return statement.executeUpdate() == 1;
        }
    }

    @Override public boolean releaseCompanyCash(Connection connection, CompanyId companyId, long amountMinor) throws SQLException {
        requireTransaction(connection); requirePositive(amountMinor); Objects.requireNonNull(companyId, "companyId");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE company_cash_accounts SET reserved_minor=reserved_minor-? WHERE company_id=? AND reserved_minor>=?")) {
            statement.setLong(1, amountMinor); statement.setString(2, companyId.value().toString()); statement.setLong(3, amountMinor); return statement.executeUpdate() == 1;
        }
    }

    @Override public boolean completePayout(Connection connection, UUID payoutId, Instant completedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(payoutId, "payoutId"); Objects.requireNonNull(completedAt, "completedAt");
        CompanyPayoutOperation payout = payoutForUpdate(connection, payoutId);
        if (payout.state() == PayoutOperationState.COMPLETED) return false;
        if (payout.state() != PayoutOperationState.EXTERNAL_DEBIT_CONFIRMED) throw new IllegalStateException("payout cannot complete from " + payout.state());
        try (PreparedStatement debit = connection.prepareStatement("UPDATE company_cash_accounts SET cash_minor=cash_minor-?,reserved_minor=reserved_minor-? WHERE company_id=? AND cash_minor>=? AND reserved_minor>=?")) {
            debit.setLong(1, payout.amountMinor()); debit.setLong(2, payout.amountMinor()); debit.setString(3, payout.companyId().value().toString()); debit.setLong(4, payout.amountMinor()); debit.setLong(5, payout.amountMinor());
            if (debit.executeUpdate() != 1) throw new IllegalStateException("company payout reserve missing");
        }
        if (!transitionPayout(connection, payoutId, PayoutOperationState.EXTERNAL_DEBIT_CONFIRMED, PayoutOperationState.COMPLETED, "Vault deposit confirmed", completedAt)) throw new IllegalStateException("payout state conflict");
        UUID ledgerId = ledger(connection, payout.companyId(), -payout.amountMinor(), completedAt);
        linkPayoutLedger(connection, payoutId, ledgerId);
        audit(connection, payout.companyId(), payout.recipientUuid(), "COMPANY_PAYOUT_COMPLETED", "{\"payoutId\":\"" + payoutId + "\",\"amountMinor\":" + payout.amountMinor() + "}", completedAt);
        return true;
    }

    @Override public boolean acceptBuyback(Connection connection, UUID actionId, CompanyId companyId, UUID shareholderId, long shares, String correlationKey, SecuritiesCashRepository securitiesCash, Instant acceptedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(companyId, "companyId"); Objects.requireNonNull(shareholderId, "shareholderId"); Objects.requireNonNull(correlationKey, "correlationKey"); Objects.requireNonNull(securitiesCash, "securitiesCash"); Objects.requireNonNull(acceptedAt, "acceptedAt"); requirePositive(shares);
        if (correlationKey.isBlank()) throw new IllegalArgumentException("correlation key is required");
        try (PreparedStatement existing = connection.prepareStatement("SELECT 1 FROM company_buyback_acceptances WHERE governance_action_id=? AND shareholder_uuid=? AND correlation_key=?")) {
            existing.setString(1, actionId.toString()); existing.setString(2, shareholderId.toString()); existing.setString(3, correlationKey); try (ResultSet rows = existing.executeQuery()) { if (rows.next()) return false; }
        }
        long budget;
        long pricePerShare;
        try (PreparedStatement action = connection.prepareStatement("SELECT amount_minor,price_per_share_minor FROM company_governance_actions WHERE id=? AND company_id=? AND action_type='BUYBACK' AND state='EXECUTING'")) {
            action.setString(1, actionId.toString()); action.setString(2, companyId.value().toString()); try (ResultSet rows = action.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("buyback is not executing"); budget=rows.getLong(1); pricePerShare=rows.getLong(2);
            }
        }
        long amountMinor = Math.multiplyExact(shares, pricePerShare);
        try (PreparedStatement spent = connection.prepareStatement("SELECT COALESCE(SUM(amount_minor),0) FROM company_buyback_acceptances WHERE governance_action_id=?")) {
            spent.setString(1, actionId.toString()); try (ResultSet rows = spent.executeQuery()) { rows.next(); if (amountMinor > Math.subtractExact(budget, rows.getLong(1))) throw new IllegalStateException("buyback action budget is exhausted"); }
        }
        try (PreparedStatement holding = connection.prepareStatement("UPDATE share_holdings SET available_shares=available_shares-? WHERE company_id=? AND holder_uuid=? AND available_shares>=?")) {
            holding.setLong(1, shares); holding.setString(2, companyId.value().toString()); holding.setString(3, shareholderId.toString()); holding.setLong(4, shares); if (holding.executeUpdate() != 1) throw new IllegalStateException("shareholder did not voluntarily offer sufficient available shares");
        }
        try (PreparedStatement cash = connection.prepareStatement("UPDATE company_cash_accounts SET cash_minor=cash_minor-?,reserved_minor=reserved_minor-? WHERE company_id=? AND cash_minor>=? AND reserved_minor>=?")) {
            cash.setLong(1, amountMinor); cash.setLong(2, amountMinor); cash.setString(3, companyId.value().toString()); cash.setLong(4, amountMinor); cash.setLong(5, amountMinor); if (cash.executeUpdate() != 1) throw new IllegalStateException("company buyback reserve missing");
        }
        creditTreasuryHolding(connection, companyId, shares);
        securitiesCash.creditAvailable(connection, shareholderId, Money.ofMinor(amountMinor), acceptedAt);
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO company_buyback_acceptances (governance_action_id,shareholder_uuid,shares,amount_minor,correlation_key,accepted_at) VALUES (?,?,?,?,?,?)")) {
            insert.setString(1, actionId.toString()); insert.setString(2, shareholderId.toString()); insert.setLong(3, shares); insert.setLong(4, amountMinor); insert.setString(5, correlationKey); insert.setString(6, acceptedAt.toString()); insert.executeUpdate();
        }
        ledger(connection, companyId, -amountMinor, acceptedAt);
        audit(connection, companyId, shareholderId, "COMPANY_BUYBACK_ACCEPTED", "{\"actionId\":\"" + actionId + "\",\"correlationKey\":\"" + correlationKey + "\",\"shares\":" + shares + ",\"amountMinor\":" + amountMinor + "}", acceptedAt);
        return true;
    }

    @Override
    public List<CompanyPayoutOperation> recoverablePayouts(int limit) {
        int bound = Math.max(1, Math.min(100, limit));
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM company_payout_operations WHERE state IN ('PREPARED','EXTERNAL_DEBIT_CONFIRMED','AMBIGUOUS') ORDER BY updated_at,id LIMIT ?")) {
            statement.setInt(1, bound); try (ResultSet rows = statement.executeQuery()) { List<CompanyPayoutOperation> payouts = new ArrayList<>(); while (rows.next()) payouts.add(payout(rows)); return List.copyOf(payouts); }
        } catch (SQLException exception) { throw new IllegalStateException("could not read recoverable company payouts", exception); }
    }

    @Override
    public List<UUID> activeOrderIds(Connection connection, CompanyId companyId, UUID afterOrderId, int limit) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(companyId, "companyId"); int bound = Math.max(1, Math.min(200, limit));
        String sql = afterOrderId == null
                ? "SELECT id FROM stock_orders WHERE company_id=? AND state IN ('OPEN','PARTIALLY_FILLED') ORDER BY id LIMIT ?"
                : "SELECT id FROM stock_orders WHERE company_id=? AND state IN ('OPEN','PARTIALLY_FILLED') AND id>? ORDER BY id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, companyId.value().toString()); if (afterOrderId == null) statement.setInt(2, bound); else { statement.setString(2, afterOrderId.toString()); statement.setInt(3, bound); }
            try (ResultSet rows = statement.executeQuery()) { List<UUID> ids = new ArrayList<>(); while (rows.next()) ids.add(UUID.fromString(rows.getString(1))); return List.copyOf(ids); }
        }
    }

    @Override
    public void recordOrderReleaseProgress(Connection connection, UUID actionId, UUID lastReleasedOrderId, long releasedOrders, boolean complete, Instant updatedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (releasedOrders < 0) throw new IllegalArgumentException("released order count cannot be negative");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_order_release_progress (governance_action_id,last_released_order_id,released_orders,complete,updated_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT(governance_action_id) DO UPDATE SET
                  last_released_order_id=excluded.last_released_order_id,
                  released_orders=excluded.released_orders,
                  complete=excluded.complete,
                  updated_at=excluded.updated_at
                WHERE company_order_release_progress.released_orders <= excluded.released_orders
                  AND company_order_release_progress.complete = 0
                """)) {
            statement.setString(1, actionId.toString()); statement.setString(2, lastReleasedOrderId == null ? null : lastReleasedOrderId.toString()); statement.setLong(3, releasedOrders); statement.setInt(4, complete ? 1 : 0); statement.setString(5, updatedAt.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<OrderReleaseProgress> orderReleaseProgress(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        try (Connection connection = dataSource.getConnection()) { return readOrderReleaseProgress(connection, actionId); }
        catch (SQLException exception) { throw new IllegalStateException("could not read order release progress", exception); }
    }

    @Override public Optional<OrderReleaseProgress> orderReleaseProgress(Connection connection, UUID actionId) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId,"actionId");
        return readOrderReleaseProgress(connection, actionId);
    }

    private static Optional<OrderReleaseProgress> readOrderReleaseProgress(Connection connection, UUID actionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT last_released_order_id,released_orders,complete,updated_at FROM company_order_release_progress WHERE governance_action_id=?")) {
            statement.setString(1, actionId.toString()); try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty(); String last = rows.getString(1);
                return Optional.of(new OrderReleaseProgress(actionId, Optional.ofNullable(last).map(UUID::fromString), rows.getLong(2), rows.getInt(3) != 0, Instant.parse(rows.getString(4))));
            }
        }
    }

    @Override
    public boolean transitionCompanyStatus(Connection connection, CompanyId companyId, CompanyStatus expected, CompanyStatus next) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(companyId, "companyId");
        if (!((expected == CompanyStatus.LISTED && next == CompanyStatus.DELISTING)
                || (expected == CompanyStatus.DELISTING && next == CompanyStatus.LIQUIDATING)
                || (expected == CompanyStatus.LIQUIDATING && next == CompanyStatus.DELISTED))) {
            throw new IllegalArgumentException("illegal company exit status transition: " + expected + " -> " + next);
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE companies SET status=? WHERE id=? AND status=?")) {
            statement.setString(1, next.name()); statement.setString(2, companyId.value().toString()); statement.setString(3, expected.name());
            return statement.executeUpdate() == 1;
        }
    }

    @Override public boolean hasCompanyStatus(Connection connection, CompanyId companyId, CompanyStatus status) throws SQLException {
        requireTransaction(connection); try (PreparedStatement statement=connection.prepareStatement("SELECT 1 FROM companies WHERE id=? AND status=?")) { statement.setString(1,companyId.value().toString());statement.setString(2,status.name());try(ResultSet rows=statement.executeQuery()){return rows.next();} }
    }

    @Override
    public List<CompanyExitSnapshot> createExitSnapshots(Connection connection, UUID actionId, CompanyId companyId, Instant snapshottedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(companyId, "companyId"); Objects.requireNonNull(snapshottedAt, "snapshottedAt");
        if (activeOrderIds(connection, companyId, null, 1).size() != 0) throw new IllegalStateException("active orders must be released before snapshot");
        if (exitSnapshots(connection, actionId).isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO company_exit_snapshots (governance_action_id,company_id,holder_uuid,available_shares,reserved_shares,snapshotted_at)
                    SELECT ?,company_id,holder_uuid,available_shares,reserved_shares,?
                    FROM share_holdings WHERE company_id=? AND holder_uuid<>? AND available_shares+reserved_shares>0
                    ORDER BY holder_uuid
                    """)) {
                statement.setString(1, actionId.toString()); statement.setString(2, snapshottedAt.toString());
                statement.setString(3, companyId.value().toString()); statement.setString(4, companyId.value().toString()); statement.executeUpdate();
            }
        }
        return exitSnapshots(connection, actionId);
    }

    @Override public List<CompanyExitSnapshot> exitSnapshots(UUID actionId) { try (Connection c=dataSource.getConnection()) { return exitSnapshots(c, actionId); } catch (SQLException e) { throw new IllegalStateException("could not read exit snapshots", e); } }

    @Override
    public List<CompanyLiquidationClaim> createLiquidationClaims(Connection connection, UUID actionId, long pricePerShareMinor, Instant createdAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(createdAt, "createdAt"); requirePositive(pricePerShareMinor);
        List<CompanyLiquidationClaim> existing = claims(connection, actionId); if (!existing.isEmpty()) return existing;
        CompanyId company = actionCompany(connection, actionId);
        long companyRemaining = companyCash(connection, company); long fundRemaining = compensationFund(connection);
        for (CompanyExitSnapshot snapshot : exitSnapshots(connection, actionId)) {
            long entitlement = Math.multiplyExact(snapshot.totalShares(), pricePerShareMinor);
            long companyPart = Math.min(entitlement, companyRemaining); companyRemaining = Math.subtractExact(companyRemaining, companyPart);
            long fundPart = Math.min(Math.subtractExact(entitlement, companyPart), fundRemaining); fundRemaining = Math.subtractExact(fundRemaining, fundPart);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO company_liquidation_claims (governance_action_id,holder_uuid,shares,entitlement_minor,company_contribution_minor,fund_contribution_minor,state,created_at,updated_at) VALUES (?,?,?,?,?,?, 'PENDING',?,?)")) {
                insert.setString(1,actionId.toString()); insert.setString(2,snapshot.holderUuid().toString()); insert.setLong(3,snapshot.totalShares()); insert.setLong(4,Math.addExact(companyPart,fundPart)); insert.setLong(5,companyPart); insert.setLong(6,fundPart); insert.setString(7,createdAt.toString()); insert.setString(8,createdAt.toString()); insert.executeUpdate();
            }
        }
        return claims(connection, actionId);
    }

    @Override public List<CompanyLiquidationClaim> liquidationClaims(UUID actionId) { try (Connection c=dataSource.getConnection()) { return claims(c, actionId); } catch (SQLException e) { throw new IllegalStateException("could not read liquidation claims", e); } }
    @Override public List<CompanyLiquidationClaim> liquidationClaims(Connection connection, UUID actionId) throws SQLException { requireTransaction(connection); return claims(connection, actionId); }

    @Override
    public boolean creditLiquidationClaim(Connection connection, UUID actionId, UUID holderId, SecuritiesCashRepository securitiesCash, Instant creditedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId,"actionId"); Objects.requireNonNull(holderId,"holderId"); Objects.requireNonNull(securitiesCash,"securitiesCash"); Objects.requireNonNull(creditedAt,"creditedAt");
        CompanyLiquidationClaim claim = claim(connection, actionId, holderId);
        if (claim.state() == LiquidationClaimState.CREDITED) return false;
        if (claim.state() != LiquidationClaimState.PENDING) throw new IllegalStateException("claim is not pending");
        CompanyId company = actionCompany(connection, actionId);
        if (claim.companyContributionMinor() > 0) debitCompanyCash(connection, company, claim.companyContributionMinor(), creditedAt);
        if (claim.fundContributionMinor() > 0) debitCompensationFund(connection, claim.fundContributionMinor(), creditedAt);
        if (claim.entitlementMinor() > 0) securitiesCash.creditAvailable(connection, holderId, Money.ofMinor(claim.entitlementMinor()), creditedAt);
        try (PreparedStatement update=connection.prepareStatement("UPDATE company_liquidation_claims SET state='CREDITED',updated_at=? WHERE governance_action_id=? AND holder_uuid=? AND state='PENDING'")) {
            update.setString(1,creditedAt.toString());update.setString(2,actionId.toString());update.setString(3,holderId.toString());if(update.executeUpdate()!=1)throw new IllegalStateException("liquidation claim state conflict");
        }
        audit(connection, company, holderId, "COMPANY_LIQUIDATION_CREDITED", "{\"actionId\":\""+actionId+"\",\"amountMinor\":"+claim.entitlementMinor()+"}", creditedAt);
        return true;
    }

    private static List<CompanyExitSnapshot> exitSnapshots(Connection connection, UUID actionId) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement("SELECT governance_action_id,company_id,holder_uuid,available_shares,reserved_shares,snapshotted_at FROM company_exit_snapshots WHERE governance_action_id=? ORDER BY holder_uuid")) { statement.setString(1,actionId.toString()); try(ResultSet rows=statement.executeQuery()){ List<CompanyExitSnapshot> out=new ArrayList<>(); while(rows.next())out.add(new CompanyExitSnapshot(UUID.fromString(rows.getString(1)),new CompanyId(UUID.fromString(rows.getString(2))),UUID.fromString(rows.getString(3)),rows.getLong(4),rows.getLong(5),Instant.parse(rows.getString(6)))); return List.copyOf(out); } }
    }
    private static List<CompanyLiquidationClaim> claims(Connection connection, UUID actionId) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement("SELECT * FROM company_liquidation_claims WHERE governance_action_id=? ORDER BY holder_uuid")) { statement.setString(1,actionId.toString()); try(ResultSet rows=statement.executeQuery()){ List<CompanyLiquidationClaim> out=new ArrayList<>();while(rows.next())out.add(new CompanyLiquidationClaim(UUID.fromString(rows.getString("governance_action_id")),UUID.fromString(rows.getString("holder_uuid")),rows.getLong("shares"),rows.getLong("entitlement_minor"),rows.getLong("company_contribution_minor"),rows.getLong("fund_contribution_minor"),LiquidationClaimState.valueOf(rows.getString("state")),Instant.parse(rows.getString("created_at")),Instant.parse(rows.getString("updated_at"))));return List.copyOf(out);}}
    }
    private static CompanyLiquidationClaim claim(Connection connection, UUID actionId, UUID holderId) throws SQLException { return claims(connection,actionId).stream().filter(c->c.holderUuid().equals(holderId)).findFirst().orElseThrow(()->new IllegalArgumentException("liquidation claim missing")); }
    private static CompanyId actionCompany(Connection connection, UUID actionId) throws SQLException { try(PreparedStatement statement=connection.prepareStatement("SELECT company_id FROM company_governance_actions WHERE id=? AND action_type IN ('VOLUNTARY_DELIST','FORCED_DELIST')")){statement.setString(1,actionId.toString());try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw new IllegalArgumentException("delisting action missing");return new CompanyId(UUID.fromString(rows.getString(1)));}} }
    private static long companyCash(Connection connection, CompanyId company) throws SQLException {try(PreparedStatement statement=connection.prepareStatement("SELECT cash_minor FROM company_cash_accounts WHERE company_id=?")){statement.setString(1,company.value().toString());try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw new IllegalStateException("company cash account missing");return rows.getLong(1);}}}
    private static long compensationFund(Connection connection) throws SQLException {try(PreparedStatement statement=connection.prepareStatement("SELECT balance_minor FROM compensation_fund WHERE singleton=1");ResultSet rows=statement.executeQuery()){if(!rows.next())throw new IllegalStateException("compensation fund missing");return rows.getLong(1);}}
    private static void debitCompanyCash(Connection connection, CompanyId company,long amount,Instant at)throws SQLException {try(PreparedStatement statement=connection.prepareStatement("UPDATE company_cash_accounts SET cash_minor=cash_minor-? WHERE company_id=? AND cash_minor>=?")){statement.setLong(1,amount);statement.setString(2,company.value().toString());statement.setLong(3,amount);if(statement.executeUpdate()!=1)throw new IllegalStateException("company liquidation cash changed");}ledger(connection,company,-amount,at);}
    private static void debitCompensationFund(Connection connection,long amount,Instant at)throws SQLException {try(PreparedStatement statement=connection.prepareStatement("UPDATE compensation_fund SET balance_minor=balance_minor-? WHERE singleton=1 AND balance_minor>=?")){statement.setLong(1,amount);statement.setLong(2,amount);if(statement.executeUpdate()!=1)throw new IllegalStateException("compensation fund changed");}try(PreparedStatement statement=connection.prepareStatement("INSERT INTO escrow_ledger_entries (id,liability_kind,company_id,player_uuid,amount_minor,operation_id,trade_id,occurred_at) VALUES (?,'COMPENSATION_FUND',NULL,NULL,?,NULL,NULL,?)")){statement.setString(1,UUID.randomUUID().toString());statement.setLong(2,-amount);statement.setString(3,at.toString());statement.executeUpdate();}}

    private static CompanyGovernanceAction action(ResultSet row) throws SQLException {
        return new CompanyGovernanceAction(UUID.fromString(row.getString("id")), new CompanyId(UUID.fromString(row.getString("company_id"))), UUID.fromString(row.getString("actor_uuid")),
                GovernanceActionType.valueOf(row.getString("action_type")), row.getLong("amount_minor"), row.getLong("price_per_share_minor"), Instant.parse(row.getString("announced_at")),
                Instant.parse(row.getString("executable_at")), GovernanceActionState.valueOf(row.getString("state")), row.getString("correlation_key"));
    }

    private static CompanyPayoutOperation payout(ResultSet row) throws SQLException {
        return new CompanyPayoutOperation(UUID.fromString(row.getString("id")), new CompanyId(UUID.fromString(row.getString("company_id"))), UUID.fromString(row.getString("governance_action_id")),
                UUID.fromString(row.getString("recipient_uuid")), row.getLong("amount_minor"), row.getString("correlation_key"), PayoutOperationState.valueOf(row.getString("state")),
                Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")), row.getString("detail"));
    }

    private static void requirePlayerCompany(Connection connection, CompanyId companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM companies c WHERE c.id=? AND NOT EXISTS (SELECT 1 FROM bluechip_companies b WHERE b.company_id=c.id)")) {
            statement.setString(1, companyId.value().toString()); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("player company is missing or is a bluechip"); }
        }
    }

    private static String actionPayload(CompanyGovernanceAction action) {
        return "{\"actionId\":\"" + action.id() + "\",\"companyId\":\"" + action.companyId().value() + "\",\"type\":\"" + action.type() + "\",\"amountMinor\":" + action.amountMinor() + ",\"pricePerShareMinor\":" + action.pricePerShareMinor() + ",\"executableAt\":\"" + action.executableAt() + "\"}";
    }

    private static CompanyPayoutOperation payoutForUpdate(Connection connection, UUID payoutId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM company_payout_operations WHERE id=?")) {
            statement.setString(1, payoutId.toString()); try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("company payout missing: " + payoutId); return payout(rows);
            }
        }
    }

    private static UUID ledger(Connection connection, CompanyId companyId, long amountMinor, Instant at) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO escrow_ledger_entries (id,liability_kind,company_id,player_uuid,amount_minor,operation_id,trade_id,occurred_at) VALUES (?,'COMPANY_TREASURY',?,NULL,?,?,NULL,?)")) {
            statement.setString(1, id.toString()); statement.setString(2, companyId.value().toString()); statement.setLong(3, amountMinor); statement.setNull(4, java.sql.Types.VARCHAR); statement.setString(5, at.toString()); statement.executeUpdate();
        }
        return id;
    }

    private static void linkPayoutLedger(Connection connection, UUID payoutId, UUID ledgerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO company_payout_ledger_links (payout_operation_id,ledger_entry_id) VALUES (?,?)")) {
            statement.setString(1, payoutId.toString()); statement.setString(2, ledgerId.toString()); statement.executeUpdate();
        }
    }

    /** The company UUID is an internal holder identity, not a player account. */
    private static void creditTreasuryHolding(Connection connection, CompanyId companyId, long shares) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)
                ON CONFLICT(company_id,holder_uuid) DO UPDATE SET available_shares=share_holdings.available_shares+excluded.available_shares
                WHERE share_holdings.available_shares <= ?
                """)) {
            statement.setString(1, companyId.value().toString()); statement.setString(2, companyId.value().toString());
            statement.setLong(3, shares); statement.setLong(4, Math.subtractExact(Long.MAX_VALUE, shares));
            if (statement.executeUpdate() != 1) throw new ArithmeticException("company treasury holding overflow or state conflict");
        }
    }

    private static void audit(Connection connection, CompanyId companyId, UUID actorUuid, String eventType, String payload, Instant at) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_events (event_id,company_id,actor_uuid,event_type,payload_json,occurred_at) VALUES (?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, companyId.value().toString()); statement.setString(3, actorUuid == null ? null : actorUuid.toString()); statement.setString(4, eventType); statement.setString(5, payload); statement.setString(6, at.toString()); statement.executeUpdate();
        }
    }

    private static void requirePositive(long value) { if (value <= 0) throw new IllegalArgumentException("amount must be positive"); }

    private static boolean legalPayoutTransition(PayoutOperationState expected, PayoutOperationState next) {
        return switch (expected) {
            case PREPARED -> next == PayoutOperationState.EXTERNAL_DEBIT_CONFIRMED || next == PayoutOperationState.FAILED || next == PayoutOperationState.AMBIGUOUS || next == PayoutOperationState.CANCELLED;
            case EXTERNAL_DEBIT_CONFIRMED -> next == PayoutOperationState.COMPLETED || next == PayoutOperationState.AMBIGUOUS;
            case AMBIGUOUS -> next == PayoutOperationState.EXTERNAL_DEBIT_CONFIRMED || next == PayoutOperationState.FAILED;
            default -> false;
        };
    }

    private static boolean legalActionTransition(GovernanceActionState expected, GovernanceActionState next) {
        return (expected == GovernanceActionState.ANNOUNCED && next == GovernanceActionState.EXECUTION_READY)
                || (expected == GovernanceActionState.EXECUTION_READY && next == GovernanceActionState.EXECUTING)
                || (expected == GovernanceActionState.EXECUTING
                    && (next == GovernanceActionState.EXECUTED || next == GovernanceActionState.CANCELLED));
    }

    private static void requireTransaction(Connection connection) throws SQLException {
        if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required");
    }
}
