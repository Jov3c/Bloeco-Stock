package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.finance.NativeAsset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface NativeAssetRepository {
    void insert(Connection connection, NativeAsset asset) throws SQLException;
    Optional<NativeAsset> find(UUID id);
    List<NativeAsset> listOwned(UUID founderId, int limit);
}
