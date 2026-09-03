package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderAssetKeysTest {
    @Test
    void acceptsOnlyTheExpectedStableProviderKey() {
        assertThat(ProviderAssetKeys.parse("claim", "claim:42")).hasValue(42L);
        assertThat(ProviderAssetKeys.parse("shop", "shop:7")).hasValue(7L);
        assertThat(ProviderAssetKeys.parse("claim", "shop:42")).isEmpty();
        assertThat(ProviderAssetKeys.parse("claim", "claim:0")).isEmpty();
    }
}
