package cn.blockeco.exchange.paper;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface CompanyGuiOpener {
    void open(Player player);
}
