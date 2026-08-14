package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class MessagesTest {
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

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
