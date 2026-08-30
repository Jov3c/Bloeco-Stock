package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.governance.CompanyGovernanceAction;
import cn.blockeco.exchange.domain.governance.GovernanceActionState;
import cn.blockeco.exchange.domain.governance.GovernanceActionType;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.CompanyExitRepository;
import cn.blockeco.exchange.ports.CompanyRepository;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable, retry-safe player-company exit lifecycle. UI and Vault are deliberately outside this service. */
public final class CompanyExitService {
    private static final Duration ANNOUNCEMENT_PERIOD = Duration.ofHours(12);
    private final CompanyRepository companies;
    private final CompanyExitRepository exits;
    private final SecondaryTradingRepository orders;
    private final SecuritiesCashRepository cash;
    private final TransactionRunner transactions;
    private final AppClock clock;

    public CompanyExitService(CompanyRepository companies, CompanyExitRepository exits, SecondaryTradingRepository orders,
                              SecuritiesCashRepository cash, TransactionRunner transactions, AppClock clock) {
        this.companies=Objects.requireNonNull(companies);this.exits=Objects.requireNonNull(exits);this.orders=Objects.requireNonNull(orders);
        this.cash=Objects.requireNonNull(cash);this.transactions=Objects.requireNonNull(transactions);this.clock=Objects.requireNonNull(clock);
    }

    public UUID announceVoluntaryDelist(UUID founder, CompanyId companyId, Money liquidationPricePerShare, String correlationKey) {
        Objects.requireNonNull(founder); Objects.requireNonNull(companyId); Objects.requireNonNull(liquidationPricePerShare); requireKey(correlationKey);
        if (liquidationPricePerShare.minorUnits() <= 0) throw new IllegalArgumentException("liquidation price must be positive");
        Company company=company(companyId); if (!company.founderId().equals(founder)) throw new IllegalArgumentException("only the founder may delist a company");
        if (company.status()!=CompanyStatus.LISTED) throw new IllegalStateException("only listed companies may delist");
        Instant now=clock.now(); UUID id=UUID.randomUUID(); CompanyGovernanceAction action=new CompanyGovernanceAction(id,companyId,founder,GovernanceActionType.VOLUNTARY_DELIST,0,liquidationPricePerShare.minorUnits(),now,now.plus(ANNOUNCEMENT_PERIOD),GovernanceActionState.ANNOUNCED,correlationKey);
        transactions.inTransaction(c->{exits.createAction(c,action,"公司退市公告：12小时后停止交易并进入清算。",now);return null;}); return id;
    }

    /** The Paper permission adapter must pass a verified administrator flag; false is always rejected. */
    public UUID forceDelist(UUID administrator, boolean administratorAuthorized, CompanyId companyId, Money liquidationPricePerShare, String correlationKey) {
        Objects.requireNonNull(administrator); Objects.requireNonNull(companyId); Objects.requireNonNull(liquidationPricePerShare); requireKey(correlationKey);
        if (!administratorAuthorized) throw new IllegalArgumentException("administrator authorization is required");
        if (liquidationPricePerShare.minorUnits() <= 0) throw new IllegalArgumentException("liquidation price must be positive");
        if (company(companyId).status()!=CompanyStatus.LISTED) throw new IllegalStateException("only listed companies may delist");
        Instant now=clock.now(); UUID id=UUID.randomUUID(); CompanyGovernanceAction action=new CompanyGovernanceAction(id,companyId,administrator,GovernanceActionType.FORCED_DELIST,0,liquidationPricePerShare.minorUnits(),now,now,GovernanceActionState.ANNOUNCED,correlationKey);
        transactions.inTransaction(c->{exits.createAction(c,action,"管理员强制退市公告：停止交易并进入清算。",now);return null;}); return id;
    }

    /** Makes an announced due action live and freezes new orders before any release work starts. */
    public boolean begin(UUID actionId) {
        CompanyGovernanceAction action=action(actionId); Instant now=clock.now();
        if (action.type()!=GovernanceActionType.VOLUNTARY_DELIST && action.type()!=GovernanceActionType.FORCED_DELIST) throw new IllegalArgumentException("delisting action required");
        if (now.isBefore(action.executableAt())) throw new IllegalStateException("announcement period has not elapsed");
        return transactions.inTransaction(c->{
            if (action.state()==GovernanceActionState.EXECUTED) return false;
            if (action.state()==GovernanceActionState.ANNOUNCED) {
                if (!exits.transitionAction(c,actionId,GovernanceActionState.ANNOUNCED,GovernanceActionState.EXECUTION_READY,now)) return false;
                if (!exits.transitionAction(c,actionId,GovernanceActionState.EXECUTION_READY,GovernanceActionState.EXECUTING,now)) return false;
            } else if (action.state()!=GovernanceActionState.EXECUTING) return false;
            return exits.hasCompanyStatus(c,action.companyId(),CompanyStatus.DELISTING)
                    || exits.transitionCompanyStatus(c,action.companyId(),CompanyStatus.LISTED,CompanyStatus.DELISTING);
        });
    }

    /** Cancels at most one bounded page, using the normal order-release reservation accounting. */
    public int releaseOpenOrders(UUID actionId, int pageSize) {
        if (pageSize<1 || pageSize>200) throw new IllegalArgumentException("page size must be 1..200");
        CompanyGovernanceAction action=action(actionId); requireExecutingDelist(action); Instant now=clock.now();
        return transactions.inTransaction(c->{
            var progress=exits.orderReleaseProgress(c, actionId).orElse(null); if(progress!=null && progress.complete()) return 0;
            UUID after=progress==null?null:progress.lastReleasedOrderId().orElse(null); List<UUID> ids=exits.activeOrderIds(c,action.companyId(),after,pageSize);
            long released=progress==null?0:progress.releasedOrders(); UUID last=after;
            for(UUID id:ids){orders.releaseOrder(c,id,LimitOrder.State.CANCELLED);released++;last=id;}
            boolean complete=ids.size()<pageSize && exits.activeOrderIds(c,action.companyId(),last,1).isEmpty();
            exits.recordOrderReleaseProgress(c,actionId,last,released,complete,now);
            if(complete) exits.transitionCompanyStatus(c,action.companyId(),CompanyStatus.DELISTING,CompanyStatus.LIQUIDATING);
            return ids.size();
        });
    }

    /** Captures holders only after all reservations were released, then fixes claim economics in UUID order. */
    public int createClaims(UUID actionId) {
        CompanyGovernanceAction action=action(actionId); requireExecutingDelist(action); Instant now=clock.now();
        return transactions.inTransaction(c->{
            if(!exits.hasCompanyStatus(c,action.companyId(),CompanyStatus.LIQUIDATING)) throw new IllegalStateException("orders are not fully released");
            exits.createExitSnapshots(c,actionId,action.companyId(),now);
            return exits.createLiquidationClaims(c,actionId,action.pricePerShareMinor(),now).size();
        });
    }

    /** Replays safely after a crash: each conditional PENDING -> CREDITED claim is paid once. */
    public int creditClaims(UUID actionId) {
        CompanyGovernanceAction action=action(actionId); if(action.state()==GovernanceActionState.EXECUTED) return 0; requireExecutingDelist(action); Instant now=clock.now();
        return transactions.inTransaction(c->{
            int credited=0; for(var claim:exits.liquidationClaims(c,actionId)) if(exits.creditLiquidationClaim(c,actionId,claim.holderUuid(),cash,now)) credited++;
            boolean pending=exits.liquidationClaims(c,actionId).stream().anyMatch(claim->claim.state().name().equals("PENDING"));
            if(!pending){exits.transitionCompanyStatus(c,action.companyId(),CompanyStatus.LIQUIDATING,CompanyStatus.DELISTED);exits.transitionAction(c,actionId,GovernanceActionState.EXECUTING,GovernanceActionState.EXECUTED,now);} return credited;
        });
    }

    private Company company(CompanyId id){return companies.findById(id).orElseThrow(()->new IllegalArgumentException("company not found"));}
    private CompanyGovernanceAction action(UUID id){return exits.findAction(Objects.requireNonNull(id)).orElseThrow(()->new IllegalArgumentException("governance action not found"));}
    private static void requireKey(String key){if(key==null||key.isBlank())throw new IllegalArgumentException("correlation key is required");}
    private static void requireExecutingDelist(CompanyGovernanceAction action){if((action.type()!=GovernanceActionType.VOLUNTARY_DELIST&&action.type()!=GovernanceActionType.FORCED_DELIST)||action.state()!=GovernanceActionState.EXECUTING)throw new IllegalStateException("delisting action is not executing");}
}
