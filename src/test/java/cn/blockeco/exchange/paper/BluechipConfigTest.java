package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BluechipConfigTest {
    @Test
    void loadsExactlyTenFictionalBluechipsInMinorCurrencyUnits() {
        BluechipConfig loaded = BluechipConfig.load(configWithEntries(10), 2);

        assertThat(loaded.definitions()).hasSize(10);
        assertThat(loaded.definitions().getFirst())
                .extracting(definition -> definition.code(), definition -> definition.referencePrice(),
                        definition -> definition.lowerBound(), definition -> definition.upperBound())
                .containsExactly("NOVA", 1_000L, 800L, 1_200L);
    }

    @Test
    void rejectsNonFictionalOrNonTenBluechipConfiguration() {
        assertThatThrownBy(() -> BluechipConfig.load(configWithEntries(9), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCodesThatDoNotMatchTheMarketSymbolFormat() {
        assertThatThrownBy(() -> BluechipConfig.load(configWithEntries(10, "bad-code"), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private YamlConfiguration configWithEntries(int count) {
        return configWithEntries(count, "NOVA");
    }

    private YamlConfiguration configWithEntries(int count, String firstCode) {
        YamlConfiguration configuration = new YamlConfiguration();
        List<String> codes = List.of("NOVA", "AURORA", "TERRAN", "SKYLINE", "IRONWOOD", "LUMEN", "RIVERMINT", "ORBITAL", "CINDER", "VERDANT");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", index == 0 ? firstCode : codes.get(index));
            entry.put("display-name", "Fictional " + index);
            entry.put("industry", "Industry " + index);
            entry.put("reference-price", "10.00");
            entry.put("lower-bound", "8.00");
            entry.put("upper-bound", "12.00");
            entry.put("total-shares", 1_000_000L);
            entry.put("initial-fund-cash", "100000.00");
            entry.put("initial-fund-shares", 100_000L);
            entry.put("spread-bps", 50);
            entry.put("event-sensitivity-bps", 100);
            entry.put("dividend-payout-bps", 2_000);
            entries.add(entry);
        }
        configuration.set("bluechips", entries);
        return configuration;
    }
}
