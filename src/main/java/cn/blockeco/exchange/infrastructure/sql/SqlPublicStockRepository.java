package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.application.*;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.finance.PrimaryOfferingState;
import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.PublicStockRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Prepared-statement SQLite projections for public stock information only. */
public final class SqlPublicStockRepository implements PublicStockRepository {
    private final DataSource dataSource;
    public SqlPublicStockRepository(DataSource dataSource) { this.dataSource=Objects.requireNonNull(dataSource); }

    @Override public List<PublicMarketRow> market() {
        String sql="SELECT occurred_at FROM stock_trades";
        Instant[] bounds;
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql);ResultSet r=s.executeQuery()){Instant first=null,last=null;while(r.next()){Instant at=Instant.parse(r.getString(1));if(first==null||at.isBefore(first))first=at;if(last==null||at.isAfter(last))last=at;}bounds=first==null?new Instant[]{Instant.EPOCH,Instant.EPOCH.plusSeconds(1)}:new Instant[]{first,last.equals(Instant.MAX)?Instant.MAX:last.plusNanos(1)};}catch(SQLException e){throw failed("bound public market",e);}
        return market(bounds[0],bounds[1]);
    }
    @Override public List<PublicMarketRow> market(Instant dayStart,Instant nextDayStart) {
        Objects.requireNonNull(dayStart);Objects.requireNonNull(nextDayStart); if(!dayStart.isBefore(nextDayStart))throw new IllegalArgumentException("day bounds required");
        String sql="SELECT c.id,c.display_name,sl.stock_code,sl.issue_reference_price_minor,sl.issued_shares,c.status FROM stock_listings sl JOIN companies c ON c.id=sl.company_id WHERE c.status='LISTED' ORDER BY sl.stock_code";
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql);ResultSet r=s.executeQuery()){List<PublicMarketRow> rows=new ArrayList<>();while(r.next()){long ref=r.getLong(4),shares=r.getLong(5);MarketFacts facts=marketFacts(c,r.getString(1),ref,dayStart,nextDayStart);rows.add(new PublicMarketRow(r.getString(2),r.getString(3),Money.ofMinor(ref),Money.ofMinor(Math.multiplyExact(facts.latest,shares)),shares,CompanyStatus.valueOf(r.getString(6)),Money.ofMinor(facts.latest),Money.ofMinor(Math.subtractExact(facts.latest,facts.previous)),facts.volume,Money.ofMinor(facts.turnover)));}return List.copyOf(rows);}catch(SQLException e){throw failed("list public market",e);}
    }
    private static MarketFacts marketFacts(Connection c,String company,long reference,Instant start,Instant next)throws SQLException{long latest=reference,previous=reference,volume=0,turnover=0;Instant latestAt=null,previousAt=null;String latestId=null,previousId=null;String sql="SELECT id,price_minor,shares,notional_minor,occurred_at FROM stock_trades WHERE company_id=?";try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,company);try(ResultSet r=s.executeQuery()){while(r.next()){String id=UUID.fromString(r.getString(1)).toString();Instant at=Instant.parse(r.getString(5));long price=r.getLong(2);if(latestAt==null||at.isAfter(latestAt)||(at.equals(latestAt)&&id.compareTo(latestId)>0)){latest=price;latestAt=at;latestId=id;}if(at.isBefore(start)&&(previousAt==null||at.isAfter(previousAt)||(at.equals(previousAt)&&id.compareTo(previousId)>0))){previous=price;previousAt=at;previousId=id;}if(!at.isBefore(start)&&at.isBefore(next)){volume=Math.addExact(volume,r.getLong(3));turnover=Math.addExact(turnover,r.getLong(4));}}}}return new MarketFacts(latest,previous,volume,turnover);}
    private record MarketFacts(long latest,long previous,long volume,long turnover){}
    @Override public List<PublicOfferingView> listOfferings(int limit) {
        int bounded=limit(limit); String sql=publicOfferingSql()+" GROUP BY po.id ORDER BY po.announced_at DESC,po.id DESC LIMIT ?";
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,bounded);try(ResultSet r=s.executeQuery()){List<PublicOfferingView> result=new ArrayList<>();while(r.next())result.add(offering(r));return List.copyOf(result);}}catch(SQLException e){throw failed("list public offerings",e);}
    }
    @Override public Optional<PublicStockInfo> findInfo(String query) {
        String sql="SELECT c.display_name,c.status,c.total_shares,sl.stock_code,sl.issue_reference_price_minor FROM companies c LEFT JOIN stock_listings sl ON sl.company_id=c.id WHERE c.normalized_name=? OR sl.stock_code=? ORDER BY CASE WHEN sl.stock_code=? THEN 0 ELSE 1 END LIMIT 1";
        String name=normalized(query),code=code(query); try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,name);s.setString(2,code);s.setString(3,code);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();String stockCode=r.getString(4); long price=r.getLong(5); Optional<Money> reference=r.wasNull()?Optional.empty():Optional.of(Money.ofMinor(price));return Optional.of(new PublicStockInfo(r.getString(1),Optional.ofNullable(stockCode),CompanyStatus.valueOf(r.getString(2)),reference,r.getLong(3)));}}catch(SQLException e){throw failed("find public stock",e);}
    }
    @Override public List<PublicAnnouncement> findAnnouncements(String query,int requestedLimit) {
        String sql="SELECT a.id,c.display_name,a.body,a.created_at FROM company_announcements a JOIN companies c ON c.id=a.company_id LEFT JOIN stock_listings sl ON sl.company_id=c.id WHERE c.normalized_name=? OR sl.stock_code=? ORDER BY a.created_at DESC,a.id DESC LIMIT ?";
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,normalized(query));s.setString(2,code(query));s.setInt(3,limit(requestedLimit));try(ResultSet r=s.executeQuery()){List<PublicAnnouncement> result=new ArrayList<>();while(r.next())result.add(new PublicAnnouncement(UUID.fromString(r.getString(1)),r.getString(2),r.getString(3),Instant.parse(r.getString(4))));return List.copyOf(result);}}catch(SQLException e){throw failed("list public announcements",e);}
    }
    @Override public Optional<UUID> findOpenOfferingByCompanyOrCode(String query) {
        String sql="SELECT po.id FROM primary_offerings po JOIN companies c ON c.id=po.company_id LEFT JOIN stock_listings sl ON sl.company_id=c.id WHERE po.state='OPEN' AND (c.normalized_name=? OR sl.stock_code=?) ORDER BY po.opens_at DESC,po.id DESC LIMIT 1";
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,normalized(query));s.setString(2,code(query));try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(UUID.fromString(r.getString(1))):Optional.empty();}}catch(SQLException e){throw failed("find open offering",e);}
    }
    @Override public List<PublicStockSymbol> symbols() {
        String sql="SELECT c.display_name,sl.stock_code FROM companies c LEFT JOIN stock_listings sl ON sl.company_id=c.id WHERE EXISTS (SELECT 1 FROM primary_offerings po WHERE po.company_id=c.id AND po.state='OPEN') OR sl.company_id IS NOT NULL ORDER BY c.display_name,c.id";
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql);ResultSet r=s.executeQuery()){List<PublicStockSymbol> result=new ArrayList<>();while(r.next())result.add(new PublicStockSymbol(r.getString(1),Optional.ofNullable(r.getString(2))));return List.copyOf(result);}catch(SQLException e){throw failed("list public symbols",e);}
    }
    @Override public List<MarketNewsItem> recentNews(int requestedLimit) { String sql="SELECT headline,body,starts_at FROM bluechip_events ORDER BY starts_at DESC,id DESC LIMIT ?";try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,limit(requestedLimit));try(ResultSet r=s.executeQuery()){List<MarketNewsItem> result=new ArrayList<>();while(r.next())result.add(new MarketNewsItem(r.getString(1),r.getString(2),Instant.parse(r.getString(3))));return List.copyOf(result);}}catch(SQLException e){throw failed("list market news",e);} }
    private static int limit(int requested){return Math.max(1,Math.min(50,requested));}
    /** Invalid company-name input is simply not resolvable, so public commands can respond gracefully. */
    private static String normalized(String input){try{return Company.normalizeName(Objects.requireNonNull(input,"companyNameOrCode"));}catch(IllegalArgumentException invalid){return null;}}
    private static String code(String input){return Objects.requireNonNull(input,"companyNameOrCode").trim().toUpperCase(Locale.ROOT);}
    private static IllegalStateException failed(String action,SQLException error){return new IllegalStateException("could not "+action,error);}
    private static String publicOfferingSql(){return "SELECT po.id offering_id,po.company_id offering_company_id,c.display_name,po.state,po.target_minor,po.issue_price_minor,po.maximum_shares,po.announced_at,po.opens_at,po.closes_at,COALESCE(SUM(CASE WHEN t.state='COMPLETED' THEN ps.shares ELSE 0 END),0) issued_shares,COALESCE(SUM(CASE WHEN t.state<>'REFUNDED' THEN ps.shares ELSE 0 END),0) reserved_shares FROM primary_offerings po JOIN companies c ON c.id=po.company_id LEFT JOIN primary_subscriptions ps ON ps.offering_id=po.id LEFT JOIN treasury_operations t ON t.id=ps.id WHERE po.state IN ('ANNOUNCED','OPEN','CLOSED')";}
    private static PublicOfferingView offering(ResultSet r)throws SQLException{long maximum=r.getLong("maximum_shares"),reserved=r.getLong("reserved_shares");return new PublicOfferingView(UUID.fromString(r.getString("offering_id")),new CompanyId(UUID.fromString(r.getString("offering_company_id"))),r.getString("display_name"),PrimaryOfferingState.valueOf(r.getString("state")),Money.ofMinor(r.getLong("target_minor")),Money.ofMinor(r.getLong("issue_price_minor")),maximum,r.getLong("issued_shares"),reserved,Math.subtractExact(maximum,reserved),Instant.parse(r.getString("announced_at")),Instant.parse(r.getString("opens_at")),Instant.parse(r.getString("closes_at")));}
}
