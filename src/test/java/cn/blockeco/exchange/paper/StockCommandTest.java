package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.application.PrimaryOfferingService;
import cn.blockeco.exchange.application.PublicStockQueryService;
import cn.blockeco.exchange.application.SecuritiesCashService;
import cn.blockeco.exchange.application.SecondaryMarketService;
import cn.blockeco.exchange.application.SecondaryMarketQueryService;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class StockCommandTest {
    private static final MainThreadExecutor DIRECT_MAIN = new MainThreadExecutor() {
        @Override public <T> CompletionStage<T> submit(java.util.function.Supplier<T> work) {
            return CompletableFuture.completedFuture(work.get());
        }
    };

    @Test void not_ready_non_help_command_only_initializes_without_query_or_vault() {
        PublicStockQueryService queries = mock(PublicStockQueryService.class);
        PrimaryOfferingService offerings = mock(PrimaryOfferingService.class);
        BooleanSupplier accepting = () -> false;
        StockCommand command = new StockCommand(queries, offerings, DIRECT_MAIN, accepting, new Messages(null));
        CommandSender sender = mock(CommandSender.class);

        command.onCommand(sender, mock(Command.class), "stock", new String[] {"market"});

        verify(sender).sendMessage(org.mockito.ArgumentMatchers.<Component>argThat(component -> plain(component).equals("BlockStock 正在初始化，请稍后再试。")));
        verifyNoInteractions(queries, offerings);
    }

    @Test void root_help_is_static_before_ready() {
        StockCommand command = new StockCommand(mock(PublicStockQueryService.class), mock(PrimaryOfferingService.class), DIRECT_MAIN, () -> false, new Messages(null));
        CommandSender sender = mock(CommandSender.class);

        command.onCommand(sender, mock(Command.class), "stock", new String[] {"help"});

        verify(sender, atLeastOnce()).sendMessage(any(Component.class));
    }

    @Test void subscribe_requires_a_positive_share_count_before_lookup() {
        PublicStockQueryService queries = mock(PublicStockQueryService.class);
        PrimaryOfferingService offerings = mock(PrimaryOfferingService.class);
        StockCommand command = new StockCommand(queries, offerings, DIRECT_MAIN, () -> true, new Messages(null));
        command.setAccepting(true);
        Player player = mock(Player.class);
        when(player.hasPermission("blockeco.stock.subscribe")).thenReturn(true);

        command.onCommand(player, mock(Command.class), "stock", new String[] {"subscribe", "红石工业", "0"});

        verifyNoInteractions(queries, offerings);
        verify(player).sendMessage(org.mockito.ArgumentMatchers.<Component>argThat(component -> plain(component).contains("正整数股")));
    }

    @Test void subscription_does_not_dispatch_a_second_saga_while_the_first_is_in_flight() {
        PublicStockQueryService queries = mock(PublicStockQueryService.class);
        PrimaryOfferingService offerings = mock(PrimaryOfferingService.class);
        UUID offering = UUID.randomUUID();
        when(queries.resolveOpenOffering("红石工业")).thenReturn(CompletableFuture.completedFuture(Optional.of(offering)));
        when(offerings.subscribe(any(), eq(offering), eq(2L))).thenReturn(new CompletableFuture<>());
        StockCommand command = new StockCommand(queries, offerings, DIRECT_MAIN, () -> true, new Messages(null));
        command.setAccepting(true);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("blockeco.stock.subscribe")).thenReturn(true);

        command.onCommand(player, mock(Command.class), "stock", new String[] {"subscribe", "红石工业", "2"});
        command.onCommand(player, mock(Command.class), "stock", new String[] {"subscribe", "红石工业", "2"});

        verify(offerings, times(1)).subscribe(playerId, offering, 2L);
        verify(player).sendMessage(org.mockito.ArgumentMatchers.<Component>argThat(component -> plain(component).equals("IPO 认购正在处理中。")));
    }

    @Test void announcements_rejects_an_out_of_range_numeric_limit_without_querying() {
        PublicStockQueryService queries = mock(PublicStockQueryService.class);
        StockCommand command = new StockCommand(queries, mock(PrimaryOfferingService.class), DIRECT_MAIN, () -> true, new Messages(null));
        command.setAccepting(true);
        CommandSender sender = mock(CommandSender.class);

        command.onCommand(sender, mock(Command.class), "stock", new String[] {"announcements", "红石工业", "999999999999"});

        verifyNoInteractions(queries);
        verify(sender).sendMessage(org.mockito.ArgumentMatchers.<Component>argThat(component -> plain(component).equals("用法：/stock announcements <公司名|代码> [1-50]")));
    }

    @Test void malformed_deposit_is_rejected_before_cash_service() {
        SecuritiesCashService cash = mock(SecuritiesCashService.class);
        StockCommand command = new StockCommand(mock(PublicStockQueryService.class), mock(PrimaryOfferingService.class), cash, null, null, DIRECT_MAIN, () -> true, new Messages(null), 2);
        command.setAccepting(true);
        Player player = mock(Player.class);
        when(player.hasPermission("blockeco.stock.cash")).thenReturn(true);

        command.onCommand(player, mock(Command.class), "stock", new String[] {"deposit", "1.234"});

        verifyNoInteractions(cash);
        verify(player).sendMessage(org.mockito.ArgumentMatchers.<Component>argThat(component -> plain(component).contains("货币精度")));
    }

    @Test void every_private_secondary_command_rejects_console_without_calling_a_service() {
        SecuritiesCashService cash = mock(SecuritiesCashService.class);
        SecondaryMarketService trading = mock(SecondaryMarketService.class);
        SecondaryMarketQueryService reads = mock(SecondaryMarketQueryService.class);
        StockCommand command = secondary(cash, trading, reads, DIRECT_MAIN, () -> true);
        CommandSender console = mock(CommandSender.class);

        for (String[] args : List.of(new String[] {"cash"}, new String[] {"deposit", "1.00"},
                new String[] {"withdraw", "1.00"}, new String[] {"buy", "BS000001", "1", "1.00"},
                new String[] {"sell", "BS000001", "1", "1.00"}, new String[] {"cancel", UUID.randomUUID().toString()},
                new String[] {"portfolio"}, new String[] {"orders"}, new String[] {"trades"})) {
            command.onCommand(console, mock(Command.class), "stock", args);
        }

        verify(console, times(9)).sendMessage(argThat(c -> plain(c).equals("此命令只能由玩家执行。")));
        verifyNoInteractions(cash, trading, reads);
    }

    @Test void denied_permission_blocks_each_private_family_before_service() {
        SecuritiesCashService cash = mock(SecuritiesCashService.class);
        SecondaryMarketService trading = mock(SecondaryMarketService.class);
        SecondaryMarketQueryService reads = mock(SecondaryMarketQueryService.class);
        StockCommand command = secondary(cash, trading, reads, DIRECT_MAIN, () -> true);
        Player player = player(false);

        for (String[] args : List.of(new String[] {"cash"}, new String[] {"deposit", "1.00"},
                new String[] {"buy", "BS000001", "1", "1.00"}, new String[] {"portfolio"},
                new String[] {"orders"}, new String[] {"trades"})) command.onCommand(player, mock(Command.class), "stock", args);

        verify(player, times(6)).sendMessage(argThat(c -> plain(c).equals("你没有权限。")));
        verifyNoInteractions(cash, trading, reads);
    }

    @Test void malformed_order_cancel_and_history_arguments_do_not_call_services() {
        SecuritiesCashService cash = mock(SecuritiesCashService.class);
        SecondaryMarketService trading = mock(SecondaryMarketService.class);
        SecondaryMarketQueryService reads = mock(SecondaryMarketQueryService.class);
        StockCommand command = secondary(cash, trading, reads, DIRECT_MAIN, () -> true);
        Player player = player(true);

        command.onCommand(player, mock(Command.class), "stock", new String[] {"buy", "BS000001", "0", "1.00"});
        command.onCommand(player, mock(Command.class), "stock", new String[] {"sell", "BS000001", "1", "0"});
        command.onCommand(player, mock(Command.class), "stock", new String[] {"cancel", "not-a-uuid"});
        command.onCommand(player, mock(Command.class), "stock", new String[] {"orders", "51"});
        command.onCommand(player, mock(Command.class), "stock", new String[] {"trades", "0"});

        verifyNoInteractions(cash, trading, reads);
    }

    @Test void book_always_requests_exactly_five_public_levels() {
        SecondaryMarketQueryService reads = mock(SecondaryMarketQueryService.class);
        when(reads.book("BS000001", 5)).thenReturn(new CompletableFuture<>());
        StockCommand command = secondary(mock(SecuritiesCashService.class), mock(SecondaryMarketService.class), reads, DIRECT_MAIN, () -> true);

        command.onCommand(mock(CommandSender.class), mock(Command.class), "stock", new String[] {"book", "BS000001"});

        verify(reads).book("BS000001", 5);
    }

    @Test void async_reply_waits_for_main_executor_and_suppresses_offline_player() {
        SecondaryMarketQueryService reads = mock(SecondaryMarketQueryService.class);
        CompletableFuture<cn.blockeco.exchange.application.PortfolioView> future = new CompletableFuture<>();
        when(reads.portfolio(any())).thenReturn(future);
        java.util.ArrayList<java.util.function.Supplier<?>> scheduled = new java.util.ArrayList<>();
        MainThreadExecutor queuedMain = new MainThreadExecutor() { @Override public <T> CompletionStage<T> submit(java.util.function.Supplier<T> work) { scheduled.add(work); return new CompletableFuture<>(); } };
        StockCommand command = secondary(mock(SecuritiesCashService.class), mock(SecondaryMarketService.class), reads, queuedMain, () -> true);
        Player player = player(true); when(player.isOnline()).thenReturn(false);

        command.onCommand(player, mock(Command.class), "stock", new String[] {"cash"});
        future.complete(new cn.blockeco.exchange.application.PortfolioView(Money.zero(), Money.zero(), List.of()));

        assertThat(scheduled).hasSize(1);
        scheduled.getFirst().get();
        verify(player, never()).sendMessage(any(Component.class));
    }

    private static StockCommand secondary(SecuritiesCashService cash, SecondaryMarketService trading, SecondaryMarketQueryService reads, MainThreadExecutor main, BooleanSupplier accepting) {
        StockCommand command = new StockCommand(mock(PublicStockQueryService.class), mock(PrimaryOfferingService.class), cash, trading, reads, main, accepting, new Messages(null), 2);
        command.setAccepting(true);
        return command;
    }

    private static Player player(boolean allowed) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(allowed);
        return player;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
