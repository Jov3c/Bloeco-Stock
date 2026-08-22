package cn.blockeco.exchange.domain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.domain.trading.Trade;
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
    void offering_rejects_a_maximum_share_count_that_does_not_match_its_target_and_price() {
        assertThatThrownBy(() -> new PrimaryOffering(
                        UUID.randomUUID(), COMPANY_ID, Money.ofMinor(501), Money.ofMinor(100), 6,
                        ANNOUNCED_AT, ANNOUNCED_AT.plus(Duration.ofHours(12)),
                        ANNOUNCED_AT.plus(Duration.ofHours(60)), PrimaryOfferingState.ANNOUNCED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void company_preserves_its_invariants_when_issued_shares_are_added() {
        Company company = Company.register(
                COMPANY_ID, "Finance Works", UUID.randomUUID(), Money.zero(), DividendRate.FIFTY, ANNOUNCED_AT);

        Company grownCompany = company.withTotalShares(1_005);

        assertThat(grownCompany.totalShares()).isEqualTo(1_005L);
        assertThatThrownBy(() -> grownCompany.withTotalShares(1_004L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listing_accepts_the_bounded_six_digit_stock_code_range() {
        assertThat(new StockListing(COMPANY_ID, "BS000001", Money.ofMinor(250), 1, ANNOUNCED_AT).stockCode())
                .isEqualTo("BS000001");
        assertThat(new StockListing(COMPANY_ID, "BS999999", Money.ofMinor(250), 1, ANNOUNCED_AT).stockCode())
                .isEqualTo("BS999999");
    }

    @Test
    void listing_rejects_zero_or_seven_digit_code() {
        assertThatThrownBy(() -> new StockListing(COMPANY_ID, "BS000000", Money.ofMinor(1), 1, ANNOUNCED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockListing(COMPANY_ID, "BS1000000", Money.ofMinor(1), 1, ANNOUNCED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void securities_cash_account_rejects_negative_reserved_cash() {
        assertThatThrownBy(() -> new SecuritiesCashAccount(
                        UUID.randomUUID(), Money.ofMinor(10), Money.ofMinor(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void limit_order_rejects_remaining_shares_above_original_shares() {
        assertThatThrownBy(() -> new LimitOrder(
                        UUID.randomUUID(), COMPANY_ID, "BS000001", UUID.randomUUID(), LimitOrder.Side.BUY,
                        Money.ofMinor(10), 10, 11, 1, Money.ofMinor(110), Money.zero(), Money.zero(), 100,
                        ANNOUNCED_AT, LimitOrder.State.OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remainingShares");
    }

    @Test
    void securities_cash_operation_rejects_a_cross_direction_confirmed_stage() {
        assertThatThrownBy(() -> new SecuritiesCashOperation(
                        UUID.randomUUID(), UUID.randomUUID(), Money.ofMinor(10), SecuritiesCashDirection.DEPOSIT,
                        SecuritiesCashOperationState.AMBIGUOUS, SecuritiesCashOperationState.ESCROW_WITHDRAWN,
                        null, ANNOUNCED_AT, ANNOUNCED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direction");
    }

    @Test
    void active_buy_order_rejects_a_zero_cash_reserve() {
        assertThatThrownBy(() -> new LimitOrder(
                        UUID.randomUUID(), COMPANY_ID, "BS000001", UUID.randomUUID(), LimitOrder.Side.BUY,
                        Money.ofMinor(10), 10, 10, 1, Money.zero(), Money.zero(), Money.zero(), 100,
                        ANNOUNCED_AT, LimitOrder.State.OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservedCash");
    }

    @Test
    void sell_order_rejects_buy_only_money_fields() {
        assertThatThrownBy(() -> new LimitOrder(
                        UUID.randomUUID(), COMPANY_ID, "BS000001", UUID.randomUUID(), LimitOrder.Side.SELL,
                        Money.ofMinor(10), 10, 10, 1, Money.ofMinor(1), Money.zero(), Money.zero(), 0,
                        ANNOUNCED_AT, LimitOrder.State.OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sell");
    }

    @Test
    void filled_buy_order_rejects_zero_notional_after_shares_filled() {
        assertThatThrownBy(() -> new LimitOrder(
                        UUID.randomUUID(), COMPANY_ID, "BS000001", UUID.randomUUID(), LimitOrder.Side.BUY,
                        Money.ofMinor(10), 10, 0, 1, Money.zero(), Money.zero(), Money.zero(), 100,
                        ANNOUNCED_AT, LimitOrder.State.FILLED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filledNotional");
    }

    @Test
    void trade_rejects_out_of_range_code_identical_orders_and_inexact_notional() {
        UUID orderId = UUID.randomUUID();
        assertThatThrownBy(() -> new Trade(UUID.randomUUID(), COMPANY_ID, "BS000000", orderId, orderId,
                        2, Money.ofMinor(10), Money.ofMinor(19), Money.zero(), ANNOUNCED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stockCode");
        assertThatThrownBy(() -> new Trade(UUID.randomUUID(), COMPANY_ID, "BS000001", orderId, orderId,
                        2, Money.ofMinor(10), Money.ofMinor(20), Money.zero(), ANNOUNCED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyOrderId");
        assertThatThrownBy(() -> new Trade(UUID.randomUUID(), COMPANY_ID, "BS000001", orderId, UUID.randomUUID(),
                        2, Money.ofMinor(10), Money.ofMinor(19), Money.zero(), ANNOUNCED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notional");
    }
}
