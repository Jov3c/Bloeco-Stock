package cn.blockeco.exchange.paper;

import java.util.Objects;
import java.util.function.Consumer;

/** Defers inventory mutations until after Bukkit has completed the current click transaction. */
final class GuiTransitions {
    private GuiTransitions() { }

    static void defer(Consumer<Runnable> scheduler, Runnable action) {
        Objects.requireNonNull(scheduler, "scheduler").accept(Objects.requireNonNull(action, "action"));
    }
}
