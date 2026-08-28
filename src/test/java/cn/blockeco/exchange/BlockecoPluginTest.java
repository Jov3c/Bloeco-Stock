package cn.blockeco.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import cn.blockeco.exchange.paper.CommandAcceptanceGate;
import cn.blockeco.exchange.paper.PublicStockSymbolCache;
import cn.blockeco.exchange.application.PublicStockQueryService;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.application.CompanyFinanceSchedulers;

class BlockecoPluginTest {

    @Test
    void pluginRegistersOperationsAndMonthlyReportSchedulersAfterStartup() {
        java.util.List<java.time.Duration> periods = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger operations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reports = new java.util.concurrent.atomic.AtomicInteger();
        var schedulers = new CompanyFinanceSchedulers((task, initial, period) -> {
            periods.add(period); task.run(); return () -> { };
        }, operations::incrementAndGet, reports::incrementAndGet);

        schedulers.start();

        assertThat(periods).containsExactly(java.time.Duration.ofMinutes(5), java.time.Duration.ofHours(1));
        assertThat(operations).hasValue(1);
        assertThat(reports).hasValue(1);
        assertThat(schedulers.started()).isTrue();
    }

    @Test
    void second_bluechip_bootstrap_wiring_exception_is_reported_while_runtime_is_live() throws Exception {
        PluginRuntime runtime = new PluginRuntime();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        continueBluechipBootstrap(runtime, () -> { throw new IllegalStateException("late wiring"); }, failure::set);

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessage("late wiring");
    }

    @Test
    void second_bluechip_bootstrap_wiring_false_is_reported_while_runtime_is_live() throws Exception {
        PluginRuntime runtime = new PluginRuntime();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        continueBluechipBootstrap(runtime, () -> false, failure::set);

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessage("bootstrap wiring failed");
    }

    @Test
    void second_bluechip_bootstrap_does_not_report_failure_after_runtime_stops() throws Exception {
        PluginRuntime runtime = new PluginRuntime();
        runtime.stop();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        continueBluechipBootstrap(runtime, () -> { throw new IllegalStateException("late wiring"); }, failure::set);

        assertThat(failure.get()).isNull();
    }

    private static void continueBluechipBootstrap(PluginRuntime runtime, Supplier<Boolean> finish, Consumer<Throwable> failed) throws Exception {
        var method = BlockecoPlugin.class.getDeclaredMethod("continueBluechipBootstrap", PluginRuntime.class, Supplier.class, Consumer.class);
        method.setAccessible(true);
        method.invoke(null, runtime, finish, failed);
    }

    @Test
    void initial_stock_cache_refresh_opens_both_gates_only_after_success() {
        PublicStockSymbolCache cache = mock(PublicStockSymbolCache.class); PublicStockQueryService queries = mock(PublicStockQueryService.class);
        java.util.concurrent.CompletableFuture<Void> refresh = new java.util.concurrent.CompletableFuture<>(); when(cache.refresh(queries)).thenReturn(refresh);
        CommandAcceptanceGate company = mock(CommandAcceptanceGate.class); CommandAcceptanceGate stock = mock(CommandAcceptanceGate.class); PluginRuntime runtime = new PluginRuntime();
        MainThreadExecutor main = new MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } };

        BlockecoPlugin.attachStockAfterInitialRefresh(cache, queries, main, runtime, java.util.List.of(company, stock), () -> {}, ignored -> { });
        verify(company, org.mockito.Mockito.never()).setAccepting(true);
        refresh.complete(null);
        verify(company).setAccepting(true); verify(stock).setAccepting(true);
    }

    @Test void failed_initial_stock_refresh_reports_failure_and_never_opens_gates() {
        PublicStockSymbolCache cache = mock(PublicStockSymbolCache.class); PublicStockQueryService queries = mock(PublicStockQueryService.class); when(cache.refresh(queries)).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("db")));
        CommandAcceptanceGate company = mock(CommandAcceptanceGate.class); CommandAcceptanceGate stock = mock(CommandAcceptanceGate.class); PluginRuntime runtime = new PluginRuntime(); java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        MainThreadExecutor main = new MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } };
        assertThatThrownBy(() -> BlockecoPlugin.attachStockAfterInitialRefresh(cache, queries, main, runtime, java.util.List.of(company, stock), () -> {}, failure::set).toCompletableFuture().join()).isInstanceOf(java.util.concurrent.CompletionException.class);
        assertThat(failure.get()).isNotNull(); verify(company, org.mockito.Mockito.never()).setAccepting(true); verify(stock, org.mockito.Mockito.never()).setAccepting(true);
    }

    @Test
    void plugin_does_not_accept_company_commands_when_escrow_preflight_fails() {
        Economy provider = mock(Economy.class);
        OfflinePlayer reservedEscrowIdentity = mock(OfflinePlayer.class);
        when(provider.hasAccount(reservedEscrowIdentity)).thenReturn(false);
        when(provider.createPlayerAccount(reservedEscrowIdentity)).thenReturn(false);

        assertThat(VaultProviderResolver.escrowPreflightFailure(provider, reservedEscrowIdentity,
                UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .contains("保留托管账户");
    }

    @Test
    void escrow_preflight_rejects_a_known_player_before_it_touches_vault() {
        Economy provider = mock(Economy.class);
        OfflinePlayer knownPlayer = mock(OfflinePlayer.class);
        when(knownPlayer.hasPlayedBefore()).thenReturn(true);

        String failure = VaultProviderResolver.escrowPreflightFailure(provider, knownPlayer,
                UUID.fromString("00000000-0000-0000-0000-000000000002"));

        assertThat(failure).contains("真实玩家");
        verifyNoInteractions(provider);
    }

    @Test
    void startup_gate_refusal_keeps_runtime_initializing_and_does_not_start_recovery() {
        java.util.concurrent.atomic.AtomicReference<String> callbackFailure = new java.util.concurrent.atomic.AtomicReference<>();
        StartupRecoveryGate gate = new StartupRecoveryGate(callbackFailure::set);
        java.util.concurrent.atomic.AtomicInteger recoveryCalls = new java.util.concurrent.atomic.AtomicInteger();

        gate.start("保留托管账户是已知真实玩家", () -> {
            recoveryCalls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(1);
        });

        assertThat(gate.ready()).isFalse();
        assertThat(gate.accepting()).isFalse();
        assertThat(recoveryCalls).hasValue(0);
        assertThat(gate.failure()).contains("托管账户启动前检查失败");
        assertThat(callbackFailure).hasValueSatisfying(value -> assertThat(value).contains("托管账户启动前检查失败"));
    }

    @Test
    void startup_gate_only_becomes_ready_after_recovery_completes() {
        StartupRecoveryGate gate = new StartupRecoveryGate();
        java.util.concurrent.CompletableFuture<Integer> recovery = new java.util.concurrent.CompletableFuture<>();

        gate.start(null, () -> recovery);
        assertThat(gate.ready()).isFalse();
        assertThat(gate.accepting()).isFalse();

        recovery.complete(1);

        assertThat(gate.ready()).isTrue();
        assertThat(gate.accepting()).isTrue();
        assertThat(gate.failure()).isNull();
    }

    @Test
    void startup_gate_runs_ipo_recovery_after_capitalization_and_never_accepts_when_ipo_recovery_fails() {
        StartupRecoveryGate gate = new StartupRecoveryGate();
        java.util.concurrent.atomic.AtomicBoolean capitalizationFinished = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CompletableFuture<Integer> ipo = new java.util.concurrent.CompletableFuture<>();

        gate.start(null, () -> java.util.concurrent.CompletableFuture.completedFuture(2), capitalizations -> {
            capitalizationFinished.set(true); return ipo;
        }, (capitalizations, summary) -> { });

        assertThat(capitalizationFinished).isTrue(); assertThat(gate.accepting()).isFalse();
        ipo.completeExceptionally(new IllegalStateException("IPO SQL failure"));
        assertThat(gate.accepting()).isFalse(); assertThat(gate.failure()).contains("IPO 认购恢复失败");
    }

    @Test
    void full_startup_chain_waits_for_symbol_refresh_before_publishing_readiness() {
        StartupRecoveryGate gate = new StartupRecoveryGate();
        java.util.List<String> order = new java.util.ArrayList<>();
        java.util.concurrent.CompletableFuture<Void> symbols = new java.util.concurrent.CompletableFuture<>();

        gate.startFull(null,
                () -> { order.add("capitalization"); return java.util.concurrent.CompletableFuture.completedFuture("C"); },
                capitalizations -> { order.add("ipo:" + capitalizations); return java.util.concurrent.CompletableFuture.completedFuture("I"); },
                ipo -> { order.add("cash:" + ipo); return java.util.concurrent.CompletableFuture.completedFuture("S"); },
                cash -> { order.add("reconcile:" + cash); return java.util.concurrent.CompletableFuture.completedFuture("R"); },
                reconciliation -> { order.add("symbols:" + reconciliation); return symbols; },
                result -> order.add("ready:" + result));

        assertThat(order).containsExactly("capitalization", "ipo:C", "cash:I", "reconcile:S", "symbols:R");
        assertThat(gate.accepting()).isFalse();
        symbols.complete(null);
        assertThat(order).containsExactly("capitalization", "ipo:C", "cash:I", "reconcile:S", "symbols:R", "ready:R");
        assertThat(gate.accepting()).isTrue();
    }

    @Test
    void full_startup_chain_fails_closed_when_symbol_refresh_fails() {
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        StartupRecoveryGate gate = new StartupRecoveryGate(failure::set);

        gate.startFull(null,
                () -> java.util.concurrent.CompletableFuture.completedFuture(1),
                ignored -> java.util.concurrent.CompletableFuture.completedFuture(2),
                ignored -> java.util.concurrent.CompletableFuture.completedFuture(3),
                ignored -> java.util.concurrent.CompletableFuture.completedFuture(4),
                ignored -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("symbol cache")),
                ignored -> { throw new AssertionError("must not publish readiness"); });

        assertThat(gate.accepting()).isFalse();
        assertThat(gate.failure()).contains("symbol cache");
        assertThat(failure.get()).contains("symbol cache");
    }

    @Test
    void final_vault_provider_startup_diagnostic_is_chinese() {
        assertThat(BlockecoPlugin.startupFailureMessage(new IllegalStateException("Vault 经济提供方不可用")))
                .isEqualTo("BlockStock 启动失败：Vault 经济提供方不可用");
    }

    @Test
    void configuration_and_missing_command_startup_diagnostics_have_chinese_primary_text() {
        assertThat(BlockecoPlugin.configurationFailureMessage(new IllegalArgumentException("currency.scale must be between 0 and 8")))
                .isEqualTo("BlockStock 配置无效（附加信息：currency.scale must be between 0 and 8）");
        assertThat(BlockecoPlugin.missingCompanyCommandMessage())
                .isEqualTo("BlockStock 命令注册失败：未在 plugin.yml 中声明 company 命令");
    }

    @Test
    void market_configuration_accepts_configured_shanghai_zone_and_rejects_invalid_values() {
        assertThat(BlockecoPlugin.validateMarketConfiguration(10, "Asia/Shanghai").getId()).isEqualTo("Asia/Shanghai");
        assertThatThrownBy(() -> BlockecoPlugin.validateMarketConfiguration(-1, "Asia/Shanghai"))
                .hasMessageContaining("fee-bps");
        assertThatThrownBy(() -> BlockecoPlugin.validateMarketConfiguration(10_001, "Asia/Shanghai"))
                .hasMessageContaining("fee-bps");
        assertThatThrownBy(() -> BlockecoPlugin.validateMarketConfiguration(10, "not/a-zone"))
                .hasMessageContaining("time-zone");
    }

    @Test
    void plugin_metadata_entrypoint_exists_and_is_a_java_plugin() {
        Class<?> entrypoint = assertDoesNotThrow(() -> Class.forName(declaredEntrypointClass()));

        assertThat(entrypoint).isAssignableTo(JavaPlugin.class);
    }

    private String declaredEntrypointClass() throws Exception {
        InputStream resource = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertThat(resource).isNotNull();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> line.startsWith("main: "))
                    .map(line -> line.substring("main: ".length()))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
