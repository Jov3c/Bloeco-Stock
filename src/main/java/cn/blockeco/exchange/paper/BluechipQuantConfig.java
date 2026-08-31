package cn.blockeco.exchange.paper;

import org.bukkit.configuration.file.FileConfiguration;

/** Operator-tunable but deliberately narrow limits for the system participant. */
public record BluechipQuantConfig(int targetConfidenceBps, int maximumOrderBps, int lossCooldownSeconds) {
    public BluechipQuantConfig {
        if (targetConfidenceBps < 5_000 || targetConfidenceBps > 9_000) throw new IllegalArgumentException("market.quant.target-confidence-bps must be between 5000 and 9000");
        if (maximumOrderBps < 1 || maximumOrderBps > 500) throw new IllegalArgumentException("market.quant.maximum-order-bps must be between 1 and 500");
        if (lossCooldownSeconds < 1 || lossCooldownSeconds > 3_600) throw new IllegalArgumentException("market.quant.loss-cooldown-seconds must be between 1 and 3600");
    }

    public static BluechipQuantConfig load(FileConfiguration configuration) {
        return new BluechipQuantConfig(configuration.getInt("market.quant.target-confidence-bps"),
                configuration.getInt("market.quant.maximum-order-bps"), configuration.getInt("market.quant.loss-cooldown-seconds"));
    }
}
