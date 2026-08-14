package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class IpoLifecycleSchedulerTest {
 @Test void schedules_one_minute_async_lifecycle_and_cancels_it_on_stop(){ PrimaryOfferingService offerings=mock(PrimaryOfferingService.class); when(offerings.closeExpired(any())).thenReturn(CompletableFuture.completedFuture(null)); Holder holder=new Holder(); IpoLifecycleScheduler scheduler=new IpoLifecycleScheduler(task->{holder.task=task;return ()->holder.cancelled=true;},()->Instant.EPOCH,offerings,failure->{}); scheduler.start(); holder.task.run(); verify(offerings).closeExpired(Instant.EPOCH); scheduler.stop(); assertThat(holder.cancelled).isTrue(); }
 static final class Holder { Runnable task; boolean cancelled; }
}
