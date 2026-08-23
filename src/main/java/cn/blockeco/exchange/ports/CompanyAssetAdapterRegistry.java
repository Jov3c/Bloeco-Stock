package cn.blockeco.exchange.ports;

import java.util.Collection;
import java.util.List;

/**
 * Bukkit integrations register provider-neutral ownership verifiers here.
 *
 * <p>This service belongs to BlockStock. A compatibility plugin may obtain it from Bukkit's
 * service manager after BlockStock enables, register only while its own provider is enabled,
 * and unregister its adapter on disable. Registrations are intentionally synchronous and
 * in-memory: the adapter, not BlockStock, owns all third-party API calls and their threading
 * requirements.</p>
 */
public interface CompanyAssetAdapterRegistry {
    void register(CompanyAssetAdapter adapter);
    void unregister(String adapterId);
    Collection<CompanyAssetAdapter> snapshot();

    /** Returns only adapters that can safely provide a GUI asset picker. */
    default Collection<AssetCatalogAdapter> catalogSnapshot() {
        return snapshot().stream()
                .filter(AssetCatalogAdapter.class::isInstance)
                .map(AssetCatalogAdapter.class::cast)
                .toList();
    }
}
