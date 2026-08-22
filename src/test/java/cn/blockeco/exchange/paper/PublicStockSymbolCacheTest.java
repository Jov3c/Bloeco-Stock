package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.blockeco.exchange.application.PublicStockQueryService;
import cn.blockeco.exchange.application.PublicStockSymbol;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class PublicStockSymbolCacheTest {
    @Test void failed_refresh_retains_the_previous_immutable_snapshot() {
        PublicStockQueryService service = mock(PublicStockQueryService.class);
        PublicStockSymbol symbol = new PublicStockSymbol("红石工业", Optional.of("BS000001"));
        when(service.symbols()).thenReturn(CompletableFuture.completedFuture(List.of(symbol)));
        PublicStockSymbolCache cache = new PublicStockSymbolCache();

        cache.refresh(service);
        assertThatThrownBy(() -> cache.snapshot().add(symbol)).isInstanceOf(UnsupportedOperationException.class);
        when(service.symbols()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));

        assertThat(cache.snapshot()).containsExactly(symbol);
        assertThat(cache.refresh(service)).isCompletedExceptionally();
        assertThat(cache.snapshot()).containsExactly(symbol);
    }
}
