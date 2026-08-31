package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BluechipQuantConfigTest {
    @Test
    void loadsBoundedQuantConfiguration() {
        YamlConfiguration yaml = validConfig();

        assertThat(BluechipQuantConfig.load(yaml)).isEqualTo(new BluechipQuantConfig(6_500, 200, 120));
    }

    @Test
    void rejectsUnsafeQuantThresholdsAndLimits() {
        YamlConfiguration threshold = validConfig(); threshold.set("market.quant.target-confidence-bps", 4_999);
        YamlConfiguration size = validConfig(); size.set("market.quant.maximum-order-bps", 501);
        YamlConfiguration cooldown = validConfig(); cooldown.set("market.quant.loss-cooldown-seconds", 0);

        assertThatThrownBy(() -> BluechipQuantConfig.load(threshold)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BluechipQuantConfig.load(size)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BluechipQuantConfig.load(cooldown)).isInstanceOf(IllegalArgumentException.class);
    }

    private static YamlConfiguration validConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("market.quant.target-confidence-bps", 6_500);
        yaml.set("market.quant.maximum-order-bps", 200);
        yaml.set("market.quant.loss-cooldown-seconds", 120);
        return yaml;
    }
}
