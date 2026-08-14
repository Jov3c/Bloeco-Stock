package cn.blockeco.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.application.*;
import cn.blockeco.exchange.paper.*;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.concurrent.*;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PluginRuntimeTest {
 @Test void stop_rejects_real_command_and_closes_once() {
  CompanyRegistrationService service=mock(CompanyRegistrationService.class); MainThreadExecutor main=new MainThreadExecutor(){public <T> CompletionStage<T> submit(java.util.function.Supplier<T>w){return CompletableFuture.completedFuture(w.get());}};
  CompanyCommand command=new CompanyCommand(service,mock(CompanyQueryService.class),new Messages(null),main); command.setAccepting(true);
  BootstrapCoordinator<String> bootstrap=new BootstrapCoordinator<>(main,v->true,e->{},v->{}); ExecutorService executor=Executors.newSingleThreadExecutor(); java.util.concurrent.atomic.AtomicInteger closes=new java.util.concurrent.atomic.AtomicInteger();
  PluginRuntime runtime=new PluginRuntime(command,bootstrap,executor,closes::incrementAndGet); runtime.stop(); runtime.stop();
  Player player=mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true);
  command.onCommand(player,mock(Command.class),"company",new String[]{"create","North","30"});
  verifyNoInteractions(service); assertThat(closes).hasValue(1);
 }
}
