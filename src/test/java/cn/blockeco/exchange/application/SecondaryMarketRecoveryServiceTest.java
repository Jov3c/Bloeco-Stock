package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.finance.EscrowReconciliation;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class SecondaryMarketRecoveryServiceTest {
 @Test void inspection_is_read_only_and_mismatch_blocks_mutations() {
  SecuritiesCashRepository cash=mock(SecuritiesCashRepository.class); when(cash.findRecoveryCandidates()).thenReturn(List.of()); when(cash.reconcile(Money.ofMinor(9))).thenReturn(new EscrowReconciliation(Money.ofMinor(9),Money.ofMinor(10),Money.zero(),Money.zero(),Money.zero(),Money.zero(),Money.zero()));
  var snapshot=new SecondaryMarketRecoveryService(cash,(Executor)Runnable::run).inspect(Money.ofMinor(9)).toCompletableFuture().join();
  assertThat(snapshot.mutationsBlocked()).isTrue(); verify(cash).reconcile(Money.ofMinor(9)); verify(cash).findRecoveryCandidates(); verifyNoMoreInteractions(cash);
 }
}
