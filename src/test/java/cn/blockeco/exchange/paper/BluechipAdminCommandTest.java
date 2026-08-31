package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BluechipAdminCommandTest {
    @Test void cashFundAdjustmentIsRejectedWithoutCallingTheFundControl() {
        Player player = mock(Player.class); when(player.hasPermission(BluechipAdminCommand.PERMISSION)).thenReturn(true);
        BluechipAdminCommand command = new BluechipAdminCommand(() -> { }, paused -> { }, (code, kind, value) -> { throw new AssertionError("cash must not mutate internal ledgers"); },
                new BluechipAdminCommand.EventControl() { @Override public void company(String code, int impact) { } @Override public void industry(String industry, int impact) { } @Override public void market(int impact) { } }, new Messages(new org.bukkit.configuration.file.YamlConfiguration()));
        command.onCommand(player, mock(Command.class), "stockadmin", new String[]{"bluechip", "fund", "BLC", "cash", "100"});
    }
    @Test void authorizedIndustryEventPassesTheConfiguredIndustryToEventControl() {
        Player player = mock(Player.class); when(player.hasPermission(BluechipAdminCommand.PERMISSION)).thenReturn(true);
        java.util.concurrent.atomic.AtomicReference<String> scope = new java.util.concurrent.atomic.AtomicReference<>();
        BluechipAdminCommand command = new BluechipAdminCommand(() -> { }, paused -> { }, (code, kind, value) -> { },
                new BluechipAdminCommand.EventControl() { @Override public void company(String code, int impact) { throw new AssertionError("wrong route"); } @Override public void industry(String industry, int impact) { scope.set(industry + ":" + impact); } @Override public void market(int impact) { throw new AssertionError("wrong route"); } }, new Messages(new org.bukkit.configuration.file.YamlConfiguration()), java.util.List.of("Energy"));

        command.onCommand(player, mock(Command.class), "stockadmin", new String[]{"bluechip", "event", "industry", "Energy", "500"});

        assertThat(scope).hasValue("Energy:500");
    }
    @Test void rejectsPlayerWithoutBluechipPermissionBeforeAnyMutation() {
        Player player = mock(Player.class); when(player.hasPermission(BluechipAdminCommand.PERMISSION)).thenReturn(false);
        BluechipAdminCommand command = new BluechipAdminCommand(() -> { throw new AssertionError("must not initialize"); },
                paused -> { throw new AssertionError("must not pause"); }, (code, kind, value) -> { throw new AssertionError("must not fund"); },
                new BluechipAdminCommand.EventControl() { @Override public void company(String code, int impact) { throw new AssertionError("must not trigger event"); } @Override public void industry(String industry, int impact) { throw new AssertionError("must not trigger event"); } @Override public void market(int impact) { throw new AssertionError("must not trigger event"); } }, new Messages(new org.bukkit.configuration.file.YamlConfiguration()));

        assertThat(command.onCommand(player, mock(Command.class), "stockadmin", new String[]{"bluechip", "init"})).isTrue();
    }

    @Test void quantStatusIsAReadOnlyPermissionGatedCompletion() {
        Player player = mock(Player.class); when(player.hasPermission(BluechipAdminCommand.PERMISSION)).thenReturn(true);
        BluechipAdminCommand command = new BluechipAdminCommand(() -> { }, paused -> { }, (code, kind, value) -> { },
                new BluechipAdminCommand.EventControl() { @Override public void company(String code, int impact) { } @Override public void industry(String industry, int impact) { } @Override public void market(int impact) { } },
                new Messages(new org.bukkit.configuration.file.YamlConfiguration()));

        assertThat(command.onTabComplete(player, mock(Command.class), "stockadmin", new String[]{"bluechip", "quant", ""}))
                .containsExactly("status");
    }
}
