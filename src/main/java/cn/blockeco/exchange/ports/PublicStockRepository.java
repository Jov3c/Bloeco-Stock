package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.application.*;
import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Synchronous persistence boundary; callers must schedule it outside Paper's thread. */
public interface PublicStockRepository {
    List<PublicMarketRow> market();
    List<PublicOfferingView> listOfferings(int limit);
    Optional<PublicStockInfo> findInfo(String companyNameOrCode);
    List<PublicAnnouncement> findAnnouncements(String companyNameOrCode, int limit);
    Optional<UUID> findOpenOfferingByCompanyOrCode(String companyNameOrCode);
    List<PublicStockSymbol> symbols();
}
