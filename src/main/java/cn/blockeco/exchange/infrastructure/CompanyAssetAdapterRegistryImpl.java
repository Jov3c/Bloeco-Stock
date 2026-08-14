package cn.blockeco.exchange.infrastructure;

import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import cn.blockeco.exchange.ports.CompanyAssetAdapterRegistry;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public final class CompanyAssetAdapterRegistryImpl implements CompanyAssetAdapterRegistry {
    private final ConcurrentHashMap<String, CompanyAssetAdapter> adapters = new ConcurrentHashMap<>();
    @Override public void register(CompanyAssetAdapter adapter) { if (adapters.putIfAbsent(adapter.id(), adapter) != null) throw new IllegalArgumentException("duplicate asset adapter: " + adapter.id()); }
    @Override public void unregister(String adapterId) { adapters.remove(adapterId); }
    @Override public Collection<CompanyAssetAdapter> snapshot() { return java.util.List.copyOf(adapters.values()); }
}
