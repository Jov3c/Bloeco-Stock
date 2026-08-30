package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.TransactionRunner;
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
        assertThat(tab.complete(playerWith("blockeco.company.create"), new String[] {"create", "红石", "10000.00", "5"})).containsExactly("50");
        assertThat(tab.complete(opWithAllPermissions(), new String[] {"recovery", ""})).containsExactly("list");
        assertThat(tab.complete(mock(CommandSender.class), new String[] {""})).isEmpty();
    }

    @Test
    void ipo_second_level_candidates_are_player_only_and_individually_permission_scoped() {
        assertThat(tab.complete(playerWith("blockeco.company.ipo.announce"), new String[] {"ipo", ""})).containsExactly("list", "info", "announce");
        assertThat(tab.complete(playerWith("blockeco.company.ipo.subscribe"), new String[] {"ipo", ""})).containsExactly("list", "info", "subscribe");
        assertThat(tab.complete(consoleWith("blockeco.company.ipo.announce", "blockeco.company.ipo.subscribe"), new String[] {"ipo", ""})).containsExactly("list", "info");
    }

    @Test
    void unknown_and_incomplete_paths_return_empty_immutable_lists() {
        assertThat(tab.complete(playerWith("blockeco.company.create"), new String[] {"unknown"})).isEmpty();
        assertThat(tab.complete(playerWith("blockeco.company.create"), new String[] {"create", "name"})).isEmpty();
        assertThat(tab.complete(playerWith("blockeco.company.info"), new String[] {"info", "红石"})).isEmpty();
        assertThatThrownBy(() -> tab.complete(playerWith("blockeco.company.create"), new String[] {""}).add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stockadmin_completion_exposes_only_configuration_to_authorized_senders() {
        MutableCompanyCreationRules live = new MutableCompanyCreationRules(new CompanyCreationRules(Money.ofMinor(1), Money.ofMinor(2), 0, 1_000, List.of(50)));
        StockAdminConfigCommand tab = new StockAdminConfigCommand(live, mock(StockAdminConfigCommand.ConfigStore.class), mock(cn.blockeco.exchange.ports.AuditLog.class), new TransactionRunner() { @Override public <T> T inTransaction(SqlWork<T> work) { throw new AssertionError(); } }, Runnable::run, java.time.Instant::now, new Messages(null));
        assertThat(tab.complete(playerWith("blockstock.admin.config"), new String[] {""})).containsExactly("config");
        assertThat(tab.complete(playerWith("blockstock.admin.config"), new String[] {"config", ""}))
                .containsExactly("min-capital", "cashout-limit");
        assertThat(tab.complete(mock(CommandSender.class), new String[] {""})).isEmpty();
    }

    private static Player playerWith(String... permissions) { Player player = mock(Player.class); for (String permission : permissions) when(player.hasPermission(permission)).thenReturn(true); return player; }
    private static CommandSender consoleWith(String... permissions) { CommandSender sender = mock(CommandSender.class); for (String permission : permissions) when(sender.hasPermission(permission)).thenReturn(true); return sender; }
    private static Player opWithAllPermissions() { return playerWith("blockeco.company.create", "blockeco.company.info", "blockeco.admin.recovery"); }
}
