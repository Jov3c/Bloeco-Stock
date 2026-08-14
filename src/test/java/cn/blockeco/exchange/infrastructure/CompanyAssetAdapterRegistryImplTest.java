package cn.blockeco.exchange.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyAssetAdapterRegistryImplTest {
 @Test void registered_adapter_is_visible_to_a_production_snapshot_and_absent_after_unregistration(){CompanyAssetAdapterRegistryImpl registry=new CompanyAssetAdapterRegistryImpl(); CompanyAssetAdapter adapter=new CompanyAssetAdapter(){public String id(){return "test";}public Verification verify(UUID requester,String key){return new Verification(true,requester,"");}}; registry.register(adapter);assertThat(registry.snapshot()).containsExactly(adapter);registry.unregister("test");assertThat(registry.snapshot()).isEmpty();}
}
