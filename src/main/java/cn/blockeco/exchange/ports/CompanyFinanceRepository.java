package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.finance.CompanyCashAccount;
import cn.blockeco.exchange.domain.finance.ShareHolding;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.finance.TreasuryOperationState;
import cn.blockeco.exchange.application.CapitalizationRecoveryRecord;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyFinanceRepository {
    void createCapitalization(Connection connection, CompanyCashAccount cash, ShareHolding holding, TreasuryOperation operation, AuditEvent audit) throws SQLException;
    void prepare(Connection connection, TreasuryOperation operation, AuditEvent audit) throws SQLException;
    void transition(Connection connection, UUID id, TreasuryOperationState expected, TreasuryOperationState state, AuditEvent audit) throws SQLException;
    Optional<TreasuryOperation> findById(UUID id);
    List<TreasuryOperation> findUnsettledOperations();
    List<CapitalizationRecoveryRecord> findAmbiguousCapitalizations();
    List<Company> findLegacyCompaniesWithoutFinance();
}
