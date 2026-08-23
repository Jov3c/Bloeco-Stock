package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Read-only company center; subsequent GUI workflows are owner/session-bound here. */
public final class CompanyGuiController implements CompanyGuiOpener {
    private final CompanyQueryService companies; private final MainThreadExecutor main; private final BooleanSupplier accepting; private final Messages messages;
    private final ConcurrentHashMap<UUID, UUID> sessions = new ConcurrentHashMap<>();
    public CompanyGuiController(CompanyQueryService companies, MainThreadExecutor main, BooleanSupplier accepting, Messages messages) { this.companies=companies;this.main=main;this.accepting=accepting;this.messages=messages; }
    @Override public void open(Player player) {
        if(!accepting.getAsBoolean()){player.sendMessage(messages.initializing());return;}
        UUID session=UUID.randomUUID();sessions.put(player.getUniqueId(),session); Inventory loading=Bukkit.createInventory(new Holder(player.getUniqueId(),session),54,Component.text("BlockStock 公司中心"));loading.setItem(22,item(Material.CLOCK,"正在加载公司信息…","请稍候"));player.openInventory(loading);
        companies.findByFounder(player.getUniqueId()).whenComplete((company,error)->main.submit(()->{if(!player.isOnline()||!accepting.getAsBoolean()||!session.equals(sessions.get(player.getUniqueId())))return null;Inventory inv=Bukkit.createInventory(new Holder(player.getUniqueId(),session),54,Component.text("BlockStock 公司中心"));if(error!=null){inv.setItem(22,item(Material.BARRIER,"查询失败","请稍后重试"));}else if(company.isEmpty()){inv.setItem(22,item(Material.NETHER_STAR,"创建公司","公司创建 GUI 即将开放"));}else{var c=company.get();inv.setItem(13,item(Material.NAME_TAG,c.displayName(),"状态："+c.status()));inv.setItem(29,item(Material.CHEST,"资产管理","原生资产与外部资产绑定"));inv.setItem(31,item(Material.PAPER,"IPO 管理","发行、公告与认购"));inv.setItem(33,item(Material.BOOK,"公告与财报","查看公司披露"));}inv.setItem(49,item(Material.BARRIER,"关闭","关闭公司中心"));player.openInventory(inv);return null;}));
    }
    private static org.bukkit.inventory.ItemStack item(Material type,String title,String lore){var item=new org.bukkit.inventory.ItemStack(type);var meta=item.getItemMeta();meta.displayName(Component.text(title));meta.lore(java.util.List.of(Component.text(lore)));item.setItemMeta(meta);return item;}
    private record Holder(UUID owner, UUID session) implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
}
