package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.application.PublicStockSymbol;
import java.util.List;
import java.util.Optional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

class StockTabCompleterTest {
    @Test void tab_completion_reads_cache_only_for_company_name_and_code() {
        PublicStockSymbolCache cache = new PublicStockSymbolCache();
        cache.replaceForTest(List.of(new PublicStockSymbol("红石工业", Optional.of("BS000001"))));
        StockTabCompleter completer = new StockTabCompleter(cache);
        CommandSender sender = mock(CommandSender.class);

        assertThat(completer.onTabComplete(sender, mock(Command.class), "stock", new String[] {"info", ""}))
                .contains("红石工业", "BS000001");
    }
}
