package cn.blockeco.exchange.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuiTransitionsTest {
    @Test
    void defersInventoryWorkUntilTheSchedulerRunsIt() {
        List<Runnable> scheduled = new ArrayList<>();
        List<String> events = new ArrayList<>();

        GuiTransitions.defer(scheduled::add, () -> events.add("opened"));

        assertEquals(List.of(), events);
        assertEquals(1, scheduled.size());
        scheduled.removeFirst().run();
        assertEquals(List.of("opened"), events);
    }
}
