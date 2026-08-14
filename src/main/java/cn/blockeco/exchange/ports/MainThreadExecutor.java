package cn.blockeco.exchange.ports;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
@FunctionalInterface public interface MainThreadExecutor { <T> CompletionStage<T> submit(Supplier<T> work); }
