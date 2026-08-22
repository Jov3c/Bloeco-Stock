package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.PublicStockQueryService;
import cn.blockeco.exchange.application.PublicStockSymbol;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Main-thread-safe immutable snapshot with no persistence dependency. */
public final class PublicStockSymbolCache {
    private final AtomicReference<List<PublicStockSymbol>> snapshot = new AtomicReference<>(List.of());
    public List<PublicStockSymbol> snapshot() { return snapshot.get(); }
    void replaceForTest(List<PublicStockSymbol> symbols) { snapshot.set(List.copyOf(symbols)); }
    public CompletionStage<Void> refresh(PublicStockQueryService service) { return service.symbols().thenAccept(symbols -> snapshot.set(List.copyOf(symbols))); }
}
