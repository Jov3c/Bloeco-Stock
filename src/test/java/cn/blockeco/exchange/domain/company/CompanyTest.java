package cn.blockeco.exchange.domain.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyTest {

    @Test
    void normalizes_names_at_unicode_boundaries() {
        assertThat(Company.normalizeName("\u2003AB\u00a0")).isEqualTo("ab");
        assertThat(Company.normalizeName("A".repeat(24))).isEqualTo("a".repeat(24));
        assertThatThrownBy(() -> Company.normalizeName("A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Company.normalizeName("A".repeat(25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final UUID FOUNDER = UUID.fromString("9fbb8514-5773-41c4-aa72-6e6a1e47f5c2");
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Test
    void registers_company_with_fixed_share_count_and_locked_dividend_rate() {
        Company company = Company.register(
                new CompanyId(UUID.randomUUID()), "  Red Stone 工业  ", FOUNDER,
                Money.ofMinor(1_000_000), DividendRate.FIFTY, NOW);

        assertThat(company.displayName()).isEqualTo("Red Stone 工业");
        assertThat(company.normalizedName()).isEqualTo("red stone 工业");
        assertThat(company.totalShares()).isEqualTo(1000L);
        assertThat(company.dividendRate().basisPoints()).isEqualTo(5000);
        assertThat(company.status()).isEqualTo(CompanyStatus.PENDING_ASSET_BINDING);
    }

    @Test
    void normalizes_whitespace_and_counts_astral_characters_as_one_code_point() {
        Company company = Company.register(
                new CompanyId(UUID.randomUUID()), "\t😀\nA  ", FOUNDER,
                Money.zero(), DividendRate.THIRTY, NOW);

        assertThat(company.displayName()).isEqualTo("😀 A");
        assertThat(company.normalizedName()).isEqualTo("😀 a");
    }

    @Test
    void rejects_names_outside_two_to_twenty_four_unicode_code_points_after_normalization() {
        assertThatThrownBy(() -> registerWithName("A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registerWithName("a".repeat(25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preserves_nonnegative_registered_capital_as_treasury() {
        Company company = Company.register(
                new CompanyId(UUID.randomUUID()), "Treasury Works", FOUNDER,
                Money.ofMinor(42), DividendRate.SEVENTY, NOW);

        assertThat(company.treasury()).isEqualTo(Money.ofMinor(42));
        assertThatThrownBy(() -> Company.register(
                        new CompanyId(UUID.randomUUID()), "Treasury Works", FOUNDER,
                        Money.ofMinor(-1), DividendRate.SEVENTY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposes_the_three_permitted_dividend_rates_by_percent() {
        assertThat(DividendRate.fromPercent(30)).isEqualTo(DividendRate.THIRTY);
        assertThat(DividendRate.fromPercent(50)).isEqualTo(DividendRate.FIFTY);
        assertThat(DividendRate.fromPercent(70)).isEqualTo(DividendRate.SEVENTY);
        assertThatThrownBy(() -> DividendRate.fromPercent(40))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retains_the_founder_identity_and_rejects_the_zero_uuid() {
        Company company = Company.register(
                new CompanyId(UUID.randomUUID()), "Founder Works", FOUNDER,
                Money.zero(), DividendRate.FIFTY, NOW);

        assertThat(company.founderId()).isEqualTo(FOUNDER);
        assertThatThrownBy(() -> Company.register(
                        new CompanyId(UUID.randomUUID()), "Founder Works", new UUID(0, 0),
                        Money.zero(), DividendRate.FIFTY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retains_the_registration_time() {
        Company company = Company.register(
                new CompanyId(UUID.randomUUID()), "Time Works", FOUNDER,
                Money.zero(), DividendRate.FIFTY, NOW);

        assertThat(company.createdAt()).isEqualTo(NOW);
    }

    private Company registerWithName(String name) {
        return Company.register(
                new CompanyId(UUID.randomUUID()), name, FOUNDER,
                Money.zero(), DividendRate.FIFTY, NOW);
    }
}
