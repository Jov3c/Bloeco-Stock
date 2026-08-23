package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockGuiSessionTest {
    @Test void next_page_replaces_the_session_id_and_keeps_only_its_owner() {
        UUID owner = UUID.randomUUID();

        StockGuiSession initial = StockGuiSession.open(owner);
        StockGuiSession next = initial.next(StockGuiSession.Page.MARKET, 1, null, null);

        assertThat(next.id()).isNotEqualTo(initial.id());
        assertThat(next.belongsTo(owner)).isTrue();
        assertThat(next.belongsTo(UUID.randomUUID())).isFalse();
    }
}
