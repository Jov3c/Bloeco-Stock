package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class IpoLifecycleSchedulerTest {
 @Test void schedules_one_minute_async_lifecycle_and_cancels_it_on_stop(){ PrimaryOfferingService offerings=mock(PrimaryOfferingService.class); when(offerings.closeExpired(any())).thenReturn(CompletableFuture.completedFuture(null)); Holder holder=new Holder(); IpoLifecycleScheduler scheduler=new IpoLifecycleScheduler(task->{holder.task=task;return ()->holder.cancelled=true;},()->Instant.EPOCH,offerings,failure->{}); scheduler.start(); holder.task.run(); verify(offerings).closeExpired(Instant.EPOCH); scheduler.stop(); assertThat(holder.cancelled).isTrue(); }
 @Test void invokes_the_success_callback_only_after_a_successful_close(){ PrimaryOfferingService offerings=mock(PrimaryOfferingService.class); CompletableFuture<Void> close=new CompletableFuture<>(); when(offerings.closeExpired(any())).thenReturn(close); Holder holder=new Holder(); java.util.function.Consumer<Void> refreshed=mock(java.util.function.Consumer.class); IpoLifecycleScheduler scheduler=new IpoLifecycleScheduler(task->{holder.task=task;return ()->{};},()->Instant.EPOCH,offerings,failure->{},refreshed); scheduler.start();holder.task.run();close.complete(null);verify(refreshed).accept(null); }
 @Test void reports_close_failure_without_requesting_refresh(){ PrimaryOfferingService offerings=mock(PrimaryOfferingService.class); CompletableFuture<Void> close=new CompletableFuture<>();when(offerings.closeExpired(any())).thenReturn(close);Holder holder=new Holder();java.util.function.Consumer<Throwable> failures=mock(java.util.function.Consumer.class);java.util.function.Consumer<Void> refreshed=mock(java.util.function.Consumer.class);IpoLifecycleScheduler scheduler=new IpoLifecycleScheduler(task->{holder.task=task;return ()->{};},()->Instant.EPOCH,offerings,failures,refreshed);scheduler.start();holder.task.run();IllegalStateException error=new IllegalStateException("close failed");close.completeExceptionally(error);verify(failures).accept(error);verifyNoInteractions(refreshed);}
 static final class Holder { Runnable task; boolean cancelled; }
}
