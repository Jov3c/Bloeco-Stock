package cn.blockeco.exchange.paper;

import net.kyori.adventure.text.Component;
import java.util.List;
import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import cn.blockeco.exchange.application.PublicAnnouncement;
import cn.blockeco.exchange.application.PublicMarketRow;
import cn.blockeco.exchange.application.PublicStockInfo;
import cn.blockeco.exchange.application.SecuritiesCashResult;
import cn.blockeco.exchange.application.PortfolioView;
import cn.blockeco.exchange.application.OrderPlacementResult;
import cn.blockeco.exchange.application.OrderView;
import cn.blockeco.exchange.application.TradeView;
import cn.blockeco.exchange.application.OrderBookLevel;
import cn.blockeco.exchange.application.SecondaryMarketQueryService;
import org.bukkit.configuration.ConfigurationSection;

/** Configuration-backed user messages; defaults keep the first deployment usable. */
public final class Messages {
    private final ConfigurationSection config;
    public Messages(ConfigurationSection config) { this.config = config; }
    private Component message(String key, String fallback) { if (config == null) return Component.text(fallback); String nested = config.getString("messages." + key, null); String direct = nested != null ? nested : config.getString(key, fallback); return Component.text(direct == null ? fallback : direct); }
    private int currencyScale() { return config == null ? 2 : config.getInt("currency.scale", 2); }
    private String amount(cn.blockeco.exchange.domain.money.Money value) { return value.toMajor(currencyScale()).toPlainString(); }
    public Component processing() { return message("processing", "公司注册正在处理中。"); }
    public Component initializing() { return message("initializing", "Bloeco-Stock 正在初始化，请稍后再试。"); }
    public Component noPermission() { return message("no-permission", "你没有权限。"); }
    public Component usageStock() { return message("usage-stock", "用法：/stock [gui|help|market|ipo|info|announcements|subscribe|cash|deposit|withdraw|buy|sell|cancel|portfolio|orders|trades|book]"); }
    public Component usageStockCash() { return message("usage-stock-cash", "用法：/stock cash"); }
    public Component usageStockDeposit() { return message("usage-stock-deposit", "用法：/stock deposit <金额>"); }
    public Component usageStockWithdraw() { return message("usage-stock-withdraw", "用法：/stock withdraw <金额>"); }
    public Component usageStockOrder(boolean buy) { return message(buy?"usage-stock-buy":"usage-stock-sell", "用法：/stock " + (buy?"buy":"sell") + " <代码> <正整数股> <限价>"); }
    public Component usageStockCancel() { return message("usage-stock-cancel", "用法：/stock cancel <订单UUID>"); }
    public Component usageStockPortfolio() { return message("usage-stock-portfolio", "用法：/stock portfolio"); }
    public Component usageStockOrders() { return message("usage-stock-orders", "用法：/stock orders [1-50]"); }
    public Component usageStockTrades() { return message("usage-stock-trades", "用法：/stock trades [1-50]"); }
    public Component usageStockBook() { return message("usage-stock-book", "用法：/stock book <代码>"); }
    public Component invalidStockMoney() { return message("stock-invalid-money", "金额必须为正数，且小数位必须与服务器货币精度一致。"); }
    public Component marketUnavailable() { return message("stock-market-unavailable", "证券交易功能暂不可用，请稍后再试。"); }
    public Component cash(PortfolioView view) { return message("stock-cash", "证券账户：可用=%available% 冻结=%reserved%").replaceText(b->b.matchLiteral("%available%").replacement(amount(view.availableCash()))).replaceText(b->b.matchLiteral("%reserved%").replacement(amount(view.reservedCash()))); }
    /** Do not expose Vault/provider exception text to players: it is operational detail, not UI. */
    public Component cashResult(SecuritiesCashResult result) {
        return message("stock-cash-result", "资金操作=%id% 状态=%state%：%outcome%")
                .replaceText(b -> b.matchLiteral("%id%").replacement(result.operationId().toString()))
                .replaceText(b -> b.matchLiteral("%state%").replacement(displayState(result.state())))
                .replaceText(b -> b.matchLiteral("%outcome%").replacement(cashOutcome(result.state())));
    }
    public List<Component> portfolio(PortfolioView view) { java.util.ArrayList<Component> lines=new java.util.ArrayList<>(); lines.add(cash(view)); if(view.holdings().isEmpty()) lines.add(message("stock-portfolio-empty","当前没有持仓。")); else view.holdings().forEach(h->lines.add(message("stock-portfolio-row","%company% [%code%] 可用=%available% 冻结=%reserved% 最新=%price%").replaceText(b->b.matchLiteral("%company%").replacement(h.companyName())).replaceText(b->b.matchLiteral("%code%").replacement(h.stockCode())).replaceText(b->b.matchLiteral("%available%").replacement(String.valueOf(h.availableShares()))).replaceText(b->b.matchLiteral("%reserved%").replacement(String.valueOf(h.reservedShares()))).replaceText(b->b.matchLiteral("%price%").replacement(amount(h.latestPrice()))))); return List.copyOf(lines); }
    public Component orderResult(OrderPlacementResult result) { return message("stock-order-result","订单=%id% %side% %code% 剩余=%remaining% 状态=%state%").replaceText(b->b.matchLiteral("%id%").replacement(result.order().id().toString())).replaceText(b->b.matchLiteral("%side%").replacement(result.order().side()==cn.blockeco.exchange.domain.trading.LimitOrder.Side.BUY?"买入":"卖出")).replaceText(b->b.matchLiteral("%code%").replacement(result.order().stockCode())).replaceText(b->b.matchLiteral("%remaining%").replacement(String.valueOf(result.order().remainingShares()))).replaceText(b->b.matchLiteral("%state%").replacement(displayState(result.order().state()))); }
    public Component order(OrderView value) { return message("stock-order-row","订单=%id% %side% %code% 限价=%price% 剩余=%remaining%/%total% 状态=%state%").replaceText(b->b.matchLiteral("%id%").replacement(value.id().toString())).replaceText(b->b.matchLiteral("%side%").replacement(value.side()==cn.blockeco.exchange.domain.trading.LimitOrder.Side.BUY?"买":"卖")).replaceText(b->b.matchLiteral("%code%").replacement(value.stockCode())).replaceText(b->b.matchLiteral("%price%").replacement(amount(value.limitPrice()))).replaceText(b->b.matchLiteral("%remaining%").replacement(String.valueOf(value.remainingShares()))).replaceText(b->b.matchLiteral("%total%").replacement(String.valueOf(value.originalShares()))).replaceText(b->b.matchLiteral("%state%").replacement(displayState(value.state()))); }
    public Component trade(TradeView value) { return message("stock-trade-row","成交=%id% %side% %code% %shares%股 价格=%price% 金额=%notional% 手续费=%fee%").replaceText(b->b.matchLiteral("%id%").replacement(value.id().toString())).replaceText(b->b.matchLiteral("%side%").replacement(value.side()==cn.blockeco.exchange.domain.trading.LimitOrder.Side.BUY?"买":"卖")).replaceText(b->b.matchLiteral("%code%").replacement(value.stockCode())).replaceText(b->b.matchLiteral("%shares%").replacement(String.valueOf(value.shares()))).replaceText(b->b.matchLiteral("%price%").replacement(amount(value.price()))).replaceText(b->b.matchLiteral("%notional%").replacement(amount(value.notional()))).replaceText(b->b.matchLiteral("%fee%").replacement(amount(value.fee()))); }
    public Component ordersEmpty() { return message("stock-orders-empty", "当前没有委托。 "); } public Component tradesEmpty() { return message("stock-trades-empty", "当前没有成交。 "); }
    public List<Component> book(SecondaryMarketQueryService.OrderBook book) { java.util.ArrayList<Component> lines=new java.util.ArrayList<>(); lines.add(message("stock-book-bids","买五档：")); for(OrderBookLevel v:book.bids()) lines.add(Component.text("买 " + amount(v.price()) + " × " + v.shares())); lines.add(message("stock-book-asks","卖五档：")); for(OrderBookLevel v:book.asks()) lines.add(Component.text("卖 " + amount(v.price()) + " × " + v.shares())); return List.copyOf(lines); }
    public Component usageStockIpo() { return message("usage-stock-ipo", "用法：/stock ipo [1-50]"); }
    public Component usageStockInfo() { return message("usage-stock-info", "用法：/stock info <公司名|代码>"); }
    public Component usageStockAnnouncements() { return message("usage-stock-announcements", "用法：/stock announcements <公司名|代码> [1-50]"); }
    public Component usageStockSubscribe() { return message("usage-stock-subscribe", "用法：/stock subscribe <公司名|代码> <正整数股>"); }
    public Component invalidShares() { return message("stock-invalid-shares", "认购股数必须是正整数股。"); }
    public Component stockQueryFailed() { return message("stock-query-failed", "股票查询失败，请稍后再试。"); }
    public Component marketEmpty() { return message("stock-market-empty", "当前暂无已上市股票。 "); }
    public Component ipoEmpty() { return message("stock-ipo-empty", "当前没有可公开查询的 IPO。 "); }
    public Component stockNotFound() { return message("stock-not-found", "未找到公司或股票代码。 "); }
    public Component openIpoNotFound() { return message("stock-open-ipo-not-found", "该公司当前没有开放认购的 IPO。 "); }
    public Component announcementsEmpty() { return message("stock-announcements-empty", "当前没有公开公告。 "); }
    public Component marketRow(PublicMarketRow row) { return message("stock-market-row", "%company% [%code%] 最新=%latest% 涨跌=%change% 成交量=%volume% 成交额=%turnover% 市值=%capitalization% 已发行=%shares% 状态=%state%").replaceText(b->b.matchLiteral("%company%").replacement(row.companyName())).replaceText(b->b.matchLiteral("%code%").replacement(row.stockCode())).replaceText(b->b.matchLiteral("%latest%").replacement(amount(row.latestPrice()))).replaceText(b->b.matchLiteral("%change%").replacement(signedAmount(row.change()))).replaceText(b->b.matchLiteral("%volume%").replacement(String.valueOf(row.volume()))).replaceText(b->b.matchLiteral("%turnover%").replacement(amount(row.turnover()))).replaceText(b->b.matchLiteral("%capitalization%").replacement(amount(row.marketCapitalization()))).replaceText(b->b.matchLiteral("%shares%").replacement(String.valueOf(row.issuedShares()))).replaceText(b->b.matchLiteral("%state%").replacement(displayState(row.status()))); }
    public Component stockIpoRow(PublicOfferingView view) { return publicIpo(view); }
    public Component stockInfo(PublicStockInfo info) { return message("stock-info-row", "%company% 代码=%code% 状态=%state% 参考价（暂无成交）=%price% 已发行=%shares%").replaceText(b->b.matchLiteral("%company%").replacement(info.companyName())).replaceText(b->b.matchLiteral("%code%").replacement(info.stockCode().orElse("暂无"))).replaceText(b->b.matchLiteral("%state%").replacement(displayState(info.status()))).replaceText(b->b.matchLiteral("%price%").replacement(info.issueReferencePrice().map(this::amount).orElse("暂无"))).replaceText(b->b.matchLiteral("%shares%").replacement(String.valueOf(info.issuedShares()))); }
    public Component announcement(PublicAnnouncement announcement) { return message("stock-announcement-row", "%company% 公告[%time%] %body%").replaceText(b->b.matchLiteral("%company%").replacement(announcement.companyName())).replaceText(b->b.matchLiteral("%time%").replacement(announcement.publishedAt().toString())).replaceText(b->b.matchLiteral("%body%").replacement(announcement.body())); }
    public List<Component> stockHelp(org.bukkit.command.CommandSender sender) { java.util.ArrayList<Component> lines = new java.util.ArrayList<>(List.of(message("stock-help-root", "Bloeco-Stock 股票命令："), message("stock-help-gui", "交易所界面：/stock 或 /stock gui（原版客户端直接打开，无需模组）。"), message("stock-help-public", "公开行情：/stock market；盘口：/stock book <代码>（固定买卖各五档）；IPO：ipo、info、announcements。"))); if (sender instanceof org.bukkit.entity.Player player) { if(player.hasPermission("blockeco.stock.subscribe"))lines.add(message("stock-help-subscribe", "认购：/stock subscribe <公司名|代码> <正整数股>")); if(player.hasPermission("blockeco.stock.cash"))lines.add(message("stock-help-cash", "证券账户：cash；入金：deposit <金额>；出金：withdraw <金额>。资金与个人钱包分开，处理中不可重复操作。")); if(player.hasPermission("blockeco.stock.trade"))lines.add(message("stock-help-trade", "交易：buy/sell <代码> <正整数股> <限价>；撤单：cancel <订单UUID>。仅限价单，买方支付手续费，禁止自成交。")); if(player.hasPermission("blockeco.stock.portfolio"))lines.add(message("stock-help-portfolio", "持仓：portfolio。")); if(player.hasPermission("blockeco.stock.orders"))lines.add(message("stock-help-orders", "委托：orders [1-50]。")); if(player.hasPermission("blockeco.stock.trades"))lines.add(message("stock-help-trades", "成交：trades [1-50]。")); } return List.copyOf(lines); }
    public Component minimumCapital(CompanyCreationRules rules) { return message("stockadmin-minimum-capital", "当前最低注册资本：%capital%").replaceText(b -> b.matchLiteral("%capital%").replacement(rules.minimumCapitalMajor())); }
    public Component usageStockAdminConfig() { return message("usage-stockadmin-config", "用法：/stockadmin config [min-capital <金额>]"); }
    public Component usageStockAdminRecovery() { return message("usage-stockadmin-recovery", "用法：/stockadmin recovery <cash|reconcile>"); }
    public Component recoveryUnavailable() { return message("stockadmin-recovery-unavailable", "恢复诊断暂不可用，请稍后再试。"); }
    public List<Component> secondaryRecovery(cn.blockeco.exchange.application.SecondaryMarketRecoveryService.RecoverySnapshot snapshot) {
        java.util.ArrayList<Component> lines = new java.util.ArrayList<>();
        var reconciliation = snapshot.reconciliation();
        lines.add(message("stockadmin-recovery-summary", "证券恢复：物理托管=%physical% 最终负债=%liabilities% 已确认差额=%difference% 不确定外部金额=%uncertain% 变更=%gate%")
                .replaceText(b -> b.matchLiteral("%physical%").replacement(amount(reconciliation.physicalBalance())))
                .replaceText(b -> b.matchLiteral("%liabilities%").replacement(amount(reconciliation.finalLiabilities())))
                .replaceText(b -> b.matchLiteral("%difference%").replacement(amount(reconciliation.confirmedDifference())))
                .replaceText(b -> b.matchLiteral("%uncertain%").replacement(amount(reconciliation.uncertainExternalAmount())))
                .replaceText(b -> b.matchLiteral("%gate%").replacement(snapshot.mutationsBlocked() ? "已关闭" : "可用")));
        snapshot.operations().forEach(operation -> lines.add(message("stockadmin-recovery-cash-row", "现金操作=%id% 方向=%direction% 状态=%state% 已确认=%stage% 金额=%amount% 原因=%reason%")
                .replaceText(b -> b.matchLiteral("%id%").replacement(operation.id().toString()))
                .replaceText(b -> b.matchLiteral("%direction%").replacement(operation.direction() == cn.blockeco.exchange.domain.finance.SecuritiesCashDirection.DEPOSIT ? "入金" : "出金"))
                .replaceText(b -> b.matchLiteral("%state%").replacement(displayState(operation.state())))
                .replaceText(b -> b.matchLiteral("%stage%").replacement(operation.lastConfirmedExternalStage() == null ? "无" : displayState(operation.lastConfirmedExternalStage())))
                .replaceText(b -> b.matchLiteral("%amount%").replacement(amount(operation.amount())))
                .replaceText(b -> b.matchLiteral("%reason%").replacement(operation.detail()))));
        snapshot.legacyIssues().forEach(issue -> lines.add(message("stockadmin-recovery-legacy-row", "遗留=%source% 操作=%id% 状态=%state% 已确认=%stage% 金额=%amount% 原因=%reason%")
                .replaceText(b -> b.matchLiteral("%source%").replacement(issue.source()))
                .replaceText(b -> b.matchLiteral("%id%").replacement(issue.operationId().toString()))
                .replaceText(b -> b.matchLiteral("%state%").replacement(issue.state()))
                .replaceText(b -> b.matchLiteral("%stage%").replacement(issue.lastConfirmedStage().isBlank() ? "无" : issue.lastConfirmedStage()))
                .replaceText(b -> b.matchLiteral("%amount%").replacement(amount(issue.amount())))
                .replaceText(b -> b.matchLiteral("%reason%").replacement(issue.reason()))));
        return List.copyOf(lines);
    }
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
    public Component publicIpo(PublicOfferingView view) { return message("ipo-public-row", "发行=%id% 公司=%company% 状态=%state% 目标=%target% 发行价=%price% 最大=%maximum% 已发行=%issued% 可认购=%available% 公告=%announced% 开放=%opens% 关闭=%closes%").replaceText(b->b.matchLiteral("%id%").replacement(view.offeringId().toString())).replaceText(b->b.matchLiteral("%company%").replacement(view.companyDisplayName())).replaceText(b->b.matchLiteral("%state%").replacement(displayIpoState(view.state()))).replaceText(b->b.matchLiteral("%target%").replacement(amount(view.target()))).replaceText(b->b.matchLiteral("%price%").replacement(amount(view.issuePrice()))).replaceText(b->b.matchLiteral("%maximum%").replacement(String.valueOf(view.maximumShares()))).replaceText(b->b.matchLiteral("%issued%").replacement(String.valueOf(view.issuedShares()))).replaceText(b->b.matchLiteral("%available%").replacement(String.valueOf(view.availableShares()))).replaceText(b->b.matchLiteral("%announced%").replacement(String.valueOf(view.announcedAt()))).replaceText(b->b.matchLiteral("%opens%").replacement(String.valueOf(view.opensAt()))).replaceText(b->b.matchLiteral("%closes%").replacement(String.valueOf(view.closesAt()))); }
    public Component ipoProcessing() { return message("ipo-processing", "IPO 认购正在处理中。"); }
    public Component ipoSubscriptionResult(cn.blockeco.exchange.application.SubscriptionResult.Status status) { return switch(status){case SUCCESS->message("ipo-subscribe-success","IPO 认购已完成。");case INSUFFICIENT_FUNDS->message("ipo-subscribe-insufficient","余额不足，认购未执行。");case NOT_OPEN->message("ipo-subscribe-not-open","该 IPO 当前不可认购。");case SOLD_OUT->message("ipo-subscribe-sold-out","该 IPO 已售罄。");case INVALID->message("ipo-subscribe-invalid","认购参数无效。");case RECOVERY_REQUIRED->message("ipo-subscribe-recovery","认购状态需要管理员恢复，请勿重复付款。");case PROVIDER_FAILURE->message("ipo-subscribe-provider-failure","经济服务失败，认购未完成。");}; }
    public Component assetBound() { return message("asset-bound", "资产绑定已完成。"); }
    public Component assetBindFailed() { return message("asset-bind-failed", "资产绑定失败。请确认资产归属和适配器。"); }
    public Component ipoAnnounced() { return message("ipo-announced", "首次公开发行已公告，12 小时后开放认购。"); }
    public Component ipoAnnounceFailed() { return message("ipo-announce-failed", "首次公开发行公告失败。请确认资产绑定和目标金额。"); }
    public List<Component> companyHelp(boolean canCreate, boolean canInfo, boolean canRecovery, CompanyCreationRules rules) {
        java.util.ArrayList<Component> lines = new java.util.ArrayList<>();
        lines.add(message("help-root", "Bloeco-Stock 公司命令："));
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

    private String signedAmount(cn.blockeco.exchange.domain.money.Money value) {
        String rendered = amount(value);
        return value.minorUnits() > 0 ? "+" + rendered : rendered;
    }

    private String cashOutcome(cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState state) {
        return switch (state) {
            case COMPLETED -> "已完成，请以证券账户余额为准。";
            case PREPARED, PLAYER_WITHDRAWN, ESCROW_DEPOSITED, ESCROW_WITHDRAWN, PLAYER_DEPOSITED -> "正在处理，请勿重复提交。";
            case FAILED -> "未完成，资金未发生可确认变动。";
            case AMBIGUOUS -> "状态待人工核对，请勿重复提交。";
        };
    }

    private String displayIpoState(Object state) {
        return "OPEN".equals(String.valueOf(state)) ? "开放认购" : displayState(state);
    }

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
            case "ESCROW_WITHDRAWN" -> "托管已出金待到账";
            case "PLAYER_DEPOSITED" -> "玩家已到账待完成";
            case "FAILED" -> "已失败";
            case "CANCELLED" -> "已撤单";
            case "PARTIALLY_FILLED" -> "部分成交";
            case "FILLED" -> "已成交";
            case "SELF_TRADE_PREVENTED" -> "已阻止自成交";
            case "WITHDRAWN" -> "已扣款";
            case "COMPLETED" -> "已完成";
            case "REFUND_REQUIRED" -> "待人工退款";
            case "REFUNDED" -> "已退款";
            case "AMBIGUOUS" -> "待人工核对";
            case "REJECTED" -> "已拒绝";
            case "ANNOUNCED" -> "已公告";
            case "OPEN" -> "待成交";
            case "CLOSED" -> "已关闭";
            default -> String.valueOf(state);
        };
    }
}
