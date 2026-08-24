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
import cn.blockeco.exchange.ports.CompanyRepository;
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
    private final StockListingRepository listings; private final BluechipRepository bluechips; private final TransactionRunner transactions;
    private final Executor executor; private final AppClock clock;

    public BluechipBootstrapService(BluechipConfig config, UUID systemAccountId, CompanyRepository companies, StockListingRepository listings,
            BluechipRepository bluechips, TransactionRunner transactions, Executor executor, AppClock clock) {
        this.config = Objects.requireNonNull(config); this.systemAccountId = requireSystemAccount(systemAccountId); this.companies = Objects.requireNonNull(companies);
        this.listings = Objects.requireNonNull(listings); this.bluechips = Objects.requireNonNull(bluechips); this.transactions = Objects.requireNonNull(transactions);
        this.executor = Objects.requireNonNull(executor); this.clock = Objects.requireNonNull(clock);
    }
    public CompletionStage<BluechipBootstrapResult> initializeMissing() { return CompletableFuture.supplyAsync(this::initialize, executor); }
    private BluechipBootstrapResult initialize() {
        validateMetadataSet();
        int created = 0;
        for (BluechipDefinition definition : config.definitions()) if (initialize(definition)) created++;
        return new BluechipBootstrapResult(created);
    }
    private boolean initialize(BluechipDefinition definition) {
        CompanyId id = companyId(definition.code());
        var bluechip = bluechips.findByCompanyId(id);
        var company = companies.findById(id);
        var sameName = companies.findByNormalizedName(Company.normalizeName(definition.displayName()));
        if (bluechip.isPresent()) { validateExisting(bluechip.orElseThrow(), company.orElseThrow(), definition); return false; }
        if (company.isPresent() || sameName.isPresent()) throw new IllegalStateException("bluechip collision for " + definition.code());
        transactions.inTransaction(connection -> {
            Company seeded = Company.rehydrate(id, definition.displayName(), Company.normalizeName(definition.displayName()), systemAccountId,
                    Money.zero(), definition.totalShares(), DividendRate.FIFTY, CompanyStatus.LISTED, clock.now());
            companies.insert(connection, seeded);
            var listing = listings.allocate(connection, id, Money.ofMinor(definition.referencePrice()), definition.totalShares(), clock.now());
            bluechips.insertInitial(connection, new BluechipRepository.BluechipSeed(id, listing, definition.industry(), systemAccountId,
                    Money.ofMinor(definition.referencePrice()), Money.ofMinor(definition.lowerBound()), Money.ofMinor(definition.upperBound()),
                    definition.spreadBps(), definition.eventSensitivityBps(), definition.dividendPayoutBps(), definition.initialFundShares(),
                    Money.ofMinor(definition.initialFundCash()), clock.now()));
            return null;
        });
        return true;
    }
    private void validateMetadataSet() {
        var metadata = bluechips.allMetadata();
        if (metadata.isEmpty()) return;
        Set<CompanyId> expected = config.definitions().stream().map(definition -> companyId(definition.code())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<CompanyId> actual = metadata.stream().map(BluechipRepository.BluechipMetadata::companyId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (metadata.size() != 10 || !actual.equals(expected)) throw new IllegalStateException("bluechip metadata does not match configuration");
    }
    private void validateExisting(BluechipRepository.BluechipCompany existing, Company company, BluechipDefinition definition) {
        if (!existing.systemAccountId().equals(systemAccountId) || existing.listing().issuedShares() != definition.totalShares()
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
