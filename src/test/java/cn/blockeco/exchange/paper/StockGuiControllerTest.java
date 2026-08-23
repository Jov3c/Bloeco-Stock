package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockGuiControllerTest {
    @Test void an_old_session_cannot_match_after_the_player_opens_a_new_page() {
        StockGuiController controller = StockGuiController.forSessionTests();
        UUID player = UUID.randomUUID();
        StockGuiSession first = controller.openSession(player, StockGuiSession.Page.HOME, 0, null, null);
        StockGuiSession second = controller.openSession(player, StockGuiSession.Page.MARKET, 0, null, null);

        assertThat(controller.matches(player, first.id())).isFalse();
        assertThat(controller.matches(player, second.id())).isTrue();
    }
}
