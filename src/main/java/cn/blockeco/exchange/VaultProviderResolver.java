package cn.blockeco.exchange;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.util.UUID;

/** The startup gate for the Vault provider, invoked on Paper's main thread. */
final class VaultProviderResolver {
    private VaultProviderResolver() { }
    static boolean isAvailable(RegisteredServiceProvider<Economy> registration) {
        return registration != null && registration.getProvider() != null;
    }

    /** Returns an administrative diagnostic when the reserved non-player escrow account is unavailable. */
    static String escrowPreflightFailure(Economy provider, OfflinePlayer escrowIdentity, UUID escrowId) {
        try {
            if (provider.hasAccount(escrowIdentity) || provider.createPlayerAccount(escrowIdentity)) return null;
            return "reserved escrow identity " + escrowId + " has no Vault account and the provider refused to create it";
        } catch (RuntimeException failure) {
            return "reserved escrow identity " + escrowId + " could not be checked: " + failure.getMessage();
        }
    }
}
