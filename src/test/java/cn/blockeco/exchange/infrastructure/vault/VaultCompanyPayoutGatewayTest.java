package cn.blockeco.exchange.infrastructure.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.CompanyPayoutGateway;
import cn.blockeco.exchange.ports.EconomyGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VaultCompanyPayoutGatewayTest {
    @Test void maps_proven_provider_success_and_failure_without_exposing_provider_detail() {
        EconomyGateway economy = mock(EconomyGateway.class);
        when(economy.deposit(any(), any())).thenReturn(EconomyGateway.Result.success("private provider detail"));
        var gateway = new VaultCompanyPayoutGateway(economy, directMain());
        assertThat(gateway.depositFounder(UUID.randomUUID(), Money.ofMinor(100), UUID.randomUUID()).outcome()).isEqualTo(CompanyPayoutGateway.Outcome.SUCCESS);
        when(economy.deposit(any(), any())).thenReturn(EconomyGateway.Result.notCalledFailure("private failure"));
        assertThat(gateway.depositFounder(UUID.randomUUID(), Money.ofMinor(100), UUID.randomUUID()).outcome()).isEqualTo(CompanyPayoutGateway.Outcome.KNOWN_FAILURE);
    }
    @Test void maps_runtime_or_unproven_provider_result_to_unknown() {
        EconomyGateway economy = mock(EconomyGateway.class); when(economy.deposit(any(), any())).thenThrow(new IllegalStateException("timeout"));
        var result = new VaultCompanyPayoutGateway(economy, directMain()).depositFounder(UUID.randomUUID(), Money.ofMinor(100), UUID.randomUUID());
        assertThat(result.outcome()).isEqualTo(CompanyPayoutGateway.Outcome.UNKNOWN);
    }
    private static cn.blockeco.exchange.ports.MainThreadExecutor directMain() { return new cn.blockeco.exchange.ports.MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } }; }
}
