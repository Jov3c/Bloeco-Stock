package cn.blockeco.exchange.infrastructure.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class VaultSecuritiesCashGatewayTest {
    @Test void every_vault_leg_and_balance_is_marshalled_through_main_thread_executor() {
        EconomyGateway economy=mock(EconomyGateway.class); MainThreadExecutor main=mock(MainThreadExecutor.class); UUID escrow=UUID.randomUUID(), player=UUID.randomUUID();
        when(economy.withdraw(any(),any())).thenReturn(EconomyGateway.Result.success("ok")); when(economy.deposit(any(),any())).thenReturn(EconomyGateway.Result.success("ok")); when(economy.balance(escrow)).thenReturn(Money.ofMinor(77));
        doAnswer((Answer<Object>) invocation -> CompletableFuture.completedFuture(((Supplier<?>) invocation.getArgument(0)).get())).when(main).submit(any());
        VaultSecuritiesCashGateway gateway=new VaultSecuritiesCashGateway(economy,main,escrow);

        assertThat(gateway.withdrawPlayer(player,Money.ofMinor(1)).toCompletableFuture().join().outcome()).isEqualTo(EconomyGateway.Outcome.SUCCESS);
        gateway.depositEscrow(Money.ofMinor(2)).toCompletableFuture().join(); gateway.withdrawEscrow(Money.ofMinor(3)).toCompletableFuture().join(); gateway.depositPlayer(player,Money.ofMinor(4)).toCompletableFuture().join();
        assertThat(gateway.escrowBalance().toCompletableFuture().join()).isEqualTo(Money.ofMinor(77));
        verify(main,times(5)).submit(any());
        verify(economy).withdraw(player,Money.ofMinor(1)); verify(economy).deposit(escrow,Money.ofMinor(2)); verify(economy).withdraw(escrow,Money.ofMinor(3)); verify(economy).deposit(player,Money.ofMinor(4)); verify(economy).balance(escrow);
    }
    @Test void guarded_leg_checks_lifecycle_inside_the_main_thread_work_before_touching_economy() {
        EconomyGateway economy=mock(EconomyGateway.class); MainThreadExecutor main=mock(MainThreadExecutor.class); UUID escrow=UUID.randomUUID(), player=UUID.randomUUID();
        doAnswer((Answer<Object>) invocation -> CompletableFuture.completedFuture(((Supplier<?>) invocation.getArgument(0)).get())).when(main).submit(any());
        VaultSecuritiesCashGateway gateway=new VaultSecuritiesCashGateway(economy,main,escrow);
        AtomicBoolean accepting = new AtomicBoolean(false);

        EconomyGateway.Result result = gateway.withdrawPlayer(player, Money.ofMinor(1), accepting::get).toCompletableFuture().join();

        assertThat(result.providerWasCalled()).isFalse();
        verify(main).submit(any());
        verifyNoInteractions(economy);
    }
}
