package cn.blockeco.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.junit.jupiter.api.Test;

class BootstrapCoordinatorTest {
    private static final MainThreadExecutor DIRECT_MAIN = new MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(Supplier<T> work) { return CompletableFuture.completedFuture(work.get()); } };
    @Test void provider_resolution_rejects_missing_registration() { assertThat(VaultProviderResolver.isAvailable(null)).isFalse(); }
    @Test void provider_resolution_rejects_registration_without_provider() { RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class); assertThat(VaultProviderResolver.isAvailable(registration)).isFalse(); }
    @Test void provider_resolution_accepts_provider() { RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class); when(registration.getProvider()).thenReturn(mock(Economy.class)); assertThat(VaultProviderResolver.isAvailable(registration)).isTrue(); }
    @Test void coordinator_keeps_initializing_until_success_callback_runs_on_main_thread() {
        CompletableFuture<String> migration = new CompletableFuture<>(); AtomicInteger ready = new AtomicInteger(); AtomicInteger disabled = new AtomicInteger();
        BootstrapCoordinator<String> coordinator = new BootstrapCoordinator<>(DIRECT_MAIN, value -> ready.incrementAndGet(), failure -> disabled.incrementAndGet());
        coordinator.coordinate(migration); assertThat(coordinator.accepting()).isFalse(); migration.complete("db");
        assertThat(coordinator.accepting()).isTrue(); assertThat(disabled).hasValue(0);
    }
    @Test void coordinator_disables_once_after_migration_failure() {
        AtomicInteger disabled = new AtomicInteger(); BootstrapCoordinator<String> coordinator = new BootstrapCoordinator<>(DIRECT_MAIN, value -> {}, failure -> disabled.incrementAndGet());
        coordinator.coordinate(CompletableFuture.failedFuture(new IllegalStateException("migration")));
        assertThat(disabled).hasValue(1); assertThat(coordinator.accepting()).isFalse(); coordinator.stop(); assertThat(coordinator.accepting()).isFalse();
    }
}
