package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

class BlockStockTerminalThemeTest {
    @Test void terminal_frame_reserves_dark_top_and_bottom_bars_with_a_center_title_slot() {
        var frame = BlockStockTerminalTheme.frameSlots();

        assertThat(frame).hasSize(18);
        assertThat(frame.get(0)).isEqualTo(new BlockStockTerminalTheme.FrameSlot(0, Material.BLACK_STAINED_GLASS_PANE));
        assertThat(frame.get(4)).isEqualTo(new BlockStockTerminalTheme.FrameSlot(4, Material.NETHER_STAR));
        assertThat(frame.get(17)).isEqualTo(new BlockStockTerminalTheme.FrameSlot(53, Material.BLACK_STAINED_GLASS_PANE));
    }

    @Test void terminal_skin_assigns_stable_models_to_trade_buttons_and_dark_slots() {
        assertThat(BlockStockTerminalTheme.itemModelFor(Material.LIME_WOOL, "detail:buy"))
                .isEqualTo(new NamespacedKey("blockstock", "buy_action"));
        assertThat(BlockStockTerminalTheme.itemModelFor(Material.RED_WOOL, "detail:sell"))
                .isEqualTo(new NamespacedKey("blockstock", "sell_action"));
        assertThat(BlockStockTerminalTheme.itemModelFor(Material.LIME_STAINED_GLASS_PANE, "noop"))
                .isEqualTo(new NamespacedKey("blockstock", "bid_level"));
        assertThat(BlockStockTerminalTheme.itemModelFor(Material.RED_STAINED_GLASS_PANE, "noop"))
                .isEqualTo(new NamespacedKey("blockstock", "ask_level"));
        assertThat(BlockStockTerminalTheme.itemModelFor(Material.BLACK_STAINED_GLASS_PANE, "noop"))
                .isEqualTo(new NamespacedKey("blockstock", "slot_dark"));
    }

    @Test void resource_pack_manifest_lists_every_terminal_model_needed_by_the_item_factory() {
        assertThat(BlockStockTerminalTheme.resourcePackModelNames())
                .contains("slot_dark", "terminal_tile", "buy_action", "sell_action", "bid_level", "ask_level", "chart_up", "chart_down", "volume_tile", "market_up", "market_down");
    }

    @Test void terminal_component_roles_map_to_distinct_resource_pack_models() {
        assertThat(BlockStockTerminalTheme.modelForRole("bid_level"))
                .isEqualTo(new NamespacedKey("blockstock", "bid_level"));
        assertThat(BlockStockTerminalTheme.modelForRole("chart_up"))
                .isEqualTo(new NamespacedKey("blockstock", "chart_up"));
        assertThat(BlockStockTerminalTheme.modelForRole("sell_action"))
                .isEqualTo(new NamespacedKey("blockstock", "sell_action"));
    }
}
