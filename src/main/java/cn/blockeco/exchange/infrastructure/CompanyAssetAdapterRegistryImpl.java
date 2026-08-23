package cn.blockeco.exchange.infrastructure;

import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import cn.blockeco.exchange.ports.CompanyAssetAdapterRegistry;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class CompanyAssetAdapterRegistryImpl implements CompanyAssetAdapterRegistry {
    private final ConcurrentHashMap<String, CompanyAssetAdapter> adapters = new ConcurrentHashMap<>();
    @Override public void register(CompanyAssetAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        String id = Objects.requireNonNull(adapter.id(), "adapter.id").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("asset adapter id must be non-blank");
        if (!id.equals(adapter.id())) throw new IllegalArgumentException("asset adapter id must not have surrounding whitespace");
        if (adapters.putIfAbsent(id, adapter) != null) throw new IllegalArgumentException("duplicate asset adapter: " + id);
    }
    @Override public void unregister(String adapterId) { if (adapterId != null) adapters.remove(adapterId); }
    /** A stable order keeps GUI picker slots and user-facing diagnostics predictable. */
    @Override public Collection<CompanyAssetAdapter> snapshot() {
        return adapters.values().stream().sorted(Comparator.comparing(CompanyAssetAdapter::id)).toList();
    }
}
