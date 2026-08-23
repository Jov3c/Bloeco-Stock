package cn.blockeco.exchange.paper;

import org.bukkit.entity.Player;

/** Narrow command-facing boundary for opening the vanilla exchange. */
@FunctionalInterface
public interface StockGuiOpener {
    void openHome(Player player);
}
