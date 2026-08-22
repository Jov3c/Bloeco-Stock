package cn.blockeco.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.application.*;
import cn.blockeco.exchange.paper.*;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.domain.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PluginRuntimeTest {
 private static final CompanyCreationRules RULES = new CompanyCreationRules(Money.fromMajor(new BigDecimal("1000.00"), 2), Money.fromMajor(new BigDecimal("10000.00"), 2), 2, 1000, List.of(30, 50, 70));
 @Test void runtime_readies_and_stops_both_command_gates() {
  CommandAcceptanceGate companyGate=mock(CommandAcceptanceGate.class); CommandAcceptanceGate stockGate=mock(CommandAcceptanceGate.class); PluginRuntime runtime=new PluginRuntime();
  assertThat(runtime.attachReady(List.of(companyGate,stockGate),()->{})).isTrue();
  verify(companyGate).setAccepting(true); verify(stockGate).setAccepting(true);
  runtime.stop(); verify(companyGate).setAccepting(false); verify(stockGate).setAccepting(false);
 }
 @Test void ready_attach_after_stop_keeps_each_gate_closed() {
  CommandAcceptanceGate companyGate=mock(CommandAcceptanceGate.class); CommandAcceptanceGate stockGate=mock(CommandAcceptanceGate.class); PluginRuntime runtime=new PluginRuntime(); runtime.stop();
  assertThat(runtime.attachReady(List.of(companyGate,stockGate),()->{})).isFalse();
  verify(companyGate).setAccepting(false); verify(stockGate).setAccepting(false);
 }
 @Test void stop_closes_published_database_before_a_blocked_migration_finishes() throws Exception {
  CompanyRegistrationService service=mock(CompanyRegistrationService.class); MainThreadExecutor main=new MainThreadExecutor(){public <T> CompletionStage<T> submit(java.util.function.Supplier<T>w){return CompletableFuture.completedFuture(w.get());}};
  CompanyCommand command=new CompanyCommand(service,mock(CompanyQueryService.class),new Messages(null),main,RULES); ExecutorService migrationExecutor=Executors.newSingleThreadExecutor(); ExecutorService stopper=Executors.newSingleThreadExecutor();
  CountDownLatch published=new CountDownLatch(1); CountDownLatch releaseMigration=new CountDownLatch(1); CountDownLatch closed=new CountDownLatch(1); java.util.concurrent.atomic.AtomicInteger closes=new java.util.concurrent.atomic.AtomicInteger(); java.util.concurrent.atomic.AtomicInteger wired=new java.util.concurrent.atomic.AtomicInteger();
  PluginRuntime runtime=new PluginRuntime(command); BootstrapCoordinator<AutoCloseable> bootstrap=new BootstrapCoordinator<>(main, value -> { wired.incrementAndGet(); return runtime.attachReady(command,value); }, failure -> {}, runtime::closeDatabase); runtime.attachBootstrap(bootstrap); runtime.attachExecutor(migrationExecutor);
  CompletableFuture<AutoCloseable> migration=CompletableFuture.supplyAsync(() -> { AutoCloseable database=() -> { closes.incrementAndGet(); closed.countDown(); }; runtime.attachDatabase(database); published.countDown(); try { releaseMigration.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } return database; }, migrationExecutor);
  bootstrap.coordinate(migration); assertThat(published.await(1, TimeUnit.SECONDS)).isTrue(); Future<?> stopping=stopper.submit(runtime::stop);
  assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(closes).hasValue(1); releaseMigration.countDown(); stopping.get(1, TimeUnit.SECONDS); migration.get(1, TimeUnit.SECONDS); stopper.shutdown();
  Player player=mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true); command.onCommand(player,mock(Command.class),"company",new String[]{"create","North","30"});
  assertThat(wired).hasValue(0); verifyNoInteractions(service); assertThat(migrationExecutor.isShutdown()).isTrue();
 }
 @Test void stop_during_migration_prevents_wiring_and_closes_completed_database_once() {
  CompanyRegistrationService service=mock(CompanyRegistrationService.class); MainThreadExecutor main=new MainThreadExecutor(){public <T> CompletionStage<T> submit(java.util.function.Supplier<T>w){return CompletableFuture.completedFuture(w.get());}};
  CompanyCommand command=new CompanyCommand(service,mock(CompanyQueryService.class),new Messages(null),main,RULES); command.setAccepting(true);
  CompletableFuture<AutoCloseable> migration=new CompletableFuture<>(); java.util.concurrent.atomic.AtomicInteger wired=new java.util.concurrent.atomic.AtomicInteger(); java.util.concurrent.atomic.AtomicInteger disabled=new java.util.concurrent.atomic.AtomicInteger(); java.util.concurrent.atomic.AtomicInteger closes=new java.util.concurrent.atomic.AtomicInteger(); ExecutorService executor=Executors.newSingleThreadExecutor();
  PluginRuntime runtime=new PluginRuntime(command); BootstrapCoordinator<AutoCloseable> bootstrap=new BootstrapCoordinator<>(main, value -> { wired.incrementAndGet(); return runtime.attachReady(command,value); }, failure -> disabled.incrementAndGet(), runtime::closeDatabase);
  runtime.attachBootstrap(bootstrap); runtime.attachExecutor(executor); bootstrap.coordinate(migration); runtime.stop(); migration.complete(closes::incrementAndGet);
  assertThat(wired).hasValue(0); assertThat(disabled).hasValue(0); assertThat(closes).hasValue(1); assertThat(executor.isShutdown()).isTrue(); assertThat(bootstrap.accepting()).isFalse();
 }
 @Test void ready_attach_racing_stop_closes_database_once() throws Exception {
  CompanyRegistrationService service=mock(CompanyRegistrationService.class); MainThreadExecutor main=new MainThreadExecutor(){public <T> CompletionStage<T> submit(java.util.function.Supplier<T>w){return CompletableFuture.completedFuture(w.get());}};
  CompanyCommand command=new CompanyCommand(service,mock(CompanyQueryService.class),new Messages(null),main,RULES); java.util.concurrent.atomic.AtomicInteger closes=new java.util.concurrent.atomic.AtomicInteger(); PluginRuntime runtime=new PluginRuntime(command);
  ExecutorService callers=Executors.newFixedThreadPool(2); java.util.concurrent.Future<?> attach=callers.submit(() -> runtime.attachReady(command, closes::incrementAndGet)); java.util.concurrent.Future<?> stop=callers.submit(runtime::stop); attach.get(); stop.get(); callers.shutdown();
  Player player=mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true); command.onCommand(player,mock(Command.class),"company",new String[]{"create","North","30"}); verifyNoInteractions(service); assertThat(closes).hasValue(1);
 }
 @Test void stop_rejects_real_command_and_closes_once() {
  CompanyRegistrationService service=mock(CompanyRegistrationService.class); MainThreadExecutor main=new MainThreadExecutor(){public <T> CompletionStage<T> submit(java.util.function.Supplier<T>w){return CompletableFuture.completedFuture(w.get());}};
  CompanyCommand command=new CompanyCommand(service,mock(CompanyQueryService.class),new Messages(null),main,RULES); command.setAccepting(true);
  BootstrapCoordinator<String> bootstrap=new BootstrapCoordinator<>(main,v->true,e->{},v->{}); ExecutorService executor=Executors.newSingleThreadExecutor(); java.util.concurrent.atomic.AtomicInteger closes=new java.util.concurrent.atomic.AtomicInteger();
  PluginRuntime runtime=new PluginRuntime(command,bootstrap,executor,closes::incrementAndGet); runtime.stop(); runtime.stop();
  Player player=mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true);
  command.onCommand(player,mock(Command.class),"company",new String[]{"create","North","30"});
  verifyNoInteractions(service); assertThat(closes).hasValue(1);
 }
 @Test void close_failure_identifies_the_blockstock_database() {
  PluginRuntime runtime=new PluginRuntime();

  assertThatThrownBy(() -> runtime.closeDatabase(() -> { throw new Exception("locked"); }))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("could not close BlockStock database");
 }
}
