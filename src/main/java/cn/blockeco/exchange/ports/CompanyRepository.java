package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public interface CompanyRepository {

    void insert(Connection connection, Company company) throws SQLException;

    Optional<Company> findById(CompanyId id);

    Optional<Company> findByNormalizedName(String normalizedName);
}
