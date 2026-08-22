package cn.blockeco.exchange.paper;

import java.util.concurrent.atomic.AtomicBoolean;

/** Public read readiness is independent from potentially unsafe money/trading mutations. */
public final class SecondaryTradingGate implements CommandAcceptanceGate {
    private final AtomicBoolean publicReady = new AtomicBoolean();
    private final AtomicBoolean mutationsOpen = new AtomicBoolean();
    @Override public void setAccepting(boolean accepting) { publicReady.set(accepting); if (!accepting) mutationsOpen.set(false); }
    public void setMutationsOpen(boolean open) { mutationsOpen.set(open && publicReady.get()); }
    public boolean publicReady() { return publicReady.get(); }
    public boolean mutationsOpen() { return mutationsOpen.get(); }
}
