package cn.blockeco.exchange.domain.bluechip;

/** Static market model inputs for one fictional bluechip company. */
public record BluechipDefinition(
        String code,
        String displayName,
        String industry,
        long referencePrice,
        long lowerBound,
        long upperBound,
        long totalShares,
        long initialFundCash,
        long initialFundShares,
        int spreadBps,
        int eventSensitivityBps,
        int dividendPayoutBps) {
}
