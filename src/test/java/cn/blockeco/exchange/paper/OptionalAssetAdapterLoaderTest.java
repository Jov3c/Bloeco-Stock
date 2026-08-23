package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.blockeco.exchange.infrastructure.CompanyAssetAdapterRegistryImpl;
import java.util.UUID;
import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class OptionalAssetAdapterLoaderTest {
    @Test
    void absent_optional_plugins_are_reported_without_registering_or_failing() {
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.getPlugin("Lands")).thenReturn(null);
        when(plugins.getPlugin("Residence")).thenReturn(null);
        when(plugins.getPlugin("QuickShop-Hikari")).thenReturn(null);
        when(plugins.getPlugin("Shopkeepers")).thenReturn(null);
        CompanyAssetAdapterRegistryImpl registry = new CompanyAssetAdapterRegistryImpl();

        OptionalAssetAdapterLoader.LoadReport report = new OptionalAssetAdapterLoader(plugins, registry, ignored -> { }).load();

        assertThat(report.availableProviderPlugins()).isEmpty();
        assertThat(report.detectedWithoutBridge()).isEmpty();
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void detected_provider_without_blockstock_bridge_stays_non_bindable_and_is_visible_for_diagnostics() {
        PluginManager plugins = mock(PluginManager.class);
        Plugin lands = mock(Plugin.class);
        when(lands.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("Lands")).thenReturn(lands);
        when(plugins.getPlugin("Residence")).thenReturn(null);
        when(plugins.getPlugin("QuickShop-Hikari")).thenReturn(null);
        when(plugins.getPlugin("Shopkeepers")).thenReturn(null);
        CompanyAssetAdapterRegistryImpl registry = new CompanyAssetAdapterRegistryImpl();

        OptionalAssetAdapterLoader.LoadReport report = new OptionalAssetAdapterLoader(plugins, registry, ignored -> { }).load();

        assertThat(report.availableProviderPlugins()).containsExactly("Lands");
        assertThat(report.detectedWithoutBridge()).containsExactly("Lands");
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void provider_with_a_registered_compatibility_adapter_is_not_reported_as_missing_a_bridge() {
        PluginManager plugins = mock(PluginManager.class);
        Plugin shopkeepers = mock(Plugin.class);
        when(shopkeepers.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("Lands")).thenReturn(null);
        when(plugins.getPlugin("Residence")).thenReturn(null);
        when(plugins.getPlugin("QuickShop-Hikari")).thenReturn(null);
        when(plugins.getPlugin("Shopkeepers")).thenReturn(shopkeepers);
        CompanyAssetAdapterRegistryImpl registry = new CompanyAssetAdapterRegistryImpl();
        registry.register(new CompanyAssetAdapter() {
            @Override public String id() { return "shopkeepers"; }
            @Override public Verification verify(UUID requester, String externalKey) {
                return new Verification(false, null, "not used");
            }
        });

        OptionalAssetAdapterLoader.LoadReport report = new OptionalAssetAdapterLoader(plugins, registry, ignored -> { }).load();

        assertThat(report.availableProviderPlugins()).containsExactly("Shopkeepers");
        assertThat(report.detectedWithoutBridge()).isEmpty();
    }
}
