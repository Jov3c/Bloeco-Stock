package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlBluechipBootstrapFundingRepository implements BluechipBootstrapFundingRepository {
    private final DataSource dataSource;
    public SqlBluechipBootstrapFundingRepository(DataSource dataSource) { this.dataSource = dataSource; }
    @Override public Optional<Funding> find(UUID id) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM bluechip_bootstrap_funding WHERE id=?")) {
            s.setString(1, id.toString()); try (ResultSet r = s.executeQuery()) { return r.next() ? Optional.of(map(r)) : Optional.empty(); }
        } catch (SQLException e) { throw new IllegalStateException("could not read bluechip bootstrap funding", e); }
    }
    @Override public void prepare(Connection c, Funding f) throws SQLException {
        required(c); try (PreparedStatement s=c.prepareStatement("INSERT INTO bluechip_bootstrap_funding (id,system_account_uuid,amount_minor,state,detail,created_at,updated_at) VALUES (?,?,?,?,?,?,?)")) {
            s.setString(1,f.id().toString());s.setString(2,f.systemAccountId().toString());s.setLong(3,f.amount().minorUnits());s.setString(4,f.state().name());s.setString(5,f.detail());s.setString(6,f.createdAt().toString());s.setString(7,f.updatedAt().toString());s.executeUpdate();
        }
    }
    @Override public void transition(Connection c, UUID id, State expected, State next, String detail, Instant at) throws SQLException {
        required(c); try (PreparedStatement s=c.prepareStatement("UPDATE bluechip_bootstrap_funding SET state=?,detail=?,updated_at=? WHERE id=? AND state=?")) {
            s.setString(1,next.name());s.setString(2,detail);s.setString(3,at.toString());s.setString(4,id.toString());s.setString(5,expected.name());if(s.executeUpdate()!=1)throw new IllegalStateException("bluechip bootstrap funding state conflict: "+id);
        }
    }
    @Override public void complete(Connection c, UUID id, Instant at) throws SQLException { transition(c,id,State.ESCROW_DEPOSITED,State.COMPLETED,"local liquidity ledger applied",at); }
    private static Funding map(ResultSet r) throws SQLException { return new Funding(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("system_account_uuid")),Money.ofMinor(r.getLong("amount_minor")),State.valueOf(r.getString("state")),r.getString("detail"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at"))); }
    private static void required(Connection c) throws SQLException { if (c == null || c.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required"); }
}
