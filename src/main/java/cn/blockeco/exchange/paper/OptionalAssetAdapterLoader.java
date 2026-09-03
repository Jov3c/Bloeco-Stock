package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import cn.blockeco.exchange.ports.CompanyAssetAdapterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Detects supported asset providers and registers safe, reflection-isolated bridges where available.
 *
 * <p>The third-party APIs are deliberately not reflected into: an API mismatch must never make
 * an ownership check succeed accidentally. A provider-specific BlockStock compatibility bridge
 * registers its verified {@link CompanyAssetAdapter} with the registry. This loader merely
 * exposes a deterministic diagnostic when the provider is installed but its safe bridge is not.
 * Missing, disabled, or incompatible optional plugins therefore never prevent startup.</p>
 */
public final class OptionalAssetAdapterLoader {
    private static final List<Provider> PROVIDERS = List.of(
            new Provider("GriefPrevention", "griefprevention", List.of("GriefPrevention")),
            new Provider("Lands", "lands", List.of("Lands", "LandsFree")),
            new Provider("Residence", "residence", List.of("Residence")),
            new Provider("QuickShop", "quickshop", List.of("QuickShop-Hikari", "QuickShop")),
            new Provider("Shopkeepers", "shopkeepers", List.of("Shopkeepers")));

    private final PluginManager plugins;
    private final CompanyAssetAdapterRegistry adapters;
    private final Consumer<String> diagnostics;

    public OptionalAssetAdapterLoader(PluginManager plugins, CompanyAssetAdapterRegistry adapters,
                                      Consumer<String> diagnostics) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Safe to call at startup and after an optional compatibility plugin has enabled. */
    public LoadReport load() {
        List<String> available = new ArrayList<>();
        List<String> missingBridge = new ArrayList<>();
        for (Provider provider : PROVIDERS) {
            Plugin plugin = availablePlugin(provider);
            if (plugin == null) {
                continue;
            }
            available.add(provider.displayName());
            registerBuiltInBridge(provider, plugin);
        }
        Set<String> registeredIds = adapterIds(adapters.snapshot());
        for (Provider provider : PROVIDERS) {
            if (!available.contains(provider.displayName())) continue;
            if (!registeredIds.contains(provider.adapterId())) {
                missingBridge.add(provider.displayName());
                diagnostics.accept("Bloeco-Stock 检测到可选插件 " + provider.displayName()
                        + "，但未找到已验证的资产适配桥；该插件不会阻止服务器启动，也不会出现在可绑定资产中。");
            }
        }
        return new LoadReport(List.copyOf(available), List.copyOf(missingBridge));
    }

    private Plugin availablePlugin(Provider provider) {
        for (String pluginName : provider.pluginNames()) {
            Plugin plugin = plugins.getPlugin(pluginName);
            if (plugin != null && plugin.isEnabled()) {
                return plugin;
            }
        }
        return null;
    }
    private void registerBuiltInBridge(Provider provider, Plugin plugin) {
        if (adapterIds(adapters.snapshot()).contains(provider.adapterId())) return;
        try {
            if (provider.adapterId().equals("griefprevention")) adapters.register(new GriefPreventionAssetAdapter(plugin));
            if (provider.adapterId().equals("quickshop")) adapters.register(new QuickShopAssetAdapter(plugin));
        } catch (RuntimeException failure) {
            diagnostics.accept("Bloeco-Stock 无法加载 " + provider.displayName() + " 资产适配桥：" + failure.getMessage());
        }
    }

    private static Set<String> adapterIds(Collection<CompanyAssetAdapter> adapters) {
        Set<String> ids = new LinkedHashSet<>();
        adapters.forEach(adapter -> ids.add(adapter.id()));
        return ids;
    }

    public record LoadReport(List<String> availableProviderPlugins, List<String> detectedWithoutBridge) {
        public LoadReport {
            availableProviderPlugins = List.copyOf(availableProviderPlugins);
            detectedWithoutBridge = List.copyOf(detectedWithoutBridge);
        }
    }

    private record Provider(String displayName, String adapterId, List<String> pluginNames) { }
}
