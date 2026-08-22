package cn.blockeco.exchange.infrastructure.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.EconomyGateway;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class VaultEconomyGatewayTest {
    @Test void maps_successful_withdrawal_and_deposit() {
        Fixture fixture = Fixture.withProvider();
        when(fixture.economy.has(fixture.player, 11.0)).thenReturn(true);
        when(fixture.economy.withdrawPlayer(fixture.player, 11.0)).thenReturn(response(EconomyResponse.ResponseType.SUCCESS, ""));
        when(fixture.economy.depositPlayer(fixture.player, 11.0)).thenReturn(response(EconomyResponse.ResponseType.SUCCESS, ""));
        assertThat(fixture.gateway.withdraw(fixture.id, Money.ofMinor(1100)).outcome()).isEqualTo(EconomyGateway.Outcome.SUCCESS);
        assertThat(fixture.gateway.deposit(fixture.id, Money.ofMinor(1100)).outcome()).isEqualTo(EconomyGateway.Outcome.SUCCESS);
    }
    @Test void maps_non_success_response_to_insufficient_funds_for_withdrawal() {
        Fixture fixture = Fixture.withProvider();
        when(fixture.economy.has(fixture.player, 11.0)).thenReturn(false);
        when(fixture.economy.withdrawPlayer(fixture.player, 11.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE, "not enough"));
        assertThat(fixture.gateway.withdraw(fixture.id, Money.ofMinor(1100)).outcome()).isEqualTo(EconomyGateway.Outcome.INSUFFICIENT_FUNDS);
        assertThat(fixture.gateway.withdraw(fixture.id, Money.ofMinor(1100)).providerWasCalled()).isFalse();
        verify(fixture.economy, never()).withdrawPlayer(fixture.player, 11.0);
    }
    @Test void maps_failed_withdrawal_despite_sufficient_balance_and_not_implemented_to_provider_failure() {
        Fixture fixture = Fixture.withProvider();
        when(fixture.economy.has(fixture.player, 11.0)).thenReturn(true);
        when(fixture.economy.withdrawPlayer(fixture.player, 11.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE, "backend down"));
        assertThat(fixture.gateway.withdraw(fixture.id, Money.ofMinor(1100)).outcome()).isEqualTo(EconomyGateway.Outcome.PROVIDER_FAILURE);
        when(fixture.economy.withdrawPlayer(fixture.player, 11.0)).thenReturn(response(EconomyResponse.ResponseType.NOT_IMPLEMENTED, "unsupported"));
        assertThat(fixture.gateway.withdraw(fixture.id, Money.ofMinor(1100)).outcome()).isEqualTo(EconomyGateway.Outcome.PROVIDER_FAILURE);
    }
    @Test void maps_missing_provider_and_non_finite_value_to_provider_failure() {
        Fixture missing = Fixture.withoutProvider();
        assertThat(missing.gateway.withdraw(missing.id, Money.ofMinor(1)).outcome()).isEqualTo(EconomyGateway.Outcome.PROVIDER_FAILURE);
        assertThat(missing.gateway.withdraw(missing.id, Money.ofMinor(1)).providerWasCalled()).isFalse();
        Fixture provider = Fixture.withProvider();
        assertThat(provider.gateway.deposit(provider.id, Money.ofMinor(Long.MAX_VALUE)).outcome()).isEqualTo(EconomyGateway.Outcome.PROVIDER_FAILURE);
        assertThat(provider.gateway.deposit(provider.id, Money.ofMinor(Long.MAX_VALUE)).providerWasCalled()).isFalse();
    }
    @Test void maps_null_response_and_provider_exception_to_provider_failure() {
        Fixture fixture = Fixture.withProvider();
        when(fixture.economy.has(fixture.player, 11.0)).thenReturn(true);
        when(fixture.economy.withdrawPlayer(fixture.player, 11.0)).thenReturn(null);
        assertThat(fixture.gateway.withdraw(fixture.id, Money.ofMinor(1100)).outcome()).isEqualTo(EconomyGateway.Outcome.PROVIDER_FAILURE);
        when(fixture.economy.withdrawPlayer(fixture.player, 11.0)).thenThrow(new IllegalStateException("offline"));
        assertThat(fixture.gateway.withdraw(fixture.id, Money.ofMinor(1100)).outcome()).isEqualTo(EconomyGateway.Outcome.PROVIDER_FAILURE);
    }
    private static EconomyResponse response(EconomyResponse.ResponseType type, String message) { return new EconomyResponse(11.0, 0, type, message); }
    private static final class Fixture {
        final UUID id=UUID.randomUUID(); final Economy economy=mock(Economy.class); final OfflinePlayer player=mock(OfflinePlayer.class); final VaultEconomyGateway gateway;
        private Fixture(boolean provider) { Server server=mock(Server.class); ServicesManager services=mock(ServicesManager.class); when(server.getServicesManager()).thenReturn(services); when(server.getOfflinePlayer(id)).thenReturn(player); if(provider) { RegisteredServiceProvider<Economy> registration=mock(RegisteredServiceProvider.class); when(services.getRegistration(Economy.class)).thenReturn(registration); when(registration.getProvider()).thenReturn(economy); } gateway=new VaultEconomyGateway(server, 2); }
        static Fixture withProvider() { return new Fixture(true); } static Fixture withoutProvider() { return new Fixture(false); }
    }
}
