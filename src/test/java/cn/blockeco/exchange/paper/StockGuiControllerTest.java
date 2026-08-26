package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.time.LocalDate;
import java.util.List;
import cn.blockeco.exchange.application.MarketChart;
import cn.blockeco.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;

class StockGuiControllerTest {
    @Test void daily_and_intraday_modes_render_different_chart_content() {
        MarketChart chart = chartWithFiveCandlesAndThreePoints();

        assertThat(StockGuiController.chartLore(chart, 2, StockGuiSession.ChartMode.DAILY)).contains("日K线", "量99");
        assertThat(StockGuiController.chartLore(chart, 2, StockGuiSession.ChartMode.INTRADAY)).contains("分时线", "08:00", "走势");
    }
    @Test void detailChartLoreShowsSimpleIntradayAndDailyKlineWithoutIndicators() {
        var chart = new MarketChart(LocalDate.of(2026,8,24), new MarketChart.SessionSummary(Money.ofMinor(1000),Money.ofMinor(1200),Money.ofMinor(900),Money.ofMinor(1100),42), List.of(new MarketChart.DailyCandle(LocalDate.of(2026,8,23),Money.ofMinor(800),Money.ofMinor(1100),Money.ofMinor(700),Money.ofMinor(1000),99)));

        assertThat(StockGuiController.chartLore(chart, 2)).contains("分时线 · 2026-08-24", "开 10.00  高 12.00  低 9.00  现 11.00", "成交量 42 股", "日K线（最近 1 日）", "08-23 开8.00 高11.00 低7.00 收10.00 量99");
    }
    @Test void detailChartLoreRendersAnOrderedIntradaySparklineAndTimestampedPoints() {
        var chart = new MarketChart(LocalDate.of(2026,8,24), new MarketChart.SessionSummary(Money.ofMinor(1000),Money.ofMinor(1200),Money.ofMinor(900),Money.ofMinor(1100),42), List.of(), List.of(
                new MarketChart.IntradayPoint("08:00", Money.ofMinor(1000), 3), new MarketChart.IntradayPoint("08:30", Money.ofMinor(1200), 5), new MarketChart.IntradayPoint("09:00", Money.ofMinor(1100), 7)));

        assertThat(StockGuiController.chartLore(chart, 2)).contains("走势 ▁█▅", "08:00 10.00 · 3股", "08:30 12.00 · 5股", "09:00 11.00 · 7股");
    }
    @Test void marketNewsHasItsOwnVanillaInventorySessionAndCanReturnHome() {
        StockGuiController controller = StockGuiController.forSessionTests();
        UUID player = UUID.randomUUID();

        StockGuiSession news = controller.openSession(player, StockGuiSession.Page.NEWS, 0, null, null);
        StockGuiSession home = controller.openSession(player, StockGuiSession.Page.HOME, 0, null, null);

        assertThat(news.page()).isEqualTo(StockGuiSession.Page.NEWS);
        assertThat(controller.matches(player, news.id())).isFalse();
        assertThat(controller.matches(player, home.id())).isTrue();
    }
    @Test void an_old_session_cannot_match_after_the_player_opens_a_new_page() {
        StockGuiController controller = StockGuiController.forSessionTests();
        UUID player = UUID.randomUUID();
        StockGuiSession first = controller.openSession(player, StockGuiSession.Page.HOME, 0, null, null);
        StockGuiSession second = controller.openSession(player, StockGuiSession.Page.MARKET, 0, null, null);

        assertThat(controller.matches(player, first.id())).isFalse();
        assertThat(controller.matches(player, second.id())).isTrue();
    }

    @Test void inventory_replacement_does_not_clear_the_current_session_but_a_real_close_does() {
        StockGuiController controller = StockGuiController.forSessionTests();
        UUID player = UUID.randomUUID();
        StockGuiSession session = controller.openSession(player, StockGuiSession.Page.MARKET, 0, null, null);

        controller.beginInventoryReplacement(player);
        assertThat(controller.shouldClearOnClose(player, session.id())).isFalse();
        controller.endInventoryReplacement(player);

        assertThat(controller.shouldClearOnClose(player, session.id())).isTrue();
    }

    @Test void confirmation_is_consumed_before_its_async_operation_completes() {
        var gui = StockGuiController.forSessionTests(); UUID player = UUID.randomUUID();
        StockGuiSession confirm = gui.openSession(player, StockGuiSession.Page.CONFIRM, 0, null,
                new StockGuiSession.CashTransfer(true, Money.ofMinor(100)));

        assertThat(gui.beginSubmission(player, confirm.id())).isTrue();
        assertThat(gui.beginSubmission(player, confirm.id())).isFalse();
        assertThat(gui.currentPage(player)).isEqualTo(StockGuiSession.Page.SUBMITTING);
    }

    private static MarketChart chartWithFiveCandlesAndThreePoints() {
        return new MarketChart(LocalDate.of(2026, 8, 24),
                new MarketChart.SessionSummary(Money.ofMinor(1000), Money.ofMinor(1200), Money.ofMinor(900), Money.ofMinor(1100), 42),
                List.of(
                        new MarketChart.DailyCandle(LocalDate.of(2026, 8, 20), Money.ofMinor(800), Money.ofMinor(1100), Money.ofMinor(700), Money.ofMinor(1000), 99),
                        new MarketChart.DailyCandle(LocalDate.of(2026, 8, 21), Money.ofMinor(900), Money.ofMinor(1200), Money.ofMinor(800), Money.ofMinor(1100), 88),
                        new MarketChart.DailyCandle(LocalDate.of(2026, 8, 22), Money.ofMinor(1000), Money.ofMinor(1300), Money.ofMinor(900), Money.ofMinor(1200), 77),
                        new MarketChart.DailyCandle(LocalDate.of(2026, 8, 23), Money.ofMinor(1100), Money.ofMinor(1400), Money.ofMinor(1000), Money.ofMinor(1300), 66),
                        new MarketChart.DailyCandle(LocalDate.of(2026, 8, 24), Money.ofMinor(1200), Money.ofMinor(1500), Money.ofMinor(1100), Money.ofMinor(1400), 55)),
                List.of(new MarketChart.IntradayPoint("08:00", Money.ofMinor(1000), 3), new MarketChart.IntradayPoint("08:30", Money.ofMinor(1200), 5), new MarketChart.IntradayPoint("09:00", Money.ofMinor(1100), 7)));
    }
}
