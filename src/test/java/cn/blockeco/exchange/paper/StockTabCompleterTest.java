package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.application.PublicStockSymbol;
import java.util.List;
import java.util.Optional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockTabCompleterTest {
    @Test void tab_completion_reads_cache_only_for_company_name_and_code() {
        PublicStockSymbolCache cache = new PublicStockSymbolCache();
        cache.replaceForTest(List.of(new PublicStockSymbol("红石工业", Optional.of("BS000001"))));
        StockTabCompleter completer = new StockTabCompleter(cache);
        CommandSender sender = mock(CommandSender.class);

        assertThat(completer.onTabComplete(sender, mock(Command.class), "stock", new String[] {"info", ""}))
                .contains("红石工业", "BS000001");
    }

    @Test void top_level_completion_is_permission_filtered_and_never_null() {
        StockTabCompleter completer = new StockTabCompleter(new PublicStockSymbolCache());
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("blockeco.stock.trade")).thenReturn(false);
        when(sender.hasPermission("blockeco.stock.cash")).thenReturn(false);
        when(sender.hasPermission("blockeco.stock.subscribe")).thenReturn(false);

        assertThat(completer.onTabComplete(sender, mock(Command.class), "stock", new String[] {""}))
                .contains("market", "book").doesNotContain("buy", "sell", "deposit").isNotNull();
        assertThat(completer.onTabComplete(sender, mock(Command.class), "stock", new String[] {"buy", ""})).isEmpty();
        assertThat(completer.onTabComplete(sender, mock(Command.class), "stock", new String[] {"subscribe", ""})).isEmpty();
        assertThat(completer.onTabComplete(sender, mock(Command.class), "stock", new String[] {"cancel", ""})).isEmpty();
    }
}
