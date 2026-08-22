package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.finance.*;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class SecuritiesCashServiceTest {
    @Test void deposit_persists_intent_then_executes_exactly_two_external_legs() throws Exception {
        SecuritiesCashRepository repo=mock(SecuritiesCashRepository.class); SecuritiesCashGateway gateway=mock(SecuritiesCashGateway.class);
        TransactionRunner tx=mock(TransactionRunner.class); Connection connection=mock(Connection.class); when(connection.getAutoCommit()).thenReturn(false);
        doAnswer((Answer<Object>) invocation -> ((TransactionRunner.SqlWork<?>)invocation.getArgument(0)).execute(connection)).when(tx).inTransaction(any());
        UUID player=UUID.randomUUID(); UUID operation=UUID.randomUUID(); Instant now=Instant.parse("2026-08-22T00:00:00Z");
        when(repo.findActiveOperation(player)).thenReturn(Optional.empty());
        when(repo.findOperation(any())).thenAnswer(invocation -> Optional.of(new SecuritiesCashOperation((UUID)invocation.getArgument(0),player,Money.ofMinor(100),SecuritiesCashDirection.DEPOSIT,SecuritiesCashOperationState.ESCROW_DEPOSITED,SecuritiesCashOperationState.ESCROW_DEPOSITED,"",now,now)));
        when(gateway.withdrawPlayer(player,Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok")));
        when(gateway.depositEscrow(Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok")));
        SecuritiesCashService service=new SecuritiesCashService(repo,tx,gateway,(Executor)Runnable::run,()->now);

        SecuritiesCashResult result=service.deposit(player,Money.ofMinor(100)).toCompletableFuture().join();

        assertThat(result.completed()).isTrue();
        var order=inOrder(repo,gateway);
        order.verify(repo).prepareOperation(eq(connection),any()); order.verify(gateway).withdrawPlayer(player,Money.ofMinor(100));
        order.verify(repo).transitionOperation(eq(connection),any(),eq(SecuritiesCashOperationState.PREPARED),eq(SecuritiesCashOperationState.PLAYER_WITHDRAWN),eq(SecuritiesCashOperationState.PLAYER_WITHDRAWN),anyString(),any());
        order.verify(gateway).depositEscrow(Money.ofMinor(100)); verifyNoMoreInteractions(gateway);
    }
}
