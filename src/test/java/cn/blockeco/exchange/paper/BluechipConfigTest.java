package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
    void rejectsConfigurationsThatDoNotContainExactlyTenBluechips() {
        assertThatThrownBy(() -> BluechipConfig.load(configWithEntries(9), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCodesThatDoNotMatchTheMarketSymbolFormat() {
        assertThatThrownBy(() -> BluechipConfig.load(configWithEntries(10, entries -> entries.getFirst().put("code", "bad-code")), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPlayerSequenceCodesSoBluechipsCannotConsumeTheBSNamespace() {
        assertThatThrownBy(() -> BluechipConfig.load(configWithEntries(10, entries -> entries.getFirst().put("code", "BS000001")), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for player listings");
    }

    @Test
    void rejectsDisplayNamesThatCollideAfterCompanyNormalization() {
        assertThatThrownBy(() -> BluechipConfig.load(configWithEntries(10,
                entries -> entries.get(1).put("display-name", "  fictional   0  ")), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBasisPointValuesOutsideTheSupportedRange() {
        for (String key : List.of("spread-bps", "event-sensitivity-bps", "dividend-payout-bps")) {
            assertThatThrownBy(() -> BluechipConfig.load(configWithEntries(10,
                    entries -> entries.getFirst().put(key, 10_001)), 2))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private YamlConfiguration configWithEntries(int count) {
        return configWithEntries(count, entries -> { });
    }

    private YamlConfiguration configWithEntries(int count, Consumer<List<Map<String, Object>>> customizer) {
        YamlConfiguration configuration = new YamlConfiguration();
        List<String> codes = List.of("NOVA", "AURORA", "TERRAN", "SKYLINE", "IRONWOOD", "LUMEN", "RIVERMINT", "ORBITAL", "CINDER", "VERDANT");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", codes.get(index));
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
        customizer.accept(entries);
        configuration.set("bluechips", entries);
        return configuration;
    }
}
