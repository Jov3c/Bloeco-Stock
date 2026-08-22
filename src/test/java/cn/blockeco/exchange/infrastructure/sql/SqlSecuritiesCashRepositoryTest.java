package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.finance.SecuritiesCashDirection;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState;
import cn.blockeco.exchange.domain.money.Money;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlSecuritiesCashRepositoryTest {
    private static final Instant NOW=Instant.parse("2026-08-22T00:00:00Z");

    @Test void database_partial_index_rejects_a_second_active_cash_operation() throws Exception {
        var file=Files.createTempFile("blockstock-cash-active-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); var repository=new SqlSecuritiesCashRepository(db.dataSource()); UUID player=UUID.randomUUID();
            db.inTransaction(c->{repository.prepareOperation(c,operation(player,SecuritiesCashDirection.DEPOSIT,100));return null;});
            assertThatThrownBy(()->db.inTransaction(c->{repository.prepareOperation(c,operation(player,SecuritiesCashDirection.WITHDRAW,100));return null;})).isInstanceOf(RuntimeException.class);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void transition_rejects_other_direction_external_stage() throws Exception {
        var file=Files.createTempFile("blockstock-cash-transition-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); var repository=new SqlSecuritiesCashRepository(db.dataSource()); SecuritiesCashOperation operation=operation(UUID.randomUUID(),SecuritiesCashDirection.DEPOSIT,100);
            db.inTransaction(c->{repository.prepareOperation(c,operation);return null;});
            assertThatThrownBy(()->db.inTransaction(c->{repository.transitionOperation(c,operation.id(),SecuritiesCashOperationState.PREPARED,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.ESCROW_WITHDRAWN,"wrong direction",NOW);return null;})).isInstanceOf(IllegalArgumentException.class);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void reconciliation_uses_the_single_connection_pool_without_nested_borrow_and_keeps_adjustments_separate() throws Exception {
        var file=Files.createTempFile("blockstock-cash-reconcile-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); var repository=new SqlSecuritiesCashRepository(db.dataSource()); UUID depositor=UUID.randomUUID(), withdrawer=UUID.randomUUID(); SecuritiesCashOperation deposit=operation(depositor,SecuritiesCashDirection.DEPOSIT,100), withdrawal=operation(withdrawer,SecuritiesCashDirection.WITHDRAW,70);
            db.inTransaction(c->{
                repository.prepareOperation(c,deposit);
                repository.transitionOperation(c,deposit.id(),SecuritiesCashOperationState.PREPARED,SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.PLAYER_WITHDRAWN,"wallet debited",NOW);
                repository.transitionOperation(c,deposit.id(),SecuritiesCashOperationState.PLAYER_WITHDRAWN,SecuritiesCashOperationState.ESCROW_DEPOSITED,SecuritiesCashOperationState.ESCROW_DEPOSITED,"escrow credited",NOW);
                repository.creditAvailable(c,withdrawer,Money.ofMinor(70),NOW);
                repository.reserve(c,withdrawer,Money.ofMinor(70));
                repository.prepareOperation(c,withdrawal);
                repository.transitionOperation(c,withdrawal.id(),SecuritiesCashOperationState.PREPARED,SecuritiesCashOperationState.ESCROW_WITHDRAWN,SecuritiesCashOperationState.ESCROW_WITHDRAWN,"escrow debited",NOW);
                return null;
            });
            var reconciliation=repository.reconcile(Money.ofMinor(100));
            assertThat(reconciliation.provenInboundNotYetLiability()).isEqualTo(Money.ofMinor(100));
            assertThat(reconciliation.provenOutboundStillLiability()).isEqualTo(Money.ofMinor(70));
            assertThat(reconciliation.securitiesCashLiability()).isEqualTo(Money.ofMinor(70));
            assertThat(reconciliation.confirmedDifference()).isEqualTo(Money.zero());
        } finally { Files.deleteIfExists(file); }
    }

    private static SecuritiesCashOperation operation(UUID player,SecuritiesCashDirection direction,long amount) {
        return new SecuritiesCashOperation(UUID.randomUUID(),player,Money.ofMinor(amount),direction,SecuritiesCashOperationState.PREPARED,null,"prepared",NOW,NOW);
    }
}
