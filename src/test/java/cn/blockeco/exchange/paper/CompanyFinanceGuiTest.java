package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.ports.CompanyOperationsRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class CompanyFinanceGuiTest {
    @Test
    void financeGuiLabelsPersonalSecuritiesAndCompanyMoneySeparately() {
        assertThat(CompanyFinanceGui.mainPageText()).contains("公司账户", "证券账户", "个人钱包", "返回公司中心");
    }

    @Test
    void financeGuiStatesThatNativeBindingHasNoAutomaticRevenueSource() {
        assertThat(CompanyFinanceGui.nativeBindingNotice()).isEqualTo("已绑定，尚未接入自动收益来源");
    }

    @Test
    void financeGuiRendersLiveCompanyAmountsNextDividendAndSixReportHistory() {
        CompanyId company = new CompanyId(UUID.randomUUID());
        CompanyOperationsRepository.FinancialSnapshot snapshot = new CompanyOperationsRepository.FinancialSnapshot(company, 120, 35, 7, 90, 55);
        List<CompanyOperationsRepository.MonthlyReport> reports = java.util.stream.IntStream.range(0, 7).mapToObj(index -> new CompanyOperationsRepository.MonthlyReport(company, Instant.parse("2026-0" + (index + 1) + "-01T00:00:00Z"), Instant.EPOCH, index, 0, index, 35, 120, Instant.EPOCH)).toList();
        CompanyFinanceGui.FinanceView view = new CompanyFinanceGui.FinanceView(snapshot, Instant.parse("2026-09-15T00:00:00Z"), reports);
        assertThat(CompanyFinanceGui.mainPageText(view)).contains("可用公司资金：120", "留存收益：35", "累计亏损：7", "本月收入：90", "本月成本：55", "本月净利润：35", "下次分红：2026-09-15T00:00:00Z");
        assertThat(CompanyFinanceGui.historyPageText(view)).contains("最近 6 份月报").doesNotContain("2026-07-01T00:00:00Z");
    }
}
