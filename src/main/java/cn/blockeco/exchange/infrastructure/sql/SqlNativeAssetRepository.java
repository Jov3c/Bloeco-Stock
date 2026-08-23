package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.NativeAsset;
import cn.blockeco.exchange.ports.NativeAssetRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlNativeAssetRepository implements NativeAssetRepository {
    private final DataSource dataSource;
    public SqlNativeAssetRepository(DataSource dataSource) { this.dataSource=dataSource; }
    @Override public void insert(Connection connection, NativeAsset asset) throws SQLException { try(PreparedStatement s=connection.prepareStatement("INSERT INTO native_assets (id,company_id,founder_uuid,name,created_at) VALUES (?,?,?,?,?)")){s.setString(1,asset.id().toString());s.setString(2,asset.companyId().value().toString());s.setString(3,asset.founderId().toString());s.setString(4,asset.name());s.setString(5,asset.createdAt().toString());s.executeUpdate();} }
    @Override public Optional<NativeAsset> find(UUID id) { try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM native_assets WHERE id=?")){s.setString(1,id.toString());try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new NativeAsset(id,new CompanyId(UUID.fromString(r.getString("company_id"))),UUID.fromString(r.getString("founder_uuid")),r.getString("name"),Instant.parse(r.getString("created_at"))));}}catch(SQLException e){throw new IllegalStateException("could not read native asset",e);} }
    @Override public List<NativeAsset> listOwned(UUID founderId, int limit) { try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM native_assets WHERE founder_uuid=? ORDER BY created_at DESC LIMIT ?")){s.setString(1,founderId.toString());s.setInt(2,Math.max(1,Math.min(45,limit)));try(ResultSet r=s.executeQuery()){List<NativeAsset> values=new ArrayList<>();while(r.next())values.add(new NativeAsset(UUID.fromString(r.getString("id")),new CompanyId(UUID.fromString(r.getString("company_id"))),founderId,r.getString("name"),Instant.parse(r.getString("created_at"))));return List.copyOf(values);}}catch(SQLException e){throw new IllegalStateException("could not list native assets",e);} }
}
