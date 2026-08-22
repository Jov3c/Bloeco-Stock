package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.application.PrimaryOfferingService;
import cn.blockeco.exchange.application.PublicStockQueryService;
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

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
