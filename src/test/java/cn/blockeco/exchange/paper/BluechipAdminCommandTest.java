package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BluechipAdminCommandTest {
    @Test void authorizedIndustryEventRoutesToIndustryEventControl() {
        Player player = mock(Player.class); when(player.hasPermission(BluechipAdminCommand.PERMISSION)).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<String> scope = new java.util.concurrent.atomic.AtomicReference<>();
        BluechipAdminCommand command = new BluechipAdminCommand(() -> { }, paused -> { }, (code, kind, value) -> { },
                (target, impact) -> scope.set(target + ":" + impact), new Messages(new org.bukkit.configuration.file.YamlConfiguration()));

        command.onCommand(player, mock(Command.class), "stockadmin", new String[]{"bluechip", "event", "industry", "500"});

        assertThat(scope).hasValue("industry:500");
    }
    @Test void rejectsPlayerWithoutBluechipPermissionBeforeAnyMutation() {
        Player player = mock(Player.class); when(player.hasPermission(BluechipAdminCommand.PERMISSION)).thenReturn(false);
        BluechipAdminCommand command = new BluechipAdminCommand(() -> { throw new AssertionError("must not initialize"); },
                paused -> { throw new AssertionError("must not pause"); }, (code, kind, value) -> { throw new AssertionError("must not fund"); },
                (scope, impact) -> { throw new AssertionError("must not trigger event"); }, new Messages(new org.bukkit.configuration.file.YamlConfiguration()));

        assertThat(command.onCommand(player, mock(Command.class), "stockadmin", new String[]{"bluechip", "init"})).isTrue();
    }
}
