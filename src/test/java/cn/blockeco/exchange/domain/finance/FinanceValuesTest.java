package cn.blockeco.exchange.domain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinanceValuesTest {

    private static final CompanyId COMPANY_ID = new CompanyId(UUID.fromString("8ca5cd4e-6f35-4d03-8ae2-286e6f2c3b0d"));
    private static final Instant ANNOUNCED_AT = Instant.parse("2026-08-14T12:00:00Z");

    @Test
    void cash_account_rejects_reserved_amount_above_cash() {
        assertThatThrownBy(() -> new CompanyCashAccount(
                        COMPANY_ID, Money.ofMinor(10), Money.ofMinor(10), Money.zero(), Money.ofMinor(11)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cash_account_rejects_negative_amounts() {
        assertThatThrownBy(() -> new CompanyCashAccount(
                        COMPANY_ID, Money.ofMinor(-1), Money.zero(), Money.zero(), Money.zero()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void share_holding_rejects_negative_share_balances() {
        assertThatThrownBy(() -> new ShareHolding(COMPANY_ID, UUID.randomUUID(), -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareHolding(COMPANY_ID, UUID.randomUUID(), 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void offering_rounds_down_target_to_integer_shares() {
        PrimaryOffering offering = PrimaryOffering.plan(
                COMPANY_ID, Money.ofMinor(501), Money.ofMinor(100), ANNOUNCED_AT);

        assertThat(offering.maximumShares()).isEqualTo(5L);
        assertThat(offering.opensAt()).isEqualTo(ANNOUNCED_AT.plus(Duration.ofHours(12)));
        assertThat(offering.closesAt()).isEqualTo(ANNOUNCED_AT.plus(Duration.ofHours(60)));
        assertThat(offering.state()).isEqualTo(PrimaryOfferingState.ANNOUNCED);
    }

    @Test
    void offering_rejects_a_price_that_cannot_issue_a_share() {
        assertThatThrownBy(() -> PrimaryOffering.plan(
                        COMPANY_ID, Money.ofMinor(99), Money.ofMinor(100), ANNOUNCED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void company_preserves_its_invariants_when_issued_shares_are_added() {
        Company company = Company.register(
                COMPANY_ID, "Finance Works", UUID.randomUUID(), Money.zero(), DividendRate.FIFTY, ANNOUNCED_AT);

        assertThat(company.withTotalShares(1_005).totalShares()).isEqualTo(1_005L);
        assertThatThrownBy(() -> company.withTotalShares(999L)).isInstanceOf(IllegalArgumentException.class);
    }
}
