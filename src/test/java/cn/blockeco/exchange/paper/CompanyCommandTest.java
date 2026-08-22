package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.application.RegistrationRequest;
import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.CompanyRegistrationService;
import cn.blockeco.exchange.application.CapitalizationRecoveryRecord;
import cn.blockeco.exchange.application.AssetBindingService;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.infrastructure.CompanyAssetAdapterRegistryImpl;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAssetBindingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.finance.TreasuryOperationState;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.domain.money.Money;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

class CompanyCommandTest {
    private static final MainThreadExecutor DIRECT_MAIN = new MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return CompletableFuture.completedFuture(work.get()); } };
    private final UUID founder = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final CompanyCreationRules rules = new CompanyCreationRules(Money.fromMajor(new BigDecimal("1000.00"), 2), Money.fromMajor(new BigDecimal("10000.00"), 2), 2, 1000, List.of(30, 50, 70));

    @Test
    void rejects_initial_shares_below_the_company_domain_minimum_during_configuration() {
        assertThatThrownBy(() -> new CompanyCreationRules(Money.ofMinor(1), Money.ofMinor(1), 0, 999, List.of(50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialShares");
    }

    @Test
    void root_help_includes_live_create_rules_for_authorized_player() {
        Player player = permittedPlayer("blockeco.company.create", "blockeco.company.info");
        CompanyCommand command = new CompanyCommand(mock(CompanyRegistrationService.class), mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, rules); command.setAccepting(true);

        assertThat(command.onCommand(player, mock(Command.class), "company", new String[0])).isTrue();

        assertThat(allPlainMessages(player)).contains("/company create <名称> <实缴资本> <30|50|70>", "创建费：1000.00", "最低注册资本：10000.00", "合计余额：11000.00", "初始发行：1000 股", "必须是玩家", "插件完成初始化", "公司名不能重复");
    }

    @Test
    void missing_info_name_sends_explicit_usage() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("blockeco.company.info")).thenReturn(true);
        CompanyCommand command = new CompanyCommand(mock(CompanyRegistrationService.class), mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, rules); command.setAccepting(true);

        assertThat(command.onCommand(sender, mock(Command.class), "company", new String[] {"info"})).isTrue();

        ArgumentCaptor<net.kyori.adventure.text.Component> captured = ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(sender).sendMessage(captured.capture());
        assertThat(PlainTextComponentSerializer.plainText().serialize(captured.getValue())).contains("/company info <名称>");
    }

    @Test
    void create_parser_uses_configured_dividend_choices() {
        CompanyCreationRules customRules = new CompanyCreationRules(Money.fromMajor(new BigDecimal("1"), 0), Money.fromMajor(new BigDecimal("2"), 0), 0, 1_000, List.of(25, 75));
        Player player = permittedPlayer("blockeco.company.create");
        CompanyCommand command = new CompanyCommand(mock(CompanyRegistrationService.class), mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, customRules);

        assertThat(CompanyCommand.parseCreate(founder, new String[] {"create", "North", "2", "25"}, customRules)).contains(new RegistrationRequest(founder, "North", Money.ofMinor(2), 25));
        assertThat(CompanyCommand.parseCreate(founder, new String[] {"create", "North", "2", "30"}, customRules)).isEmpty();
        command.onCommand(player, mock(Command.class), "company", new String[0]);
        assertThat(allPlainMessages(player)).contains("/company create <名称> <实缴资本> <25|75>");
    }

    @Test
    void parses_precise_paid_in_capital_before_dividend() {
        assertThat(CompanyCommand.parseCreate(founder, new String[] {"create", "红石", "100000.00", "50"}, rules))
                .contains(new RegistrationRequest(founder, "红石", Money.fromMajor(new BigDecimal("100000.00"), 2), 50));
    }

    @Test
    void rejects_paid_in_capital_below_configured_minimum() {
        assertThat(CompanyCommand.parseCreate(founder, new String[] {"create", "红石", "9999.99", "50"}, rules)).isEmpty();
    }

    @Test
    void changed_minimum_is_used_immediately_by_company_parse_and_help() {
        MutableCompanyCreationRules live = new MutableCompanyCreationRules(rules);
        live.replaceMinimumCapital(Money.fromMajor(new BigDecimal("25000.50"), 2));
        Player player = permittedPlayer("blockeco.company.create");
        CompanyCommand command = new CompanyCommand(mock(CompanyRegistrationService.class), mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, live);

        assertThat(CompanyCommand.parseCreate(founder, new String[] {"create", "North", "10000.00", "50"}, live.current())).isEmpty();
        command.onCommand(player, mock(Command.class), "company", new String[0]);
        assertThat(allPlainMessages(player)).contains("最低注册资本：25000.50");
    }

    @Test
    void create_parser_joins_multiword_name_before_final_dividend() {
        var result = CompanyCommand.parseCreate(founder, new String[] {"create", "North", "Star", "10000.00", "50"}, rules);

        assertThat(result).contains(new RegistrationRequest(founder, "North Star", Money.ofMinor(1_000_000), 50));
    }

    @Test
    void create_parser_strips_optional_quotes_from_multiword_name() {
        var result = CompanyCommand.parseCreate(founder, new String[] {"create", "\"North", "Star\"", "10000.00", "70"}, rules);

        assertThat(result).contains(new RegistrationRequest(founder, "North Star", Money.ofMinor(1_000_000), 70));
    }

    @Test
    void create_parser_rejects_dividend_outside_supported_choices() {
        var result = CompanyCommand.parseCreate(founder, new String[] {"create", "North", "10000.00", "40"}, rules);

        assertThat(result).isEmpty();
    }

    @Test
    void create_rejects_console_before_dispatching_registration() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        CommandSender console = mock(CommandSender.class);
        CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, rules); command.setAccepting(true);

        command.onCommand(console, mock(Command.class), "company", new String[] {"create", "North", "10000.00", "30"});

        ArgumentCaptor<net.kyori.adventure.text.Component> message = ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(console).sendMessage(message.capture());
        assertThat(PlainTextComponentSerializer.plainText().serialize(message.getValue())).contains("只能由玩家执行");
        verifyNoInteractions(registration);
    }

    @Test
    void create_rejects_player_without_permission() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        Player player = mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(false);
        CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, rules);

        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "10000.00", "30"});

        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
        verifyNoInteractions(registration);
    }

    @Test
    void create_during_initialization_sends_configured_message_without_service_call() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        Player player = mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true);
        CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, rules);

        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "30"});

        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
        verifyNoInteractions(registration);
    }

    @Test
    void every_nonempty_subcommand_during_initialization_only_sends_initializing_without_services() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class); CompanyQueryService queries = mock(CompanyQueryService.class);
        AssetBindingService assets = mock(AssetBindingService.class); cn.blockeco.exchange.application.PrimaryOfferingService offerings = mock(cn.blockeco.exchange.application.PrimaryOfferingService.class);
        CommandSender sender = mock(CommandSender.class);
        CompanyCommand command = new CompanyCommand(registration, queries, new Messages(null), DIRECT_MAIN, rules, assets, offerings);

        for (String[] args : List.of(new String[] {"create", "North", "10000.00", "50"}, new String[] {"info", "North"}, new String[] {"recovery", "list"}, new String[] {"asset", "bind", "adapter", "key"}, new String[] {"ipo", "announce", "10000.00", "10.00"}, new String[] {"unknown"})) {
            assertThat(command.onCommand(sender, mock(Command.class), "company", args)).isTrue();
        }

        assertThat(allPlainMessages(sender)).containsExactly(
                "BlockStock 正在初始化，请稍后再试。", "BlockStock 正在初始化，请稍后再试。",
                "BlockStock 正在初始化，请稍后再试。", "BlockStock 正在初始化，请稍后再试。",
                "BlockStock 正在初始化，请稍后再试。", "BlockStock 正在初始化，请稍后再试。");
        verifyNoInteractions(registration, queries, assets, offerings);
    }

    @Test
    void root_help_during_initialization_is_readable_without_service_access() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class); CompanyQueryService queries = mock(CompanyQueryService.class);
        AssetBindingService assets = mock(AssetBindingService.class); cn.blockeco.exchange.application.PrimaryOfferingService offerings = mock(cn.blockeco.exchange.application.PrimaryOfferingService.class);
        CommandSender sender = mock(CommandSender.class);
        CompanyCommand command = new CompanyCommand(registration, queries, new Messages(null), DIRECT_MAIN, rules, assets, offerings);

        command.onCommand(sender, mock(Command.class), "company", new String[0]);

        assertThat(allPlainMessages(sender)).contains("BlockStock 公司命令：");
        verifyNoInteractions(registration, queries, assets, offerings);
    }

    @Test
    void info_during_initialization_does_not_query_and_only_reports_initializing() {
        CompanyQueryService queries = mock(CompanyQueryService.class);
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission("blockeco.company.info")).thenReturn(true);
        CompanyCommand command = new CompanyCommand(mock(CompanyRegistrationService.class), queries, new Messages(null), DIRECT_MAIN, rules);

        command.onCommand(sender, mock(Command.class), "company", new String[] {"info", "North"});

        assertThat(allPlainMessages(sender)).containsExactly("BlockStock 正在初始化，请稍后再试。");
        verifyNoInteractions(queries);
    }

    @Test
    void recovery_list_during_initialization_does_not_query_and_only_reports_initializing() {
        CompanyQueryService queries = mock(CompanyQueryService.class);
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission("blockeco.admin.recovery")).thenReturn(true);
        CompanyCommand command = new CompanyCommand(mock(CompanyRegistrationService.class), queries, new Messages(null), DIRECT_MAIN, rules);

        command.onCommand(sender, mock(Command.class), "company", new String[] {"recovery", "list"});

        assertThat(allPlainMessages(sender)).containsExactly("BlockStock 正在初始化，请稍后再试。");
        verifyNoInteractions(queries);
    }

    @Test
    void create_rejects_second_submission_while_first_is_in_flight() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        Player player = mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true); when(player.getUniqueId()).thenReturn(founder);
        when(registration.register(any())).thenReturn(new CompletableFuture<>());
        CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), DIRECT_MAIN, rules); command.setAccepting(true);

        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "10000.00", "30"});
        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "10000.00", "30"});

        verify(registration, times(1)).register(new RegistrationRequest(founder, "North", Money.ofMinor(1_000_000), 30));
        verify(player, times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void create_completion_sends_result_only_when_main_thread_queue_runs() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class); CompletableFuture<cn.blockeco.exchange.application.RegistrationResult> result = new CompletableFuture<>(); when(registration.register(any())).thenReturn(result);
        Player player = mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true); when(player.getUniqueId()).thenReturn(founder);
        QueuedMain main = new QueuedMain(); CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), main, rules); command.setAccepting(true);
        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "10000.00", "30"}); result.complete(cn.blockeco.exchange.application.RegistrationResult.of(cn.blockeco.exchange.application.RegistrationResult.Status.SUCCESS, ""));
        verify(player, times(1)).sendMessage(any(net.kyori.adventure.text.Component.class)); main.runAll();
        verify(player, times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void recovery_list_includes_ambiguous_capitalization_without_invoking_any_write_service() {
        CompanyQueryService queries = mock(CompanyQueryService.class); CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        UUID operationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID companyId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID playerId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        TreasuryOperation operation = new TreasuryOperation(operationId, new CompanyId(companyId), playerId, Money.ofMinor(77), operationId.toString(), TreasuryOperationState.AMBIGUOUS, java.time.Instant.parse("2026-08-14T12:00:00Z"), java.time.Instant.parse("2026-08-14T12:01:00Z"));
        when(queries.recoveryList()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(queries.capitalizationRecoveryList()).thenReturn(CompletableFuture.completedFuture(List.of(new CapitalizationRecoveryRecord(operation, "Vault 超时"))));
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission("blockeco.admin.recovery")).thenReturn(true);
        CompanyCommand command = new CompanyCommand(registration, queries, new Messages(null), DIRECT_MAIN, rules); command.setAccepting(true);

        assertThat(command.onCommand(sender, mock(Command.class), "company", new String[] {"recovery", "list"})).isTrue();

        assertThat(allPlainMessages(sender)).anySatisfy(message -> assertThat(message).contains(operationId.toString(), companyId.toString(), playerId.toString(), "77", "待人工核对", "Vault 超时"));
        verifyNoInteractions(registration);
    }

    @Test
    void asset_bind_command_persists_an_active_binding_from_a_registered_adapter_and_reports_success_on_main_thread() throws Exception {
        Path file = Files.createTempFile("company-command-asset-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            CompanyId company = cn.blockeco.exchange.application.Fixtures.company(database, 100_000);
            UUID companyFounder = cn.blockeco.exchange.application.Fixtures.founder(database, company);
            CompanyAssetAdapterRegistryImpl registry = new CompanyAssetAdapterRegistryImpl();
            CompanyAssetAdapter adapter = ownedAdapter("fixture-land", companyFounder);
            AssetBindingService assetBindings = new AssetBindingService(new SqlAssetBindingRepository(database.dataSource()), database, registry::snapshot, () -> Instant.parse("2026-08-14T12:00:00Z"));
            QueuedMain main = new QueuedMain();
            CompanyCommand command = commandWithAssetBindings(database, assetBindings, main);
            Player player = permittedPlayer("blockeco.company.asset.bind");
            when(player.getUniqueId()).thenReturn(companyFounder);
            command.setAccepting(true);
            // An integration can become available after Blockeco has finished enabling.
            registry.register(adapter);

            assertThat(command.onCommand(player, mock(Command.class), "company", new String[] {"asset", "bind", "fixture-land", "plot-42"})).isTrue();
            main.awaitQueuedWork();
            verify(player, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
            main.runAll();

            assertThat(allPlainMessages(player)).containsExactly("资产绑定已完成。");
            assertThat(new SqlAssetBindingRepository(database.dataSource()).findActive(company, "fixture-land", "plot-42"))
                    .hasValueSatisfying(binding -> {
                        assertThat(binding.state()).isEqualTo(AssetBindingState.ACTIVE);
                        assertThat(binding.verifiedOwner()).isEqualTo(companyFounder);
                    });
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void asset_bind_command_reports_a_chinese_failure_when_the_adapter_is_not_registered() throws Exception {
        Path file = Files.createTempFile("company-command-asset-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            CompanyId company = cn.blockeco.exchange.application.Fixtures.company(database, 100_000);
            UUID companyFounder = cn.blockeco.exchange.application.Fixtures.founder(database, company);
            CompanyAssetAdapterRegistryImpl registry = new CompanyAssetAdapterRegistryImpl();
            AssetBindingService assetBindings = new AssetBindingService(new SqlAssetBindingRepository(database.dataSource()), database, registry.snapshot(), () -> Instant.parse("2026-08-14T12:00:00Z"));
            QueuedMain main = new QueuedMain();
            CompanyCommand command = commandWithAssetBindings(database, assetBindings, main);
            Player player = permittedPlayer("blockeco.company.asset.bind");
            when(player.getUniqueId()).thenReturn(companyFounder);
            command.setAccepting(true);

            command.onCommand(player, mock(Command.class), "company", new String[] {"asset", "bind", "missing", "plot-42"});
            main.awaitQueuedWork();
            main.runAll();

            assertThat(allPlainMessages(player)).containsExactly("资产绑定失败。请确认资产归属和适配器。");
            assertThat(new SqlAssetBindingRepository(database.dataSource()).findActive(company, "missing", "plot-42")).isEmpty();
        } finally { Files.deleteIfExists(file); }
    }

    private CompanyCommand commandWithAssetBindings(Database database, AssetBindingService assetBindings, QueuedMain main) {
        CompanyQueryService queries = new CompanyQueryService(new SqlCompanyRepository(database.dataSource()), null, Runnable::run);
        return new CompanyCommand(mock(CompanyRegistrationService.class), queries, new Messages(null), main, rules, assetBindings, null);
    }

    private static CompanyAssetAdapter ownedAdapter(String id, UUID owner) {
        return new CompanyAssetAdapter() {
            @Override public String id() { return id; }
            @Override public Verification verify(UUID requester, String externalKey) { return new Verification(owner.equals(requester), owner, "fixture ownership"); }
        };
    }

    private static final class QueuedMain implements MainThreadExecutor {
        private final java.util.List<java.util.function.Supplier<?>> queue = new java.util.ArrayList<>();
        private final java.util.concurrent.CountDownLatch queued = new java.util.concurrent.CountDownLatch(1);
        @Override public synchronized <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { queue.add(work); queued.countDown(); return new CompletableFuture<>(); }
        void awaitQueuedWork() throws InterruptedException { assertThat(queued.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
        synchronized void runAll() { while (!queue.isEmpty()) queue.remove(0).get(); }
    }

    private static Player permittedPlayer(String... permissions) {
        Player player = mock(Player.class);
        for (String permission : permissions) when(player.hasPermission(permission)).thenReturn(true);
        return player;
    }

    private static List<String> allPlainMessages(CommandSender sender) {
        ArgumentCaptor<net.kyori.adventure.text.Component> captured = ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(sender, atLeastOnce()).sendMessage(captured.capture());
        return captured.getAllValues().stream().map(component -> PlainTextComponentSerializer.plainText().serialize(component)).toList();
    }
}
