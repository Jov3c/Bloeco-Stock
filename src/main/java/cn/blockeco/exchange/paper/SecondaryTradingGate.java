package cn.blockeco.exchange.paper;

import java.util.concurrent.atomic.AtomicReference;

/** Public read readiness is independent from potentially unsafe money/trading mutations. */
public final class SecondaryTradingGate implements CommandAcceptanceGate {
    private enum State { CLOSED, PUBLIC_ONLY, OPEN }
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    @Override public void setAccepting(boolean accepting) { state.updateAndGet(old -> accepting ? (old==State.OPEN?State.OPEN:State.PUBLIC_ONLY) : State.CLOSED); }
    public void setMutationsOpen(boolean open) { state.updateAndGet(old -> open && old!=State.CLOSED ? State.OPEN : old==State.CLOSED?State.CLOSED:State.PUBLIC_ONLY); }
    public boolean publicReady() { return state.get()!=State.CLOSED; }
    public boolean mutationsOpen() { return state.get()==State.OPEN; }
}
