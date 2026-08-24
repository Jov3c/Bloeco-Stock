package cn.blockeco.exchange.paper;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Real server-container anvil text input; works with unmodified vanilla clients. */
final class TextInputGui {
    private TextInputGui() { }

    static void open(JavaPlugin plugin, Player player, String title, Consumer<String> submitted) {
        Bukkit.getScheduler().runTask(plugin, () -> new AnvilGUI.Builder()
                .plugin(plugin)
                .title(title)
                .text(" ")
                .onClick((slot, state) -> {
                    if (slot != AnvilGUI.Slot.OUTPUT) return Collections.emptyList();
                    String value = state.getText().trim();
                    if (value.isEmpty()) return List.of(AnvilGUI.ResponseAction.replaceInputText(" "));
                    return List.of(AnvilGUI.ResponseAction.close(), AnvilGUI.ResponseAction.run(() ->
                            Bukkit.getScheduler().runTask(plugin, () -> submitted.accept(value))));
                })
                .open(player));
    }
}
