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
            if (escrowIdentity.isOnline() || escrowIdentity.hasPlayedBefore()) {
                return "保留托管账户 UUID " + escrowId + " 对应真实玩家，禁止使用玩家钱包作为托管账户";
            }
            if (provider.hasAccount(escrowIdentity) || provider.createPlayerAccount(escrowIdentity)) return null;
            return "保留托管账户 UUID " + escrowId + " 尚未创建，且经济提供方拒绝创建空账户";
        } catch (RuntimeException failure) {
            return "保留托管账户 UUID " + escrowId + " 检查失败" + detail(failure);
        }
    }
    private static String detail(RuntimeException failure) { return failure.getMessage() == null ? "" : "（附加信息：" + failure.getMessage() + "）"; }
}
