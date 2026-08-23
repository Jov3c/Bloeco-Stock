package cn.blockeco.exchange.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import cn.blockeco.exchange.ports.AssetCatalogAdapter;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyAssetAdapterRegistryImplTest {
 @Test void registered_adapter_is_visible_to_a_production_snapshot_and_absent_after_unregistration(){CompanyAssetAdapterRegistryImpl registry=new CompanyAssetAdapterRegistryImpl(); CompanyAssetAdapter adapter=new CompanyAssetAdapter(){public String id(){return "test";}public Verification verify(UUID requester,String key){return new Verification(true,requester,"");}}; registry.register(adapter);assertThat(registry.snapshot()).containsExactly(adapter);registry.unregister("test");assertThat(registry.snapshot()).isEmpty();}
 @Test void snapshot_is_stably_sorted_by_adapter_id_for_gui_presentation(){CompanyAssetAdapterRegistryImpl registry=new CompanyAssetAdapterRegistryImpl();CompanyAssetAdapter quickshop=adapter("quickshop");CompanyAssetAdapter lands=adapter("lands");registry.register(quickshop);registry.register(lands);assertThat(registry.snapshot()).containsExactly(lands,quickshop);}
 @Test void catalog_snapshot_exposes_only_gui_pickable_adapters(){CompanyAssetAdapterRegistryImpl registry=new CompanyAssetAdapterRegistryImpl();registry.register(adapter("lands"));AssetCatalogAdapter nativeAssets=new AssetCatalogAdapter(){public String id(){return "blockstock-native";}public Verification verify(UUID requester,String key){return new Verification(true,requester,"");}public java.util.List<AssetChoice> listOwned(UUID requester,String search,int limit){return java.util.List.of();}};registry.register(nativeAssets);assertThat(registry.catalogSnapshot()).containsExactly(nativeAssets);}
 private static CompanyAssetAdapter adapter(String id){return new CompanyAssetAdapter(){public String id(){return id;}public Verification verify(UUID requester,String key){return new Verification(true,requester,"");}};}
}
