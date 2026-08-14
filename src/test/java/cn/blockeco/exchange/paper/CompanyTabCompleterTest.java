package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.money.Money;
import java.math.BigDecimal;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CompanyTabCompleterTest {
    private final CompanyTabCompleter tab = new CompanyTabCompleter(new CompanyCreationRules(Money.fromMajor(new BigDecimal("1000.00"), 2), Money.fromMajor(new BigDecimal("10000.00"), 2), 2, 1000, List.of(30, 50, 70)));

    @Test
    void root_completion_shows_only_sender_permitted_commands() {
        assertThat(tab.complete(playerWith("blockeco.company.create", "blockeco.company.info"), new String[] {""})).containsExactly("create", "info");
        assertThat(tab.complete(opWithAllPermissions(), new String[] {""})).containsExactly("create", "info", "recovery");
        assertThat(tab.complete(consoleWith("blockeco.company.create"), new String[] {""})).doesNotContain("create");
    }

    @Test
    void dividend_and_recovery_candidates_are_permission_scoped_and_prefix_filtered() {
        assertThat(tab.complete(playerWith("blockeco.company.create"), new String[] {"create", "红石", "5"})).containsExactly("50");
        assertThat(tab.complete(opWithAllPermissions(), new String[] {"recovery", ""})).containsExactly("list");
        assertThat(tab.complete(mock(CommandSender.class), new String[] {""})).isEmpty();
    }

    @Test
    void unknown_and_incomplete_paths_return_empty_immutable_lists() {
        assertThat(tab.complete(playerWith("blockeco.company.create"), new String[] {"unknown"})).isEmpty();
        assertThat(tab.complete(playerWith("blockeco.company.create"), new String[] {"create", "name"})).isEmpty();
        assertThat(tab.complete(playerWith("blockeco.company.info"), new String[] {"info", "红石"})).isEmpty();
        assertThatThrownBy(() -> tab.complete(playerWith("blockeco.company.create"), new String[] {""}).add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Player playerWith(String... permissions) { Player player = mock(Player.class); for (String permission : permissions) when(player.hasPermission(permission)).thenReturn(true); return player; }
    private static CommandSender consoleWith(String... permissions) { CommandSender sender = mock(CommandSender.class); for (String permission : permissions) when(sender.hasPermission(permission)).thenReturn(true); return sender; }
    private static Player opWithAllPermissions() { return playerWith("blockeco.company.create", "blockeco.company.info", "blockeco.admin.recovery"); }
}
