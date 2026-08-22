package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.blockeco.exchange.application.PublicStockQueryService;
import cn.blockeco.exchange.application.PublicStockSymbol;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PublicStockSymbolCacheTest {
    @Test void refresh_updates_an_immutable_non_blocking_snapshot() {
        PublicStockQueryService service = mock(PublicStockQueryService.class);
        PublicStockSymbol symbol = new PublicStockSymbol("红石工业", Optional.of("BS000001"));
        when(service.symbols()).thenReturn(CompletableFuture.completedFuture(List.of(symbol)));
        PublicStockSymbolCache cache = new PublicStockSymbolCache();

        cache.refresh(service);

        assertThat(cache.snapshot()).containsExactly(symbol);
    }
}
