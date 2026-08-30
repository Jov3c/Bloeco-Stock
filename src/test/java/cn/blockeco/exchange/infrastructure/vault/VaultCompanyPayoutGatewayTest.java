package cn.blockeco.exchange.infrastructure.vault;

import static org.assertj.core.api.Assertions.assertThat;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.CompanyPayoutGateway;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.TreasuryEscrowGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VaultCompanyPayoutGatewayTest {
    @Test void withdraws_controlled_escrow_before_crediting_founder() {
        RecordingEscrow escrow = new RecordingEscrow();
        var gateway = new VaultCompanyPayoutGateway(escrow);
        assertThat(gateway.depositFounder(UUID.randomUUID(), Money.ofMinor(100), UUID.randomUUID()).outcome()).isEqualTo(CompanyPayoutGateway.Outcome.SUCCESS);
        assertThat(escrow.calls).containsExactly("withdraw", "deposit");
    }
    @Test void maps_called_or_second_step_failure_to_unknown_without_second_attempt() {
        RecordingEscrow escrow = new RecordingEscrow(); escrow.deposit = EconomyGateway.Result.providerFailure("private failure");
        var result = new VaultCompanyPayoutGateway(escrow).depositFounder(UUID.randomUUID(), Money.ofMinor(100), UUID.randomUUID());
        assertThat(result.outcome()).isEqualTo(CompanyPayoutGateway.Outcome.UNKNOWN);
    }
    private static final class RecordingEscrow implements TreasuryEscrowGateway { final java.util.List<String> calls=new java.util.ArrayList<>(); EconomyGateway.Result deposit=EconomyGateway.Result.success(""); public EconomyGateway.Result withdrawPlayer(UUID p,Money a,UUID id){return null;} public EconomyGateway.Result depositEscrow(Money a,UUID id){return null;} public EconomyGateway.Result withdrawEscrow(Money a,UUID id){calls.add("withdraw");return EconomyGateway.Result.success("");} public EconomyGateway.Result refundPlayer(UUID p,Money a,UUID id){calls.add("deposit");return deposit;} }
}
