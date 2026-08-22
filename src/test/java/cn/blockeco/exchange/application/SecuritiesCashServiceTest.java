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
    @Test void second_leg_provider_failure_after_confirmed_first_leg_is_ambiguous_without_credit() throws Exception {
        Fixture f=fixture(); when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok"))); when(f.gateway.depositEscrow(Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.providerFailure("provider timeout")));
        SecuritiesCashResult result=f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.AMBIGUOUS); verify(f.repository,never()).completeDeposit(any(),any(),any()); verify(f.gateway,times(1)).withdrawPlayer(f.player,Money.ofMinor(100)); verify(f.gateway,times(1)).depositEscrow(Money.ofMinor(100));
    }
    @Test void null_provider_result_after_invocation_is_ambiguous() throws Exception {
        Fixture f=fixture(); when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(null));
        SecuritiesCashResult result=f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.AMBIGUOUS); verify(f.gateway,never()).depositEscrow(any());
    }
    @Test void external_timeout_is_ambiguous_and_does_not_call_next_leg() throws Exception {
        Fixture f=fixture(Duration.ofMillis(1)); when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenReturn(new CompletableFuture<>());
        SecuritiesCashResult result=f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.AMBIGUOUS); verify(f.gateway,never()).depositEscrow(any());
    }
    @Test void duplicate_active_cash_operation_is_rejected_before_gateway() throws Exception {
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
    @Test void withdraw_reserves_then_executes_each_leg_once_and_only_final_stage_consumes_reserve() throws Exception {
        Fixture f=fixture(); Instant now=Instant.parse("2026-08-22T00:00:00Z");
        when(f.gateway.withdrawEscrow(Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok")));
        when(f.gateway.depositPlayer(f.player,Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok")));
        when(f.repository.findOperation(any())).thenAnswer(i->Optional.of(new SecuritiesCashOperation(i.getArgument(0),f.player,Money.ofMinor(100),SecuritiesCashDirection.WITHDRAW,SecuritiesCashOperationState.PLAYER_DEPOSITED,SecuritiesCashOperationState.PLAYER_DEPOSITED,"",now,now)));

        SecuritiesCashResult result=f.service.withdraw(f.player,Money.ofMinor(100)).toCompletableFuture().join();

        assertThat(result.completed()).isTrue();
        var order=inOrder(f.repository,f.gateway);
        order.verify(f.repository).reserve(any(),eq(f.player),eq(Money.ofMinor(100)));
        order.verify(f.repository).prepareOperation(any(),any());
        order.verify(f.gateway).withdrawEscrow(Money.ofMinor(100));
        order.verify(f.repository).transitionOperation(any(),any(),eq(SecuritiesCashOperationState.PREPARED),eq(SecuritiesCashOperationState.ESCROW_WITHDRAWN),eq(SecuritiesCashOperationState.ESCROW_WITHDRAWN),anyString(),any());
        order.verify(f.gateway).depositPlayer(f.player,Money.ofMinor(100));
        order.verify(f.repository).transitionOperation(any(),any(),eq(SecuritiesCashOperationState.ESCROW_WITHDRAWN),eq(SecuritiesCashOperationState.PLAYER_DEPOSITED),eq(SecuritiesCashOperationState.PLAYER_DEPOSITED),anyString(),any());
        order.verify(f.repository).completeWithdrawal(any(),any(),any());
        verify(f.repository,never()).release(any(),any(),any());
        verify(f.gateway,times(1)).withdrawEscrow(Money.ofMinor(100));
        verify(f.gateway,times(1)).depositPlayer(f.player,Money.ofMinor(100));
    }
    @Test void invoked_first_withdrawal_failure_is_ambiguous_and_keeps_reserve() throws Exception {
        Fixture f=fixture(); when(f.gateway.withdrawEscrow(Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.providerFailure("unknown")));
        SecuritiesCashResult result=f.service.withdraw(f.player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.AMBIGUOUS);
        verify(f.repository,never()).release(any(),any(),any()); verify(f.gateway,never()).depositPlayer(any(),any());
    }
    @Test void synchronous_gateway_throw_after_intent_is_ambiguous_and_never_calls_next_leg() throws Exception {
        Fixture f=fixture(); when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenThrow(new IllegalStateException("provider boundary"));
        SecuritiesCashResult result=f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join();
        assertThat(result.state()).isEqualTo(SecuritiesCashOperationState.AMBIGUOUS);
        verify(f.gateway,never()).depositEscrow(any());
    }
    @Test void startup_finishes_only_durable_final_stages_locally_without_gateway_calls() throws Exception {
        Fixture f=fixture(); Instant now=Instant.parse("2026-08-22T00:00:00Z");
        SecuritiesCashOperation deposit=new SecuritiesCashOperation(UUID.randomUUID(),f.player,Money.ofMinor(20),SecuritiesCashDirection.DEPOSIT,SecuritiesCashOperationState.ESCROW_DEPOSITED,SecuritiesCashOperationState.ESCROW_DEPOSITED,"durable",now,now);
        SecuritiesCashOperation withdrawal=new SecuritiesCashOperation(UUID.randomUUID(),UUID.randomUUID(),Money.ofMinor(30),SecuritiesCashDirection.WITHDRAW,SecuritiesCashOperationState.PLAYER_DEPOSITED,SecuritiesCashOperationState.PLAYER_DEPOSITED,"durable",now,now);
        SecuritiesCashOperation ambiguous=new SecuritiesCashOperation(UUID.randomUUID(),UUID.randomUUID(),Money.ofMinor(40),SecuritiesCashDirection.DEPOSIT,SecuritiesCashOperationState.AMBIGUOUS,SecuritiesCashOperationState.PLAYER_WITHDRAWN,"unknown",now,now);
        when(f.repository.findRecoveryCandidates()).thenReturn(java.util.List.of(deposit,withdrawal,ambiguous));
        when(f.repository.findOperation(any())).thenAnswer(i->Optional.of(java.util.List.of(deposit,withdrawal,ambiguous).stream().filter(o->o.id().equals(i.getArgument(0))).findFirst().orElseThrow()));

        var records=f.service.recoverDurableFinalStages().toCompletableFuture().join();

        assertThat(records).hasSize(3); verify(f.repository).completeDeposit(any(),eq(deposit),any()); verify(f.repository).completeWithdrawal(any(),eq(withdrawal),any()); verifyNoInteractions(f.gateway);
    }
    @Test void sql_failure_after_external_success_never_invokes_next_leg_and_attempts_ambiguity() throws Exception {
        Fixture f=fixture(); when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok")));
        doThrow(new IllegalStateException("disk full")).doNothing().when(f.repository).transitionOperation(any(),any(),eq(SecuritiesCashOperationState.PREPARED),any(),any(),anyString(),any());

        org.assertj.core.api.Assertions.assertThatThrownBy(()->f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);

        verify(f.gateway,never()).depositEscrow(any());
        verify(f.repository).transitionOperation(any(),any(),eq(SecuritiesCashOperationState.PREPARED),eq(SecuritiesCashOperationState.AMBIGUOUS),isNull(),contains("durable stage persistence failed"),any());
    }
    @Test void local_completion_failure_after_final_durable_stage_is_recovered_without_another_gateway_leg() throws Exception {
        Fixture f=fixture(); Instant now=Instant.parse("2026-08-22T00:00:00Z");
        when(f.gateway.withdrawPlayer(f.player,Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok")));
        when(f.gateway.depositEscrow(Money.ofMinor(100))).thenReturn(CompletableFuture.completedFuture(EconomyGateway.Result.success("ok")));
        SecuritiesCashOperation durable=new SecuritiesCashOperation(UUID.randomUUID(),f.player,Money.ofMinor(100),SecuritiesCashDirection.DEPOSIT,SecuritiesCashOperationState.ESCROW_DEPOSITED,SecuritiesCashOperationState.ESCROW_DEPOSITED,"durable",now,now);
        when(f.repository.findOperation(any())).thenReturn(Optional.of(durable)); doThrow(new IllegalStateException("local credit interrupted")).doNothing().when(f.repository).completeDeposit(any(),any(),any());

        org.assertj.core.api.Assertions.assertThatThrownBy(()->f.service.deposit(f.player,Money.ofMinor(100)).toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
        when(f.repository.findRecoveryCandidates()).thenReturn(java.util.List.of(durable));
        f.service.recoverDurableFinalStages().toCompletableFuture().join();

        verify(f.gateway,times(1)).withdrawPlayer(f.player,Money.ofMinor(100)); verify(f.gateway,times(1)).depositEscrow(Money.ofMinor(100)); verify(f.repository,times(2)).completeDeposit(any(),any(),any());
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
