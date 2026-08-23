package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyGuiSessionTest {
    @Test
    void replacing_a_company_gui_page_invalidates_the_previous_confirmation_session() {
        UUID player = UUID.randomUUID();
        CompanyGuiSession first = CompanyGuiSession.open(player)
                .next(CompanyGuiSession.Page.CONFIRM_CREATE,
                        new CompanyGuiSession.CompanyDraft("红石工坊", Money.ofMinor(1_000_000), 50));
        CompanyGuiSession replacement = first.next(CompanyGuiSession.Page.HOME, null);

        assertThat(first.id()).isNotEqualTo(replacement.id());
        assertThat(first.belongsTo(player)).isTrue();
        assertThat(replacement.draft()).isNull();
    }

    @Test
    void native_asset_binding_draft_keeps_the_verified_adapter_and_key_until_confirmation() {
        UUID player = UUID.randomUUID();
        CompanyGuiSession session = CompanyGuiSession.open(player).next(CompanyGuiSession.Page.CONFIRM_BIND,
                new CompanyGuiSession.AssetBindingDraft("blockstock-native", "asset-42", "熔炉产线"));

        assertThat(session.draft()).isEqualTo(new CompanyGuiSession.AssetBindingDraft("blockstock-native", "asset-42", "熔炉产线"));
    }
}
