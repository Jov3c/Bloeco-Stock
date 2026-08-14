package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.application.RegistrationRequest;
import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.CompanyRegistrationService;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

class CompanyCommandTest {
    private final UUID founder = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void create_parser_joins_multiword_name_before_final_dividend() {
        var result = CompanyCommand.parseCreate(founder, new String[] {"create", "North", "Star", "50"});

        assertThat(result).contains(new RegistrationRequest(founder, "North Star", 50));
    }

    @Test
    void create_parser_strips_optional_quotes_from_multiword_name() {
        var result = CompanyCommand.parseCreate(founder, new String[] {"create", "\"North", "Star\"", "70"});

        assertThat(result).contains(new RegistrationRequest(founder, "North Star", 70));
    }

    @Test
    void create_parser_rejects_dividend_outside_supported_choices() {
        var result = CompanyCommand.parseCreate(founder, new String[] {"create", "North", "40"});

        assertThat(result).isEmpty();
    }

    @Test
    void create_rejects_console_before_dispatching_registration() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        CommandSender console = mock(CommandSender.class);
        CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), mock(Plugin.class));

        command.onCommand(console, mock(Command.class), "company", new String[] {"create", "North", "30"});

        ArgumentCaptor<net.kyori.adventure.text.Component> message = ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(console).sendMessage(message.capture());
        assertThat(PlainTextComponentSerializer.plainText().serialize(message.getValue())).contains("only be used by a player");
        verifyNoInteractions(registration);
    }

    @Test
    void create_rejects_player_without_permission() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        Player player = mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(false);
        CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), mock(Plugin.class));

        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "30"});

        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
        verifyNoInteractions(registration);
    }

    @Test
    void create_rejects_second_submission_while_first_is_in_flight() {
        CompanyRegistrationService registration = mock(CompanyRegistrationService.class);
        Player player = mock(Player.class); when(player.hasPermission("blockeco.company.create")).thenReturn(true); when(player.getUniqueId()).thenReturn(founder);
        when(registration.register(any())).thenReturn(new CompletableFuture<>());
        CompanyCommand command = new CompanyCommand(registration, mock(CompanyQueryService.class), new Messages(null), mock(Plugin.class)); command.setAccepting(true);

        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "30"});
        command.onCommand(player, mock(Command.class), "company", new String[] {"create", "North", "30"});

        verify(registration, times(1)).register(new RegistrationRequest(founder, "North", 30));
        verify(player, times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
}
