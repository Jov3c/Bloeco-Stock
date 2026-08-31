package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import cn.blockeco.exchange.application.MarketChart;
import cn.blockeco.exchange.application.OrderBookLevel;
import cn.blockeco.exchange.application.SecondaryMarketQueryService;
import cn.blockeco.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;

class StockGuiControllerTest {
    @Test void live_market_refresh_uses_a_one_second_period() {
        assertThat(StockGuiController.liveRefreshPeriodTicks()).isEqualTo(20L);
    }

    @Test void home_menu_exposes_a_public_ipo_entry_for_investors_without_a_company() {
        assertThat(StockGuiController.homeSlots())
                .extracting(StockGuiController.HomeSlot::slot, StockGuiController.HomeSlot::action, StockGuiController.HomeSlot::name)
                .contains(org.assertj.core.groups.Tuple.tuple(22, "ipo", "公开 IPO"));
    }

    @Test void canonical_detail_slots_keep_every_depth_chart_and_control_slot_unique() {
        var book = new SecondaryMarketQueryService.OrderBook(
                List.of(new OrderBookLevel(Money.ofMinor(1050), 5), new OrderBookLevel(Money.ofMinor(1040), 4), new OrderBookLevel(Money.ofMinor(1030), 3), new OrderBookLevel(Money.ofMinor(1020), 2), new OrderBookLevel(Money.ofMinor(1010), 1)),
                List.of(new OrderBookLevel(Money.ofMinor(990), 1), new OrderBookLevel(Money.ofMinor(980), 2), new OrderBookLevel(Money.ofMinor(970), 3), new OrderBookLevel(Money.ofMinor(960), 4), new OrderBookLevel(Money.ofMinor(950), 5)));

        List<StockGuiController.DetailSlot> slots = StockGuiController.detailSlots(book, Money.ofMinor(1000), Money.ofMinor(25), 7, Money.ofMinor(9999), chartWithFiveCandlesAndThreePoints(), StockGuiSession.ChartMode.DAILY);
        Map<Integer, StockGuiController.DetailSlot> bySlot = slots.stream().collect(java.util.stream.Collectors.toMap(StockGuiController.DetailSlot::slot, slot -> slot));

        assertThat(bySlot).hasSize(slots.size());
        assertThat(bySlot).containsKeys(17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);
        assertThat(bySlot.get(15)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action)
                .containsExactly(org.bukkit.Material.CLOCK, "chart:intraday");
        assertThat(bySlot.get(16)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action)
                .containsExactly(org.bukkit.Material.ENCHANTED_BOOK, "chart:daily");
        for (int slot = 17; slot <= 21; slot++) assertThat(bySlot.get(slot)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.RED_STAINED_GLASS_PANE, "noop");
        assertThat(bySlot.get(22)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.GOLD_INGOT, "noop");
        for (int slot = 23; slot <= 27; slot++) assertThat(bySlot.get(slot)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.LIME_STAINED_GLASS_PANE, "noop");
        for (int slot = 28; slot <= 34; slot++) assertThat(bySlot.get(slot).action()).isEqualTo("noop");
        assertThat(bySlot.get(37)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.PAPER, "noop");
        assertThat(bySlot.get(38)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.GOLD_INGOT, "noop");
        assertThat(bySlot.get(39)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.BOOK, "noop");
        assertThat(bySlot.get(40)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.LIME_WOOL, "detail:buy");
        assertThat(bySlot.get(41)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.RED_WOOL, "detail:sell");
        assertThat(bySlot.get(42)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.BOOK, "help");
        assertThat(bySlot.get(43)).extracting(StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action).containsExactly(org.bukkit.Material.ARROW, "back:market");
    }

    @Test void detail_renderer_places_clickable_clock_and_book_controls_in_top_cards() {
        assertThat(StockGuiController.detailControls(StockGuiSession.ChartMode.INTRADAY))
                .extracting(StockGuiController.DetailSlot::slot, StockGuiController.DetailSlot::material, StockGuiController.DetailSlot::action)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(15, org.bukkit.Material.CLOCK, "chart:intraday"),
                        org.assertj.core.groups.Tuple.tuple(16, org.bukkit.Material.ENCHANTED_BOOK, "chart:daily"));
    }

    @Test void chart_control_action_changes_the_current_detail_session_mode() {
        StockGuiController controller = StockGuiController.forSessionTests();
        UUID player = UUID.randomUUID();
        StockGuiSession detail = controller.openSession(player, StockGuiSession.Page.DETAIL, 0, "NOVA", null);

        assertThat(controller.applyChartControl(player, detail.id(), "chart:daily")).isTrue();
        assertThat(controller.currentChartMode(player)).isEqualTo(StockGuiSession.ChartMode.DAILY);
        assertThat(controller.matches(player, detail.id())).isFalse();
        assertThat(controller.applyChartControl(player, detail.id(), "chart:intraday")).isFalse();
    }
    @Test void detail_layout_exposes_localized_identity_live_quote_holding_and_actions() {
        assertThat(StockGuiController.detailTitle("星铸工业", "NOVA")).isEqualTo("星铸工业 · NOVA");
        assertThat(StockGuiController.detailCardLabels(Money.ofMinor(1234), Money.ofMinor(-56), 80, Money.ofMinor(98765)))
                .containsExactly("最新 12.34", "涨跌 -0.56", "我的持仓 80 股", "今日成交额 987.65");
        assertThat(StockGuiController.detailActions()).contains("detail:buy", "detail:sell", "back:market", "help", "chart:daily", "chart:intraday");
    }

    @Test void detail_layout_keeps_five_independent_sell_and_buy_prices() {
        var book = new SecondaryMarketQueryService.OrderBook(
                List.of(new OrderBookLevel(Money.ofMinor(990), 10), new OrderBookLevel(Money.ofMinor(980), 9), new OrderBookLevel(Money.ofMinor(970), 8), new OrderBookLevel(Money.ofMinor(960), 7), new OrderBookLevel(Money.ofMinor(950), 6)),
                List.of(new OrderBookLevel(Money.ofMinor(1010), 10), new OrderBookLevel(Money.ofMinor(1020), 9), new OrderBookLevel(Money.ofMinor(1030), 8), new OrderBookLevel(Money.ofMinor(1040), 7), new OrderBookLevel(Money.ofMinor(1050), 6)));

        assertThat(StockGuiController.orderBookLabels(book, 2)).contains("卖1 10.10", "卖5 10.50", "买1 9.90", "买5 9.50");
    }

    @Test void chart_raster_uses_candles_for_daily_and_a_line_for_intraday() {
        MarketChart chart = chartWithFiveCandlesAndThreePoints();

        assertThat(StockGuiController.chartRaster(chart, StockGuiSession.ChartMode.DAILY))
                .isNotEqualTo(StockGuiController.chartRaster(chart, StockGuiSession.ChartMode.INTRADAY));
    }
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
