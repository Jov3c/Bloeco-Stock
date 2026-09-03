package cn.blockeco.exchange.paper;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;

/** Shared vanilla-inventory skin for every BlockStock page. */
final class BlockStockTerminalTheme {
    private BlockStockTerminalTheme() { }

    static List<FrameSlot> frameSlots() {
        var slots = new java.util.ArrayList<FrameSlot>();
        for (int slot = 0; slot <= 8; slot++) slots.add(new FrameSlot(slot, slot == 4 ? Material.NETHER_STAR : Material.BLACK_STAINED_GLASS_PANE));
        for (int slot = 45; slot <= 53; slot++) slots.add(new FrameSlot(slot, Material.BLACK_STAINED_GLASS_PANE));
        return List.copyOf(slots);
    }

    static void fill(Inventory inventory, StockGuiItemFactory items) {
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, items.filler());
        for (FrameSlot frame : frameSlots()) {
            String title = frame.slot() == 4 ? "Bloeco-Stock" : " ";
            inventory.setItem(frame.slot(), items.action(frame.material(), "noop", Component.text(title), List.of()));
        }
    }

    /** Maps existing vanilla GUI roles to resource-pack art without changing click routing. */
    static NamespacedKey itemModelFor(Material material, String action) {
        if ("detail:buy".equals(action) || "cash:deposit".equals(action) || "confirm".equals(action)) return modelForRole("buy_action");
        if ("detail:sell".equals(action) || "cash:withdraw".equals(action) || "cancel".equals(action)) return modelForRole("sell_action");
        if (material == Material.BLACK_STAINED_GLASS_PANE) return modelForRole("slot_dark");
        if (material == Material.LIME_STAINED_GLASS_PANE) return modelForRole("bid_level");
        if (material == Material.RED_STAINED_GLASS_PANE) return modelForRole("ask_level");
        if (material == Material.LIGHT_BLUE_STAINED_GLASS_PANE || material == Material.CYAN_STAINED_GLASS_PANE) return modelForRole("chart_up");
        return modelForRole("terminal_tile");
    }

    static NamespacedKey modelForRole(String role) {
        if (!resourcePackModelNames().contains(role)) throw new IllegalArgumentException("unknown terminal role: " + role);
        return key(role);
    }

    static List<String> resourcePackModelNames() {
        return List.of("slot_dark", "terminal_tile", "header", "quote_card", "info_card", "market_up", "market_down",
                "chart_up", "chart_down", "volume_tile", "bid_level", "ask_level", "buy_action", "sell_action", "help_action", "nav_action");
    }

    private static NamespacedKey key(String value) { return new NamespacedKey("blockstock", value); }

    record FrameSlot(int slot, Material material) { }
}
