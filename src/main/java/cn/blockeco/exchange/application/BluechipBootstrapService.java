package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.bluechip.BluechipDefinition;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.paper.BluechipConfig;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipRepository;
import cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository;
import cn.blockeco.exchange.ports.BluechipParticipantRepository;
import cn.blockeco.exchange.ports.CompanyRepository;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import cn.blockeco.exchange.ports.StockListingRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Creates the configured fictional companies through the ordinary company/listing/holding ledgers. */
public final class BluechipBootstrapService {
    private final BluechipConfig config; private final UUID systemAccountId; private final CompanyRepository companies;
    private final StockListingRepository listings; private final BluechipRepository bluechips; private final SecuritiesCashRepository cash;
    private final BluechipBootstrapFundingService funding; private final BluechipBootstrapFundingRepository fundingRecords; private final TransactionRunner transactions;
    private final Executor executor; private final AppClock clock;
    private final UUID participantAccountId; private final BluechipParticipantRepository participantAllocations;
    /** $10,000.00 at the default scale: enough for a bounded 2% ordinary order to buy a share. */
    private static final Money PARTICIPANT_INITIAL_CASH = Money.ofMinor(1_000_000);
    private static final long PARTICIPANT_INITIAL_SHARES_PER_COMPANY = 20;

    public BluechipBootstrapService(BluechipConfig config, UUID systemAccountId, CompanyRepository companies, StockListingRepository listings,
            BluechipRepository bluechips, SecuritiesCashRepository cash, BluechipBootstrapFundingService funding,
            BluechipBootstrapFundingRepository fundingRecords, TransactionRunner transactions, Executor executor, AppClock clock) {
        this(config, systemAccountId, companies, listings, bluechips, cash, funding, fundingRecords, transactions, executor, clock, null, null);
    }
    public BluechipBootstrapService(BluechipConfig config, UUID systemAccountId, CompanyRepository companies, StockListingRepository listings,
            BluechipRepository bluechips, SecuritiesCashRepository cash, BluechipBootstrapFundingService funding,
            BluechipBootstrapFundingRepository fundingRecords, TransactionRunner transactions, Executor executor, AppClock clock,
            UUID participantAccountId, BluechipParticipantRepository participantAllocations) {
        this.config = Objects.requireNonNull(config); this.systemAccountId = requireSystemAccount(systemAccountId); this.companies = Objects.requireNonNull(companies);
        this.listings = Objects.requireNonNull(listings); this.bluechips = Objects.requireNonNull(bluechips); this.cash=Objects.requireNonNull(cash); this.funding=Objects.requireNonNull(funding); this.fundingRecords=Objects.requireNonNull(fundingRecords); this.transactions = Objects.requireNonNull(transactions);
        this.executor = Objects.requireNonNull(executor); this.clock = Objects.requireNonNull(clock);
        if ((participantAccountId == null) != (participantAllocations == null)) throw new IllegalArgumentException("participant account and allocation repository must be configured together");
        this.participantAccountId = participantAccountId;
        this.participantAllocations = participantAllocations;
        if (participantAccountId != null && participantAccountId.equals(this.systemAccountId)) throw new IllegalArgumentException("participant account must differ from bluechip maker");
    }
    public CompletionStage<BluechipBootstrapResult> initializeMissing() { return CompletableFuture.supplyAsync(this::initialize, executor); }
    private BluechipBootstrapResult initialize() {
        validateMetadataSet();
        Money total = Money.ofMinor(config.definitions().stream().mapToLong(BluechipDefinition::initialFundCash).reduce(0L, Math::addExact));
        var funded = funding.ensureEscrowFunded(total);
        var persisted = bluechips.bluechipCompanyIds();
        if (persisted.isEmpty()) {
            transactions.inTransaction(connection -> {
                for (BluechipDefinition definition : config.definitions()) seed(connection, definition);
                cash.applyBootstrapLiquidity(connection, systemAccountId, total, true, clock.now());
                fundingRecords.complete(connection, funded.id(), clock.now());
                return null;
            });
            allocateParticipant();
            return new BluechipBootstrapResult(config.definitions().size());
        }
        transactions.inTransaction(connection -> {
            for (BluechipDefinition definition : config.definitions()) {
                CompanyId id = companyId(definition.code());
                listings.reconcileLegacyBluechipTicker(connection, id, definition.code());
                listings.reconcileLegacyBluechipIssuedShares(connection, id, definition.initialFundShares());
            }
            return null;
        });
        for (BluechipDefinition definition : config.definitions()) validateExisting(bluechips.findByCompanyId(companyId(definition.code())).orElseThrow(), companies.findById(companyId(definition.code())).orElseThrow(), definition);
        if (funded.state() == BluechipBootstrapFundingRepository.State.ESCROW_DEPOSITED) transactions.inTransaction(connection -> {
            cash.applyBootstrapLiquidity(connection, systemAccountId, total, false, clock.now());
            fundingRecords.complete(connection, funded.id(), clock.now());
            return null;
        });
        allocateParticipant();
        return new BluechipBootstrapResult(0);
    }
    private void allocateParticipant() {
        if (participantAllocations == null) return;
        var positions = bluechips.all();
        transactions.inTransaction(connection -> {
            participantAllocations.allocateOnce(connection, systemAccountId, participantAccountId, PARTICIPANT_INITIAL_CASH,
                    PARTICIPANT_INITIAL_SHARES_PER_COMPANY, positions);
            return null;
        });
    }
    private void seed(java.sql.Connection connection, BluechipDefinition definition) throws java.sql.SQLException {
        CompanyId id = companyId(definition.code());
        // initialize() already established that no bluechip metadata exists.  Do not open a
        // second pooled connection here: SQLite is deliberately configured with one owner.
            Company seeded = Company.rehydrate(id, definition.displayName(), Company.normalizeName(definition.displayName()), systemAccountId,
                    Money.zero(), definition.totalShares(), DividendRate.FIFTY, CompanyStatus.LISTED, clock.now());
            companies.insert(connection, seeded);
            var listing = listings.allocateFixed(connection, id, definition.code(), Money.ofMinor(definition.referencePrice()), definition.initialFundShares(), clock.now());
            bluechips.insertInitial(connection, new BluechipRepository.BluechipSeed(id, listing, definition.industry(), systemAccountId,
                    Money.ofMinor(definition.referencePrice()), Money.ofMinor(definition.lowerBound()), Money.ofMinor(definition.upperBound()),
                    definition.spreadBps(), definition.eventSensitivityBps(), definition.dividendPayoutBps(), definition.initialFundShares(),
                    Money.ofMinor(definition.initialFundCash()), clock.now()));
    }
    private void validateMetadataSet() {
        var persistedIds = bluechips.bluechipCompanyIds();
        if (persistedIds.isEmpty()) return;
        Set<CompanyId> expected = config.definitions().stream().map(definition -> companyId(definition.code())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<CompanyId> actual = Set.copyOf(persistedIds);
        if (persistedIds.size() != 10 || !actual.equals(expected)) throw new IllegalStateException("bluechip metadata does not match configuration");
    }
    private void validateExisting(BluechipRepository.BluechipCompany existing, Company company, BluechipDefinition definition) {
        if (!existing.systemAccountId().equals(systemAccountId) || !existing.listing().stockCode().equals(definition.code()) || existing.listing().issuedShares() != definition.initialFundShares()
                || existing.referencePrice().minorUnits() != definition.referencePrice() || existing.lowerPrice().minorUnits() != definition.lowerBound()
                || existing.upperPrice().minorUnits() != definition.upperBound() || !existing.industry().equals(definition.industry())
                || company.status() != CompanyStatus.LISTED || !company.founderId().equals(systemAccountId)
                || !company.displayName().equals(definition.displayName()) || company.totalShares() != definition.totalShares()) {
            throw new IllegalStateException("bluechip collision for " + definition.code());
        }
        var metadata = bluechips.allMetadata().stream().filter(candidate -> candidate.companyId().equals(existing.companyId())).findFirst().orElseThrow(() -> new IllegalStateException("bluechip collision for " + definition.code()));
        if (metadata.spreadBps() != definition.spreadBps() || metadata.eventSensitivityBps() != definition.eventSensitivityBps() || metadata.payoutBps() != definition.dividendPayoutBps()) throw new IllegalStateException("bluechip collision for " + definition.code());
        var seeds = bluechips.initializedSeeds(existing.companyId());
        if (seeds.size() != 1 || seeds.getFirst().cash().minorUnits() != definition.initialFundCash() || seeds.getFirst().shares() != definition.initialFundShares()) throw new IllegalStateException("bluechip collision for " + definition.code());
    }
    private static CompanyId companyId(String code) { return new CompanyId(UUID.nameUUIDFromBytes(("blockstock-bluechip:" + code).getBytes(StandardCharsets.UTF_8))); }
    private static UUID requireSystemAccount(UUID value) { Objects.requireNonNull(value, "systemAccountId"); if (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0) throw new IllegalArgumentException("systemAccountId must not be zero"); return value; }
}
