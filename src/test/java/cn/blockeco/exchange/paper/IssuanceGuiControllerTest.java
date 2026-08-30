package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.governance.IssuanceProposalState;
import cn.blockeco.exchange.domain.money.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssuanceGuiControllerTest {
    @Test
    void proposalVoteAndSubscriptionPagesUseChineseCopyAndBackNavigation() {
        UUID proposal = UUID.randomUUID();
        var voting = new IssuanceGuiController.ProposalView(proposal, "星河公司", 500, Money.ofMinor(1250),
                IssuanceProposalState.VOTING, 80, 300, 180, 90, 30);

        List<IssuanceGuiController.Slot> voteSlots = IssuanceGuiController.proposalSlots(voting, 2);

        assertThat(voteSlots).extracting(IssuanceGuiController.Slot::action)
                .contains("vote:YES", "vote:NO", "vote:ABSTAIN", "back:company");
        assertThat(voteSlots).extracting(IssuanceGuiController.Slot::lore)
                .anyMatch(copy -> copy.contains("登记日股份"));
        assertThat(voteSlots).extracting(IssuanceGuiController.Slot::name)
                .contains("返回公司中心");

        var subscribing = new IssuanceGuiController.ProposalView(proposal, "星河公司", 500, Money.ofMinor(1250),
                IssuanceProposalState.SUBSCRIBING, 0, 300, 180, 90, 30);
        List<IssuanceGuiController.Slot> subscriptionSlots = IssuanceGuiController.proposalSlots(subscribing, 2);

        assertThat(subscriptionSlots).extracting(IssuanceGuiController.Slot::action).contains("subscribe");
        assertThat(subscriptionSlots).extracting(IssuanceGuiController.Slot::lore)
                .anyMatch(copy -> copy.contains("证券账户"));
    }
}
