package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.application.PublicMarketRow;
import cn.blockeco.exchange.application.SecuritiesCashResult;
import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState;
import java.math.BigDecimal;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagesTest {
    @Test
    void default_config_exposes_chinese_messages_and_preserves_placeholders() throws Exception {
        String config = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(config).contains("公司创建正在处理中", "%name%", "%state%", "%id%",
                "deposit <金额>", "withdraw <金额>", "buy <代码>", "sell <代码>",
                "cancel <订单UUID>", "portfolio", "orders [1-50]", "trades [1-50]", "book <代码>");
        assertThat(config).doesNotContain("Company registration is processing.");
    }

    @Test
    void actual_default_config_renders_secondary_usage_and_live_market_fields() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        Messages messages = new Messages(config);
        PublicMarketRow row = new PublicMarketRow("红石工业", "BS000001", Money.ofMinor(1000),
                Money.ofMinor(1400), 10, CompanyStatus.LISTED, Money.ofMinor(140), Money.ofMinor(20),
                7, Money.ofMinor(980));

        assertThat(plain(messages.usageStock())).contains("market", "book", "cash", "deposit", "withdraw",
                "buy", "sell", "cancel", "portfolio", "orders", "trades");
        assertThat(plain(messages.marketRow(row))).contains("最新=1.40", "涨跌=+0.20", "成交量=7",
                "成交额=9.80", "市值=14.00").doesNotContain("暂无成交");
    }

    @Test
    void cash_result_never_exposes_provider_detail_and_uses_controlled_chinese_outcome() {
        Messages messages = new Messages(null);
        SecuritiesCashResult result = new SecuritiesCashResult(java.util.UUID.randomUUID(),
                SecuritiesCashOperationState.AMBIGUOUS, "Vault provider IllegalStateException: secret");

        assertThat(plain(messages.cashResult(result))).contains("待人工核对", "请勿重复提交")
                .doesNotContain("Vault", "provider", "secret", "IllegalStateException");
    }

    @Test
    void distinguishes_secondary_open_order_from_open_ipo() {
        Messages messages = new Messages(null);
        cn.blockeco.exchange.application.OrderView order = new cn.blockeco.exchange.application.OrderView(
                java.util.UUID.randomUUID(), "BS000001", cn.blockeco.exchange.domain.trading.LimitOrder.Side.BUY,
                Money.ofMinor(100), 2, 2, Money.ofMinor(200), java.time.Instant.parse("2026-08-22T00:00:00Z"),
                cn.blockeco.exchange.domain.trading.LimitOrder.State.OPEN);
        cn.blockeco.exchange.domain.finance.PublicOfferingView ipo = new cn.blockeco.exchange.domain.finance.PublicOfferingView(
                java.util.UUID.randomUUID(), new cn.blockeco.exchange.domain.company.CompanyId(java.util.UUID.randomUUID()),
                "红石工业", cn.blockeco.exchange.domain.finance.PrimaryOfferingState.OPEN,
                Money.ofMinor(1000), Money.ofMinor(100), 10, 0, 0, 10,
                java.time.Instant.parse("2026-08-20T00:00:00Z"), java.time.Instant.parse("2026-08-21T00:00:00Z"),
                java.time.Instant.parse("2026-08-23T00:00:00Z"));

        assertThat(plain(messages.order(order))).contains("状态=待成交").doesNotContain("开放认购");
        assertThat(plain(messages.publicIpo(ipo))).contains("状态=开放认购").doesNotContain("待成交");
    }

    @Test
    void localizes_company_and_recovery_states_without_localizing_storage_values() {
        Messages messages = new Messages(null);

        assertThat(plain(messages.companyInfo("红石工坊", CompanyStatus.PENDING_ASSET_BINDING)))
                .isEqualTo("红石工坊 — 待绑定资产");
        assertThat(plain(messages.recoveryRecord("id", "玩家", 1100000L,
                RegistrationSagaState.REFUND_REQUIRED, "时间", "原因")))
                .contains("状态=待人工退款");
        assertThat(RegistrationSagaState.REFUND_REQUIRED.name()).isEqualTo("REFUND_REQUIRED");
    }

    @ParameterizedTest
    @MethodSource("companyStates")
    void displays_every_company_status_in_simplified_chinese(CompanyStatus state, String displayName) {
        assertThat(plain(new Messages(null).companyInfo("红石工坊", state)))
                .isEqualTo("红石工坊 — " + displayName);
    }

    @ParameterizedTest
    @MethodSource("registrationStates")
    void displays_every_registration_state_in_simplified_chinese(RegistrationSagaState state, String displayName) {
        assertThat(plain(new Messages(null).recoveryRecord("id", "玩家", 1100000L,
                state, "时间", "原因")))
                .isEqualTo("编号=id 玩家=玩家 金额=1100000 状态=" + displayName + " 时间=时间 原因=原因");
    }

    @Test
    void displays_unknown_states_without_translation() {
        Messages messages = new Messages(null);

        assertThat(plain(messages.companyInfo("红石工坊", "UNRECOGNIZED_STATE")))
                .isEqualTo("红石工坊 — UNRECOGNIZED_STATE");
        assertThat(plain(messages.recoveryRecord("id", "玩家", 1100000L,
                "UNRECOGNIZED_STATE", "时间", "原因")))
                .contains("状态=UNRECOGNIZED_STATE");
    }

    @Test
    void substitutes_localized_states_in_configured_templates_without_altering_other_placeholders() {
        ConfigurationSection config = mock(ConfigurationSection.class);
        when(config.getString("company-info", "%name% — %state%"))
                .thenReturn("企业[%name%] 状态[%state%]");
        when(config.getString("recovery-record", "编号=%id% 玩家=%player% 金额=%amount% 状态=%state% 时间=%time% 原因=%error%"))
                .thenReturn("编号:%id%;玩家:%player%;金额:%amount%;状态:%state%;时间:%time%;原因:%error%");
        Messages messages = new Messages(config);

        assertThat(plain(messages.companyInfo("红石工坊", CompanyStatus.LISTED)))
                .isEqualTo("企业[红石工坊] 状态[已上市]");
        assertThat(plain(messages.recoveryRecord("id-1", "玩家", 1100000L,
                RegistrationSagaState.AMBIGUOUS, "时间", "原因")))
                .isEqualTo("编号:id-1;玩家:玩家;金额:1100000;状态:待人工核对;时间:时间;原因:原因");
    }

    @Test
    void provides_simplified_chinese_for_every_unconfigured_fallback() {
        Messages messages = new Messages(null);

        assertThat(plain(messages.processing())).isEqualTo("公司注册正在处理中。");
        assertThat(plain(messages.initializing())).isEqualTo("BlockStock 正在初始化，请稍后再试。");
        assertThat(plain(messages.noPermission())).isEqualTo("你没有权限。");
        assertThat(plain(messages.playersOnly())).isEqualTo("此命令只能由玩家执行。");
        CompanyCreationRules rules = new CompanyCreationRules(Money.fromMajor(new BigDecimal("1000.00"), 2), Money.fromMajor(new BigDecimal("10000.00"), 2), 2, 1000, List.of(30, 50, 70));
        assertThat(plain(messages.usageCreate(rules))).isEqualTo("用法：/company create <名称> <实缴资本> <30|50|70>");
        assertThat(plain(messages.duplicateRequest())).isEqualTo("你的公司注册已在处理中。");
        assertThat(plain(messages.registrationFailed())).isEqualTo("注册失败，可能需要管理员恢复。");
        assertThat(plain(messages.registrationSuccess())).isEqualTo("公司注册已完成。");
        assertThat(plain(messages.insufficientFunds())).isEqualTo("余额不足。");
        assertThat(plain(messages.duplicateName())).isEqualTo("同名公司已存在。");
        assertThat(plain(messages.refunded())).isEqualTo("注册失败，付款已退款。");
        assertThat(plain(messages.recoveryRequired())).isEqualTo("注册需要管理员恢复。");
        assertThat(plain(messages.lookupFailed())).isEqualTo("公司查询失败。");
        assertThat(plain(messages.companyNotFound())).isEqualTo("未找到公司。");
        assertThat(plain(messages.companyInfo("名称", CompanyStatus.LISTED))).isEqualTo("名称 — 已上市");
        assertThat(plain(messages.recoveryLookupFailed())).isEqualTo("恢复记录查询失败。");
        assertThat(plain(messages.noRecoveryRecords())).isEqualTo("没有恢复记录。");
        assertThat(plain(messages.recoveryRecord("id", "玩家", 1L, CompanyStatus.LISTED, "时间", "原因")))
                .isEqualTo("编号=id 玩家=玩家 金额=1 状态=已上市 时间=时间 原因=原因");
    }

    @Test void stock_money_uses_root_currency_scale_and_plain_major_units() {
        ConfigurationSection root = mock(ConfigurationSection.class);
        when(root.getInt("currency.scale", 2)).thenReturn(2);
        Messages messages = new Messages(root);
        PublicMarketRow row = new PublicMarketRow("红石工业", "BS000001", Money.ofMinor(1000), Money.ofMinor(123400), 10, CompanyStatus.LISTED,
                Money.ofMinor(1234), Money.ofMinor(-12), 42, Money.ofMinor(51828));

        assertThat(plain(messages.marketRow(row))).contains("最新=12.34", "涨跌=-0.12", "成交额=518.28", "市值=1234.00")
                .doesNotContain("暂无成交", "=1000", "=123400");
    }

    private static Stream<Arguments> companyStates() {
        return Stream.of(
                Arguments.of(CompanyStatus.PENDING_ASSET_BINDING, "待绑定资产"),
                Arguments.of(CompanyStatus.LISTED, "已上市"),
                Arguments.of(CompanyStatus.DELISTING, "退市中"),
                Arguments.of(CompanyStatus.LIQUIDATING, "清算中"),
                Arguments.of(CompanyStatus.DELISTED, "已退市"));
    }

    private static Stream<Arguments> registrationStates() {
        return Stream.of(
                Arguments.of(RegistrationSagaState.PREPARED, "已准备"),
                Arguments.of(RegistrationSagaState.WITHDRAWN, "已扣款"),
                Arguments.of(RegistrationSagaState.COMPLETED, "已完成"),
                Arguments.of(RegistrationSagaState.REFUND_REQUIRED, "待人工退款"),
                Arguments.of(RegistrationSagaState.REFUNDED, "已退款"),
                Arguments.of(RegistrationSagaState.AMBIGUOUS, "待人工核对"),
                Arguments.of(RegistrationSagaState.REJECTED, "已拒绝"));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
