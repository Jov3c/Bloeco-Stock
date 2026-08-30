package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.governance.IssuanceProposalState;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IssuanceGuiControllerTest {
    @Test
    void proposalVoteAndSubscriptionPagesUseChineseCopyAndBackNavigation() {
        UUID proposal = UUID.randomUUID();
        var voting = new IssuanceGuiController.ProposalView(proposal, "星河公司", 500, Money.ofMinor(1250),
                IssuanceProposalState.VOTING, java.time.Instant.parse("2026-08-30T00:00:00Z"), 4_500, 80, 300, 180, 90, 30);

        List<IssuanceGuiController.Slot> voteSlots = IssuanceGuiController.proposalSlots(voting, 2);

        assertThat(voteSlots).extracting(IssuanceGuiController.Slot::action)
                .contains("vote:YES", "vote:NO", "vote:ABSTAIN");
        assertThat(voteSlots).extracting(IssuanceGuiController.Slot::lore)
                .anyMatch(copy -> copy.contains("登记日股份"));
        assertThat(voteSlots).extracting(IssuanceGuiController.Slot::name)
                .contains("发行与稀释");

        var subscribing = new IssuanceGuiController.ProposalView(proposal, "星河公司", 500, Money.ofMinor(1250),
                IssuanceProposalState.SUBSCRIBING, java.time.Instant.parse("2026-08-30T00:00:00Z"), 4_500, 0, 300, 180, 90, 30);
        List<IssuanceGuiController.Slot> subscriptionSlots = IssuanceGuiController.proposalSlots(subscribing, 2);

        assertThat(subscriptionSlots).extracting(IssuanceGuiController.Slot::action).contains("subscribe");
        assertThat(subscriptionSlots).extracting(IssuanceGuiController.Slot::lore)
                .anyMatch(copy -> copy.contains("证券账户"));
        assertThat(IssuanceGuiController.dilution(subscribing)).isEqualTo("10.0%");
        assertThat(IssuanceGuiController.deadline(subscribing)).contains("09-03", "服务器时间");
    }

    @Test
    void exchangeHomeExposesPublicIssuanceMarketToEveryPlayer() {
        assertThat(StockGuiController.homeSlots()).extracting(StockGuiController.HomeSlot::action)
                .contains("issuance");
    }

    @Test
    void founderProposalCompositionDoesNotDeadlockOnSingleSqlExecutor() throws Exception {
        ExecutorService singleSqlExecutor = Executors.newSingleThreadExecutor();
        try {
            UUID founder = UUID.randomUUID();
            Company company = Company.register(new CompanyId(UUID.randomUUID()), "星河公司", founder, Money.ofMinor(100),
                    cn.blockeco.exchange.domain.company.DividendRate.FIFTY, Instant.EPOCH);
            var result = IssuanceGuiController.composeFounderProposal(
                    CompletableFuture.supplyAsync(() -> Optional.of(company), singleSqlExecutor),
                    selected -> selected.displayName() + "-提案", singleSqlExecutor);

            assertThat(result.toCompletableFuture().get(2, TimeUnit.SECONDS)).isEqualTo("星河公司-提案");
        } finally { singleSqlExecutor.shutdownNow(); }
    }

    @Test
    void publicProposalPaginationCanAdvanceBeyondSecondPage() {
        assertThat(IssuanceGuiController.nextPage(1)).isEqualTo(2);
        assertThat(IssuanceGuiController.previousPage(2)).isEqualTo(1);
        assertThat(IssuanceGuiController.previousPage(0)).isZero();
    }
}
