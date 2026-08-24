package cn.blockeco.exchange.paper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** Permission-gated operational controls for the system-owned bluechip market. */
public final class BluechipAdminCommand implements CommandExecutor, TabCompleter {
    public static final String PERMISSION = "blockeco.admin.bluechip";
    private final Initializer initializer; private final PauseControl quotes; private final FundControl fund; private final EventControl events; private final Messages messages; private final List<String> industries;
    public BluechipAdminCommand(Initializer initializer, PauseControl quotes, FundControl fund, EventControl events, Messages messages) { this(initializer, quotes, fund, events, messages, List.of()); }
    public BluechipAdminCommand(Initializer initializer, PauseControl quotes, FundControl fund, EventControl events, Messages messages, List<String> industries) { this.initializer=Objects.requireNonNull(initializer);this.quotes=Objects.requireNonNull(quotes);this.fund=Objects.requireNonNull(fund);this.events=Objects.requireNonNull(events);this.messages=Objects.requireNonNull(messages);this.industries=List.copyOf(Objects.requireNonNull(industries)); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) { sender.sendMessage(messages.noPermission()); return true; }
        if (args.length < 2 || !"bluechip".equalsIgnoreCase(args[0])) { usage(sender); return true; }
        try { switch (args[1].toLowerCase(Locale.ROOT)) {
            case "init" -> { if(args.length!=2){usage(sender);break;} initializer.initialize(); sender.sendMessage(messages.result("蓝筹初始化已提交。")); }
            case "pause" -> { if(args.length!=2){usage(sender);break;} quotes.setPaused(true); sender.sendMessage(messages.result("蓝筹报价已暂停。")); }
            case "resume" -> { if(args.length!=2){usage(sender);break;} quotes.setPaused(false); sender.sendMessage(messages.result("蓝筹报价已恢复。")); }
            case "fund" -> { if(args.length!=5){usage(sender);break;} long value=Long.parseLong(args[4]); if(value==0){sender.sendMessage(messages.result("资金调整不能为零。"));break;} fund.adjust(args[2].toUpperCase(Locale.ROOT),args[3].toLowerCase(Locale.ROOT),value);sender.sendMessage(messages.result("蓝筹资金已调整。")); }
            case "event" -> { if(args.length<3){usage(sender);break;} if("industry".equalsIgnoreCase(args[2])) { if(args.length!=5){usage(sender);break;} if(!industries.contains(args[3])) throw new IllegalArgumentException("unknown industry"); events.industry(args[3],Integer.parseInt(args[4])); } else if("market".equalsIgnoreCase(args[2])) { if(args.length!=4){usage(sender);break;} events.market(Integer.parseInt(args[3])); } else { if(args.length!=4){usage(sender);break;} events.company(args[2],Integer.parseInt(args[3])); } sender.sendMessage(messages.result("蓝筹事件已触发。")); }
            default -> usage(sender);
        }} catch (IllegalArgumentException failure) { sender.sendMessage(messages.result("蓝筹参数无效，调整后余额不得为负。")); } catch (RuntimeException failure) { sender.sendMessage(messages.result("蓝筹操作失败，请检查代码和状态。")); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { if(!sender.hasPermission(PERMISSION))return List.of(); if(args.length==1)return filter(List.of("bluechip"),args[0]); if(args.length==2&&"bluechip".equalsIgnoreCase(args[0]))return filter(List.of("init","pause","resume","fund","event"),args[1]); if(args.length==4&&"fund".equalsIgnoreCase(args[1]))return filter(List.of("cash","shares"),args[3]); if(args.length==3&&"event".equalsIgnoreCase(args[1]))return filter(List.of("market","industry"),args[2]); if(args.length==4&&"event".equalsIgnoreCase(args[1])&&"industry".equalsIgnoreCase(args[2]))return filter(industries,args[3]); return List.of(); }
    private void usage(CommandSender sender){sender.sendMessage(messages.result("用法：/stockadmin bluechip <init|pause|resume|fund <代码> <cash|shares> <值>|event <代码|market> <影响万分比>|event industry <行业> <影响万分比>>"));}
    private static List<String> filter(List<String> values,String prefix){return values.stream().filter(v->v.regionMatches(true,0,prefix,0,prefix.length())).toList();}
    @FunctionalInterface public interface Initializer { void initialize(); }
    @FunctionalInterface public interface PauseControl { void setPaused(boolean paused); }
    @FunctionalInterface public interface FundControl { void adjust(String code,String kind,long value); }
    public interface EventControl { void company(String code, int impact); void industry(String industry, int impact); void market(int impact); }
}
