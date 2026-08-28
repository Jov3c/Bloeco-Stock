package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.List;

public interface AssetBindingRepository {
    void insertActive(Connection connection, AssetBinding binding) throws SQLException;
    Optional<AssetBinding> findActive(CompanyId companyId, String adapterId, String externalKey);
    long activeCount(CompanyId companyId);
    List<AssetBinding> allActive();
}
