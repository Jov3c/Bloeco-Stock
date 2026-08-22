package cn.blockeco.exchange.paper;

/** Prevents Paper commands from touching services until startup has produced a safe snapshot. */
public interface CommandAcceptanceGate {
    void setAccepting(boolean accepting);
}
