package cn.blockeco.exchange.paper;

import net.kyori.adventure.text.Component;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Configuration-backed user messages; defaults keep the first deployment usable. */
public final class Messages {
    private final ConfigurationSection config;
    public Messages(ConfigurationSection config) { this.config = config; }
    private Component message(String key, String fallback) { return Component.text(config == null ? fallback : config.getString(key, fallback)); }
    public Component processing() { return message("processing", "公司注册正在处理中。"); }
    public Component initializing() { return message("initializing", "BlockStock 正在初始化，请稍后再试。"); }
    public Component noPermission() { return message("no-permission", "你没有权限。"); }
    public Component playersOnly() { return message("players-only", "此命令只能由玩家执行。"); }
    public Component usageCreate(CompanyCreationRules rules) { return message("usage-create", "用法：/company create <名称> <DIVIDENDS>").replaceText(b -> b.matchLiteral("DIVIDENDS").replacement(rules.dividendChoices())); }
    public Component usageInfo() { return message("usage-info", "用法：/company info <名称>"); }
    public Component usageRecovery() { return message("usage-recovery", "用法：/company recovery list"); }
    public List<Component> companyHelp(boolean canCreate, boolean canInfo, boolean canRecovery, CompanyCreationRules rules) {
        java.util.ArrayList<Component> lines = new java.util.ArrayList<>();
        lines.add(message("help-root", "BlockStock 公司命令："));
        if (canCreate) {
            lines.add(message("help-create", "/company create <名称> <DIVIDENDS>").replaceText(b -> b.matchLiteral("DIVIDENDS").replacement(rules.dividendChoices())));
            String createRules = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(template("help-create-rules", "创建费：%fee%\n最低注册资本：%capital%\n合计余额：%total%\n初始发行：%shares% 股\n必须是玩家\n插件完成初始化\n公司名不能重复", rules));
            for (String line : createRules.split("\\R")) lines.add(Component.text(line));
        }
        if (canInfo) lines.add(message("help-info", "查询：/company info <名称>"));
        if (canRecovery) lines.add(message("help-recovery", "恢复：/company recovery list"));
        return List.copyOf(lines);
    }
    private Component template(String key, String fallback, CompanyCreationRules rules) { return message(key, fallback).replaceText(b -> b.matchLiteral("%fee%").replacement(rules.registrationFeeMajor())).replaceText(b -> b.matchLiteral("%capital%").replacement(rules.minimumCapitalMajor())).replaceText(b -> b.matchLiteral("%total%").replacement(rules.totalRequiredMajor())).replaceText(b -> b.matchLiteral("%shares%").replacement(String.valueOf(rules.initialShares()))); }
    public Component duplicateRequest() { return message("duplicate-request", "你的公司注册已在处理中。"); }
    public Component registrationFailed() { return message("registration-failed", "注册失败，可能需要管理员恢复。"); }
    public Component registrationSuccess() { return message("registration-success", "公司注册已完成。"); }
    public Component insufficientFunds() { return message("insufficient-funds", "余额不足。"); }
    public Component duplicateName() { return message("duplicate-name", "同名公司已存在。"); }
    public Component refunded() { return message("refunded", "注册失败，付款已退款。"); }
    public Component recoveryRequired() { return message("recovery-required", "注册需要管理员恢复。"); }
    public Component lookupFailed() { return message("lookup-failed", "公司查询失败。"); }
    public Component companyNotFound() { return message("company-not-found", "未找到公司。"); }
    public Component companyInfo(String name, Object state) { return message("company-info", "%name% — %state%").replaceText(b -> b.matchLiteral("%name%").replacement(name)).replaceText(b -> b.matchLiteral("%state%").replacement(displayState(state))); }
    public Component recoveryLookupFailed() { return message("recovery-lookup-failed", "恢复记录查询失败。"); }
    public Component noRecoveryRecords() { return message("no-recovery-records", "没有恢复记录。"); }
    public Component recoveryRecord(Object id,Object player,Object amount,Object state,Object time,Object error) { return message("recovery-record", "编号=%id% 玩家=%player% 金额=%amount% 状态=%state% 时间=%time% 原因=%error%").replaceText(b->b.matchLiteral("%id%").replacement(String.valueOf(id))).replaceText(b->b.matchLiteral("%player%").replacement(String.valueOf(player))).replaceText(b->b.matchLiteral("%amount%").replacement(String.valueOf(amount))).replaceText(b->b.matchLiteral("%state%").replacement(displayState(state))).replaceText(b->b.matchLiteral("%time%").replacement(String.valueOf(time))).replaceText(b->b.matchLiteral("%error%").replacement(String.valueOf(error))); }
    public Component result(String text) { return Component.text(text); }

    private String displayState(Object state) {
        return switch (String.valueOf(state)) {
            case "PENDING_ASSET_BINDING" -> "待绑定资产";
            case "LISTED" -> "已上市";
            case "DELISTING" -> "退市中";
            case "LIQUIDATING" -> "清算中";
            case "DELISTED" -> "已退市";
            case "PREPARED" -> "已准备";
            case "WITHDRAWN" -> "已扣款";
            case "COMPLETED" -> "已完成";
            case "REFUND_REQUIRED" -> "待人工退款";
            case "REFUNDED" -> "已退款";
            case "AMBIGUOUS" -> "待人工核对";
            case "REJECTED" -> "已拒绝";
            default -> String.valueOf(state);
        };
    }
}
