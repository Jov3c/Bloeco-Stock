package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/** Schedules Bukkit/Vault work on Paper's primary thread. */
public final class PaperMainThread implements MainThreadExecutor {
    private final Plugin plugin;
    public PaperMainThread(Plugin plugin) { this.plugin = plugin; }
    @Override public <T> CompletionStage<T> submit(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try { result.complete(work.get()); } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }
}
