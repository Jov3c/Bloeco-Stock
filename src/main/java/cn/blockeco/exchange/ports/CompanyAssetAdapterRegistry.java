package cn.blockeco.exchange.ports;

import java.util.Collection;

/** Bukkit integrations register provider-neutral ownership verifiers here. */
public interface CompanyAssetAdapterRegistry {
    void register(CompanyAssetAdapter adapter);
    void unregister(String adapterId);
    Collection<CompanyAssetAdapter> snapshot();
}
