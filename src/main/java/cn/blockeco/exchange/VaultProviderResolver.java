package cn.blockeco.exchange;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;

/** The startup gate for the Vault provider, invoked on Paper's main thread. */
final class VaultProviderResolver {
    private VaultProviderResolver() { }
    static boolean isAvailable(RegisteredServiceProvider<Economy> registration) {
        return registration != null && registration.getProvider() != null;
    }
}
