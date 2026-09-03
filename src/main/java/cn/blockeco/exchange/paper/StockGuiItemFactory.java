package cn.blockeco.exchange.paper;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds display-only items; routing data is a namespaced tag, never player-controlled lore. */
final class StockGuiItemFactory {
    private final NamespacedKey actionKey;

    StockGuiItemFactory(JavaPlugin plugin) {
        actionKey = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "gui-action");
    }

    ItemStack action(Material material, String action, Component name, List<Component> lore) {
        return action(material, action, null, name, lore);
    }

    ItemStack action(Material material, String action, String visualRole, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name); meta.lore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack filler() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta(); meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }
}
