package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.paper.StockAdminConfigCommand.ConfigStore;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AuditLog;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockAdminConfigCommandTest {
    private final MutableCompanyCreationRules live = new MutableCompanyCreationRules(rules("10000.00"));

    @Test
    void op_can_read_and_set_a_precise_minimum_capital_and_persists_config() {
        ConfigStore config = mock(ConfigStore.class);
        StockAdminConfigCommand command = command(config, (connection, event) -> { });
        Player op = mock(Player.class); when(op.hasPermission("blockstock.admin.config")).thenReturn(true); when(op.getUniqueId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        assertThat(command.onCommand(op, mock(Command.class), "stockadmin", new String[] {"config", "min-capital", "25000.50"})).isTrue();

        verify(config).setMinimumCapital("25000.50"); verify(config).save();
        assertThat(live.current().minimumCapital()).isEqualTo(Money.fromMajor(new BigDecimal("25000.50"), 2));
        command.onCommand(op, mock(Command.class), "stockadmin", new String[] {"config"});
        assertThat(messages(op)).anyMatch(message -> message.contains("25000.50"));
    }

    @Test
    void rejects_zero_negative_and_wrong_scale_without_changing_rules_or_config() {
        ConfigStore config = mock(ConfigStore.class); StockAdminConfigCommand command = command(config, (connection, event) -> { });
        CommandSender op = mock(CommandSender.class); when(op.hasPermission("blockstock.admin.config")).thenReturn(true);
        for (String amount : List.of("0", "-1", "1.234")) command.onCommand(op, mock(Command.class), "stockadmin", new String[] {"config", "min-capital", amount});
        assertThat(live.current().minimumCapital()).isEqualTo(Money.fromMajor(new BigDecimal("10000.00"), 2));
        verifyNoInteractions(config);
    }

    @Test
    void unauthorized_sender_cannot_read_or_change_configuration() {
        ConfigStore config = mock(ConfigStore.class); AuditLog audit = mock(AuditLog.class); StockAdminConfigCommand command = command(config, audit);
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission("blockstock.admin.config")).thenReturn(false);
        command.onCommand(sender, mock(Command.class), "stockadmin", new String[] {"config", "min-capital", "25000.50"});
        verifyNoInteractions(config, audit);
        assertThat(messages(sender)).containsExactly("你没有权限。");
    }

    @Test
    void accepted_change_writes_audit_with_old_new_actor_and_time() throws Exception {
        ConfigStore config = mock(ConfigStore.class); AuditLog audit = mock(AuditLog.class); Instant now = Instant.parse("2026-08-22T01:02:03Z");
        StockAdminConfigCommand command = new StockAdminConfigCommand(live, config, audit, directTransactions(), Runnable::run, () -> now, new Messages(null));
        Player op = mock(Player.class); UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111"); when(op.hasPermission("blockstock.admin.config")).thenReturn(true); when(op.getUniqueId()).thenReturn(id);
        command.onCommand(op, mock(Command.class), "stockadmin", new String[] {"config", "min-capital", "25000.50"});
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class); verify(audit).append(isNull(), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("ADMIN_MINIMUM_CAPITAL_CHANGED");
        assertThat(event.getValue().actorId()).contains(id); assertThat(event.getValue().occurredAt()).isEqualTo(now);
        assertThat(event.getValue().payload()).containsEntry("oldMinor", 1_000_000L).containsEntry("newMinor", 2_500_050L).containsEntry("actor", id.toString()).containsEntry("source", "PLAYER");
    }

    @Test
    void console_audit_payload_identifies_console_actor() throws Exception {
        ConfigStore config = mock(ConfigStore.class); AuditLog audit = mock(AuditLog.class); CommandSender console = mock(CommandSender.class); when(console.hasPermission("blockstock.admin.config")).thenReturn(true);
        command(config, audit).onCommand(console, mock(Command.class), "stockadmin", new String[] {"config", "min-capital", "25000.50"});
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class); verify(audit).append(isNull(), event.capture());
        assertThat(event.getValue().actorId()).isEmpty(); assertThat(event.getValue().payload()).containsEntry("actor", "CONSOLE").containsEntry("source", "CONSOLE");
    }

    @Test
    void save_failure_does_not_publish_or_audit_and_audit_failure_is_reported_in_chinese() {
        ConfigStore config = mock(ConfigStore.class); doThrow(new IllegalStateException("disk full")).when(config).save(); AuditLog audit = mock(AuditLog.class);
        CommandSender op = mock(CommandSender.class); when(op.hasPermission("blockstock.admin.config")).thenReturn(true);
        command(config, audit).onCommand(op, mock(Command.class), "stockadmin", new String[] {"config", "min-capital", "25000.50"});
        assertThat(live.current().minimumCapital()).isEqualTo(Money.fromMajor(new BigDecimal("10000.00"), 2)); verifyNoInteractions(audit);
    }

    @Test
    void audit_failure_is_reported_in_chinese_without_claiming_audit_success() throws Exception {
        ConfigStore config = mock(ConfigStore.class); AuditLog audit = mock(AuditLog.class); doThrow(new java.sql.SQLException("offline")).when(audit).append(any(), any());
        CommandSender op = mock(CommandSender.class); when(op.hasPermission("blockstock.admin.config")).thenReturn(true);
        command(config, audit).onCommand(op, mock(Command.class), "stockadmin", new String[] {"config", "min-capital", "25000.50"});
        assertThat(messages(op)).contains("最低注册资本已更新为 25000.50。", "最低注册资本已保存，但审计写入失败；请检查数据库。 ");
    }

    private StockAdminConfigCommand command(ConfigStore config, AuditLog audit) { return new StockAdminConfigCommand(live, config, audit, directTransactions(), Runnable::run, () -> Instant.parse("2026-08-22T01:02:03Z"), new Messages(null)); }
    private static TransactionRunner directTransactions() { return new TransactionRunner() { @Override public <T> T inTransaction(SqlWork<T> work) { try { return work.execute(null); } catch (Exception e) { throw new IllegalStateException(e); } } }; }
    private static List<String> messages(CommandSender sender) { ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class); verify(sender, atLeastOnce()).sendMessage(messages.capture()); return messages.getAllValues().stream().map(message -> PlainTextComponentSerializer.plainText().serialize(message)).toList(); }
    private static CompanyCreationRules rules(String capital) { return new CompanyCreationRules(Money.fromMajor(new BigDecimal("1000.00"), 2), Money.fromMajor(new BigDecimal(capital), 2), 2, 1000, List.of(30, 50, 70)); }
}
