package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BluechipAdminCommandTest {
    @Test void rejectsPlayerWithoutBluechipPermissionBeforeAnyMutation() {
        Player player = mock(Player.class); when(player.hasPermission(BluechipAdminCommand.PERMISSION)).thenReturn(false);
        BluechipAdminCommand command = new BluechipAdminCommand(() -> { throw new AssertionError("must not initialize"); },
                paused -> { throw new AssertionError("must not pause"); }, (code, kind, value) -> { throw new AssertionError("must not fund"); },
                (scope, impact) -> { throw new AssertionError("must not trigger event"); }, new Messages(new org.bukkit.configuration.file.YamlConfiguration()));

        assertThat(command.onCommand(player, mock(Command.class), "stockadmin", new String[]{"bluechip", "init"})).isTrue();
    }
}
