package cn.blockeco.exchange.application;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps periodic SQL lifecycle work off the Paper thread and independently testable. */
public final class IpoLifecycleScheduler {
    public interface RepeatingScheduler { Cancellation everyMinute(Runnable task); }
    public interface Cancellation { void cancel(); }
    private final RepeatingScheduler scheduler; private final Supplier<Instant> now; private final PrimaryOfferingService offerings; private final Consumer<Throwable> failures; private Cancellation cancellation;
    public IpoLifecycleScheduler(RepeatingScheduler scheduler,Supplier<Instant> now,PrimaryOfferingService offerings,Consumer<Throwable> failures){this.scheduler=scheduler;this.now=now;this.offerings=offerings;this.failures=failures;}
    public void start(){if(cancellation==null)cancellation=scheduler.everyMinute(()->offerings.closeExpired(now.get()).whenComplete((ok,error)->{if(error!=null)failures.accept(error);}));}
    public void stop(){if(cancellation!=null){cancellation.cancel();cancellation=null;}}
}
