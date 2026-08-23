package cn.blockeco.exchange.paper;

import org.bukkit.entity.Player;

/** Opens the vanilla IPO screens without exposing command syntax to players. */
public interface IpoGuiOpener {
    void openPublic(Player player);
    void openFounder(Player player);
}
