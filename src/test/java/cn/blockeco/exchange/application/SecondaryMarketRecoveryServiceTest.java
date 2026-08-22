package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.finance.EscrowReconciliation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashDirection;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class SecondaryMarketRecoveryServiceTest {
 @Test void inspection_is_read_only_and_mismatch_blocks_mutations() {
  SecuritiesCashRepository cash=mock(SecuritiesCashRepository.class); when(cash.findRecoveryCandidates()).thenReturn(List.of()); when(cash.reconcile(Money.ofMinor(9))).thenReturn(new EscrowReconciliation(Money.ofMinor(9),Money.ofMinor(10),Money.zero(),Money.zero(),Money.zero(),Money.zero(),Money.zero()));
  var snapshot=new SecondaryMarketRecoveryService(cash,(Executor)Runnable::run).inspect(Money.ofMinor(9)).toCompletableFuture().join();
  assertThat(snapshot.mutationsBlocked()).isTrue(); verify(cash).reconcile(Money.ofMinor(9)); verify(cash).findRecoveryCandidates(); verifyNoMoreInteractions(cash);
 }

 @Test void durable_final_stage_is_reported_for_local_completion_but_does_not_permanently_block_mutations() {
  SecuritiesCashRepository cash=mock(SecuritiesCashRepository.class);
  SecuritiesCashOperation finalDeposit=operation(SecuritiesCashDirection.DEPOSIT,SecuritiesCashOperationState.ESCROW_DEPOSITED,SecuritiesCashOperationState.ESCROW_DEPOSITED);
  when(cash.findRecoveryCandidates()).thenReturn(List.of(finalDeposit));
  when(cash.reconcile(Money.ofMinor(10))).thenReturn(reconciled(10));

  var snapshot=new SecondaryMarketRecoveryService(cash,(Executor)Runnable::run).inspect(Money.ofMinor(10)).toCompletableFuture().join();

  assertThat(snapshot.finalStageOperations()).containsExactly(finalDeposit);
  assertThat(snapshot.unresolvedCashOperations()).isEmpty();
  assertThat(snapshot.mutationsBlocked()).isFalse();
 }

 @Test void legacy_ambiguity_blocks_even_when_physical_difference_is_zero_and_is_never_resolved_by_inspection() {
  SecuritiesCashRepository cash=mock(SecuritiesCashRepository.class);
  when(cash.findRecoveryCandidates()).thenReturn(List.of());
  when(cash.reconcile(Money.ofMinor(10))).thenReturn(reconciled(10));
  SecondaryMarketRecoveryService.LegacyRecoveryIssue legacy = new SecondaryMarketRecoveryService.LegacyRecoveryIssue(
          "IPO", UUID.randomUUID(), Money.ofMinor(4), "AMBIGUOUS", "PLAYER_WITHDRAWN", "需要人工核对");

  var snapshot=new SecondaryMarketRecoveryService(cash, () -> List.of(legacy), (Executor)Runnable::run)
          .inspect(Money.ofMinor(10)).toCompletableFuture().join();

  assertThat(snapshot.legacyIssues()).containsExactly(legacy);
  assertThat(snapshot.reconciliation().confirmedDifference()).isEqualTo(Money.zero());
  assertThat(snapshot.mutationsBlocked()).isTrue();
  verify(cash).reconcile(Money.ofMinor(10));
  verify(cash).findRecoveryCandidates();
  verifyNoMoreInteractions(cash);
 }

 private static EscrowReconciliation reconciled(long physical) {
  return new EscrowReconciliation(Money.ofMinor(physical),Money.ofMinor(physical),Money.zero(),Money.zero(),Money.zero(),Money.zero(),Money.zero());
 }

 private static SecuritiesCashOperation operation(SecuritiesCashDirection direction,SecuritiesCashOperationState state,SecuritiesCashOperationState stage) {
  return new SecuritiesCashOperation(UUID.randomUUID(),UUID.randomUUID(),Money.ofMinor(1),direction,state,stage,"test",Instant.EPOCH,Instant.EPOCH);
 }
}
