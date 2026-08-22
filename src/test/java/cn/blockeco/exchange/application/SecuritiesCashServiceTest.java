package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.finance.*;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.*;
import java.sql.Connection;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class SecuritiesCashServiceTest {
    @Test void deposit_second_leg_not_called_after_player_debit_is_ambiguous() throws Exception {
        Fixture f=fixture(); when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok"))); when(f.gateway.depositEscrow(Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.notCalledFailure("offline")));
        SecuritiesCashResult result=f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.AMBIGUOUS); verify(f.repository,never()).creditAvailable(any(),any(),any(),any()); verify(f.repository,never()).completeDeposit(any(),any(),any());
    }
    @Test void external_timeout_is_ambiguous_and_does_not_call_next_leg() throws Exception {
        Fixture f=fixture(Duration.ofMillis(1)); when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenReturn(new CompletableFuture<>());
        SecuritiesCashResult result=f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.AMBIGUOUS); verify(f.gateway,never()).depositEscrow(any());
    }
    @Test void duplicate_active_cash_operation_is_rejected_before_gateway() {
        Fixture f=fixture(); Instant now=Instant.parse("2026-08-22T00:00:00Z"); when(f.repository.findActiveOperation(f.player)).thenReturn(Optional.of(new SecuritiesCashOperation(UUID.randomUUID(),f.player,Money.ofMinor(1),SecuritiesCashDirection.DEPOSIT,SecuritiesCashOperationState.PREPARED,null,"",now,now)));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class); verifyNoInteractions(f.gateway);
    }
    @Test void withdraw_pre_call_rejection_releases_reserve_and_never_calls_player_deposit() throws Exception {
        SecuritiesCashRepository repo=mock(SecuritiesCashRepository.class); SecuritiesCashGateway gateway=mock(SecuritiesCashGateway.class); TransactionRunner tx=mock(TransactionRunner.class); Connection connection=mock(Connection.class); when(connection.getAutoCommit()).thenReturn(false);
        doAnswer((Answer<Object>) invocation -> ((TransactionRunner.SqlWork<?>)invocation.getArgument(0)).execute(connection)).when(tx).inTransaction(any());
        UUID player=UUID.randomUUID(); Instant now=Instant.parse("2026-08-22T00:00:00Z"); when(repo.findActiveOperation(player)).thenReturn(Optional.empty());
        when(gateway.withdrawEscrow(Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.insufficientFunds("empty escrow")));
        SecuritiesCashResult result=new SecuritiesCashService(repo,tx,gateway,(Executor)Runnable::run,()->now).withdraw(player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.FAILED);
        var order=inOrder(repo,gateway); order.verify(repo).reserve(eq(connection),eq(player),eq(Money.ofMinor(100))); order.verify(repo).prepareOperation(eq(connection),any()); order.verify(gateway).withdrawEscrow(Money.ofMinor(100)); order.verify(repo).release(eq(connection),eq(player),eq(Money.ofMinor(100)));
        verify(gateway,never()).depositPlayer(any(),any());
    }
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
    private static Fixture fixture() throws Exception { return fixture(Duration.ofSeconds(1)); }
    private static Fixture fixture(Duration timeout) throws Exception { SecuritiesCashRepository repository=mock(SecuritiesCashRepository.class); SecuritiesCashGateway gateway=mock(SecuritiesCashGateway.class); TransactionRunner tx=mock(TransactionRunner.class); Connection connection=mock(Connection.class); when(connection.getAutoCommit()).thenReturn(false); doAnswer((Answer<Object>) invocation -> ((TransactionRunner.SqlWork<?>)invocation.getArgument(0)).execute(connection)).when(tx).inTransaction(any()); UUID player=UUID.randomUUID(); when(repository.findActiveOperation(player)).thenReturn(Optional.empty()); return new Fixture(repository,gateway,player,new SecuritiesCashService(repository,tx,gateway,(Executor)Runnable::run,()->Instant.parse("2026-08-22T00:00:00Z"),timeout)); }
    private record Fixture(SecuritiesCashRepository repository,SecuritiesCashGateway gateway,UUID player,SecuritiesCashService service) { }
}
