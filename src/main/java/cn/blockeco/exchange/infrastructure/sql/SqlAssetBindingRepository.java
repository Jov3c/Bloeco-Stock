package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.*;
import cn.blockeco.exchange.ports.AssetBindingRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

public final class SqlAssetBindingRepository implements AssetBindingRepository {
    private final DataSource dataSource;
    public SqlAssetBindingRepository(DataSource dataSource) { this.dataSource=dataSource; }
    public void insertActive(Connection c, AssetBinding b) throws SQLException { try(PreparedStatement s=c.prepareStatement("INSERT INTO asset_bindings (id,company_id,adapter_id,external_key,verified_owner_uuid,state,created_at) VALUES (?,?,?,?,?,?,?)")){s.setString(1,b.id().toString());s.setString(2,b.companyId().value().toString());s.setString(3,b.adapterId());s.setString(4,b.externalKey());s.setString(5,b.verifiedOwner().toString());s.setString(6,b.state().name());s.setString(7,b.createdAt().toString());s.executeUpdate();} }
    public Optional<AssetBinding> findActive(CompanyId id,String adapter,String key){return find("SELECT * FROM asset_bindings WHERE company_id=? AND adapter_id=? AND external_key=? AND state='ACTIVE'",id.value().toString(),adapter,key);}
    public long activeCount(CompanyId id){try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM asset_bindings WHERE company_id=? AND state='ACTIVE'")){s.setString(1,id.value().toString());try(ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}catch(SQLException e){throw new IllegalStateException("could not count asset bindings",e);}}
    public List<AssetBinding> allActive(){try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM asset_bindings WHERE state='ACTIVE' ORDER BY created_at, id")){try(ResultSet r=s.executeQuery()){List<AssetBinding> bindings=new ArrayList<>();while(r.next())bindings.add(binding(r));return List.copyOf(bindings);}}catch(SQLException e){throw new IllegalStateException("could not read active asset bindings",e);}}
    private Optional<AssetBinding> find(String sql,String...v){try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<v.length;i++)s.setString(i+1,v[i]);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(binding(r)):Optional.empty();}}catch(SQLException e){throw new IllegalStateException("could not read asset binding",e);}}
    private static AssetBinding binding(ResultSet r) throws SQLException{return new AssetBinding(UUID.fromString(r.getString("id")),new CompanyId(UUID.fromString(r.getString("company_id"))),r.getString("adapter_id"),r.getString("external_key"),UUID.fromString(r.getString("verified_owner_uuid")),AssetBindingState.valueOf(r.getString("state")),Instant.parse(r.getString("created_at")));}
}
