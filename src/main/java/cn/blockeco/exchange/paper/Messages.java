package cn.blockeco.exchange.paper;

import net.kyori.adventure.text.Component;
import java.util.List;
import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import org.bukkit.configuration.ConfigurationSection;

/** Configuration-backed user messages; defaults keep the first deployment usable. */
public final class Messages {
    private final ConfigurationSection config;
    public Messages(ConfigurationSection config) { this.config = config; }
    private Component message(String key, String fallback) { return Component.text(config == null ? fallback : config.getString(key, fallback)); }
    public Component processing() { return message("processing", "公司注册正在处理中。"); }
    public Component initializing() { return message("initializing", "BlockStock 正在初始化，请稍后再试。"); }
    public Component noPermission() { return message("no-permission", "你没有权限。"); }
    public Component minimumCapital(CompanyCreationRules rules) { return message("stockadmin-minimum-capital", "当前最低注册资本：%capital%").replaceText(b -> b.matchLiteral("%capital%").replacement(rules.minimumCapitalMajor())); }
    public Component usageStockAdminConfig() { return message("usage-stockadmin-config", "用法：/stockadmin config [min-capital <金额>]"); }
    public Component invalidMinimumCapital() { return message("invalid-minimum-capital", "最低注册资本必须是正数，且小数位必须与货币精度一致。"); }
    public Component minimumCapitalSaveFailed() { return message("minimum-capital-save-failed", "最低注册资本保存失败，当前规则未改变。"); }
    public Component minimumCapitalSaved(String amount) { return message("minimum-capital-saved", "最低注册资本已更新为 %capital%。").replaceText(b -> b.matchLiteral("%capital%").replacement(amount)); }
    public Component minimumCapitalAuditFailed() { return message("minimum-capital-audit-failed", "最低注册资本已保存，但审计写入失败；请检查数据库。 "); }
    public Component playersOnly() { return message("players-only", "此命令只能由玩家执行。"); }
    public Component usageCreate(CompanyCreationRules rules) { return message("usage-create", "用法：/company create <名称> <实缴资本> <DIVIDENDS>").replaceText(b -> b.matchLiteral("DIVIDENDS").replacement(rules.dividendChoices())); }
    public Component usageInfo() { return message("usage-info", "用法：/company info <名称>"); }
    public Component usageRecovery() { return message("usage-recovery", "用法：/company recovery list"); }
    public Component usageAssetBind() { return message("usage-asset-bind", "用法：/company asset bind <adapter> <external-key>"); }
    public Component usageIpoAnnounce() { return message("usage-ipo-announce", "用法：/company ipo announce <目标金额> <发行价>"); }
    public Component usageIpoSubscribe() { return message("usage-ipo-subscribe", "用法：/company ipo subscribe <发行UUID> <整数股>"); }
    public Component usageIpoList() { return message("usage-ipo-list", "用法：/company ipo list"); }
    public Component usageIpoInfo() { return message("usage-ipo-info", "用法：/company ipo info <发行UUID>"); }
    public Component noPublicIpos() { return message("ipo-public-empty", "当前没有可公开查询的 IPO。"); }
    public Component publicIpoNotFound() { return message("ipo-public-not-found", "未找到该公开 IPO。"); }
    public Component ipoPublicQueryFailed() { return message("ipo-public-query-failed", "公开 IPO 查询失败，请稍后再试。"); }
    public Component publicIpo(PublicOfferingView view) { return message("ipo-public-row", "发行=%id% 公司=%company% 状态=%state% 目标=%target% 发行价=%price% 最大=%maximum% 已发行=%issued% 可认购=%available% 公告=%announced% 开放=%opens% 关闭=%closes%").replaceText(b->b.matchLiteral("%id%").replacement(view.offeringId().toString())).replaceText(b->b.matchLiteral("%company%").replacement(view.companyDisplayName())).replaceText(b->b.matchLiteral("%state%").replacement(displayState(view.state()))).replaceText(b->b.matchLiteral("%target%").replacement(String.valueOf(view.target().minorUnits()))).replaceText(b->b.matchLiteral("%price%").replacement(String.valueOf(view.issuePrice().minorUnits()))).replaceText(b->b.matchLiteral("%maximum%").replacement(String.valueOf(view.maximumShares()))).replaceText(b->b.matchLiteral("%issued%").replacement(String.valueOf(view.issuedShares()))).replaceText(b->b.matchLiteral("%available%").replacement(String.valueOf(view.availableShares()))).replaceText(b->b.matchLiteral("%announced%").replacement(String.valueOf(view.announcedAt()))).replaceText(b->b.matchLiteral("%opens%").replacement(String.valueOf(view.opensAt()))).replaceText(b->b.matchLiteral("%closes%").replacement(String.valueOf(view.closesAt()))); }
    public Component ipoProcessing() { return message("ipo-processing", "IPO 认购正在处理中。"); }
    public Component ipoSubscriptionResult(cn.blockeco.exchange.application.SubscriptionResult.Status status) { return switch(status){case SUCCESS->message("ipo-subscribe-success","IPO 认购已完成。");case INSUFFICIENT_FUNDS->message("ipo-subscribe-insufficient","余额不足，认购未执行。");case NOT_OPEN->message("ipo-subscribe-not-open","该 IPO 当前不可认购。");case SOLD_OUT->message("ipo-subscribe-sold-out","该 IPO 已售罄。");case INVALID->message("ipo-subscribe-invalid","认购参数无效。");case RECOVERY_REQUIRED->message("ipo-subscribe-recovery","认购状态需要管理员恢复，请勿重复付款。");case PROVIDER_FAILURE->message("ipo-subscribe-provider-failure","经济服务失败，认购未完成。");}; }
    public Component assetBound() { return message("asset-bound", "资产绑定已完成。"); }
    public Component assetBindFailed() { return message("asset-bind-failed", "资产绑定失败。请确认资产归属和适配器。"); }
    public Component ipoAnnounced() { return message("ipo-announced", "首次公开发行已公告，12 小时后开放认购。"); }
    public Component ipoAnnounceFailed() { return message("ipo-announce-failed", "首次公开发行公告失败。请确认资产绑定和目标金额。"); }
    public List<Component> companyHelp(boolean canCreate, boolean canInfo, boolean canRecovery, CompanyCreationRules rules) {
        java.util.ArrayList<Component> lines = new java.util.ArrayList<>();
        lines.add(message("help-root", "BlockStock 公司命令："));
        if (canCreate) {
            lines.add(message("help-create", "/company create <名称> <实缴资本> <DIVIDENDS>").replaceText(b -> b.matchLiteral("DIVIDENDS").replacement(rules.dividendChoices())));
            String createRules = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(template("help-create-rules", "创建费：%fee%\n最低注册资本：%capital%\n合计余额：%total%\n初始发行：%shares% 股\n必须是玩家\n插件完成初始化\n公司名不能重复", rules));
            for (String line : createRules.split("\\R")) lines.add(Component.text(line));
        }
        if (canInfo) lines.add(message("help-info", "查询：/company info <名称>"));
        lines.add(message("help-ipo-list", "公开 IPO：/company ipo list"));
        lines.add(message("help-ipo-info", "公开 IPO 详情：/company ipo info <发行UUID>"));
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
    public Component capitalizationRecoveryRecord(Object id, Object company, Object player, Object amount, Object state, Object reason) { return message("capitalization-recovery-record", "资本托管恢复：操作=%id% 公司=%company% 玩家=%player% 金额=%amount% 状态=%state% 原因=%reason%").replaceText(b->b.matchLiteral("%id%").replacement(String.valueOf(id))).replaceText(b->b.matchLiteral("%company%").replacement(String.valueOf(company))).replaceText(b->b.matchLiteral("%player%").replacement(String.valueOf(player))).replaceText(b->b.matchLiteral("%amount%").replacement(String.valueOf(amount))).replaceText(b->b.matchLiteral("%state%").replacement(displayState(state))).replaceText(b->b.matchLiteral("%reason%").replacement(String.valueOf(reason))); }
    public Component ipoSubscriptionRecoveryRecord(cn.blockeco.exchange.application.IpoSubscriptionRecoveryRecord record) { return message("ipo-subscription-recovery-record", "IPO 恢复：操作=%id% 发行=%offering% 公司=%company% 玩家=%player% 股数=%shares% 金额=%amount% 状态=%state% 时间=%time% 原因=%reason%").replaceText(b->b.matchLiteral("%id%").replacement(record.subscriptionId().toString())).replaceText(b->b.matchLiteral("%offering%").replacement(record.offeringId().toString())).replaceText(b->b.matchLiteral("%company%").replacement(record.companyId().value().toString())).replaceText(b->b.matchLiteral("%player%").replacement(record.playerId().toString())).replaceText(b->b.matchLiteral("%shares%").replacement(String.valueOf(record.shares()))).replaceText(b->b.matchLiteral("%amount%").replacement(String.valueOf(record.amount().minorUnits()))).replaceText(b->b.matchLiteral("%state%").replacement(displayState(record.state()))).replaceText(b->b.matchLiteral("%time%").replacement(record.updatedAt().toString())).replaceText(b->b.matchLiteral("%reason%").replacement(record.reason())); }
    public Component result(String text) { return Component.text(text); }

    private String displayState(Object state) {
        return switch (String.valueOf(state)) {
            case "PENDING_ASSET_BINDING" -> "待绑定资产";
            case "LISTED" -> "已上市";
            case "DELISTING" -> "退市中";
            case "LIQUIDATING" -> "清算中";
            case "DELISTED" -> "已退市";
            case "PREPARED" -> "已准备";
            case "PLAYER_WITHDRAWN" -> "已扣款待托管";
            case "ESCROW_DEPOSITED" -> "已托管待完成";
            case "WITHDRAWN" -> "已扣款";
            case "COMPLETED" -> "已完成";
            case "REFUND_REQUIRED" -> "待人工退款";
            case "REFUNDED" -> "已退款";
            case "AMBIGUOUS" -> "待人工核对";
            case "REJECTED" -> "已拒绝";
            case "ANNOUNCED" -> "已公告";
            case "OPEN" -> "开放认购";
            case "CLOSED" -> "已关闭";
            default -> String.valueOf(state);
        };
    }
}
