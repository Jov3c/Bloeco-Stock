package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagesTest {
    @Test
    void default_config_exposes_chinese_messages_and_preserves_placeholders() throws Exception {
        String config = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(config).contains("公司创建正在处理中", "%name%", "%state%", "%id%");
        assertThat(config).doesNotContain("Company registration is processing.");
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
        assertThat(plain(messages.usageCreate())).isEqualTo("用法：/company create <名称> <30|50|70>");
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
