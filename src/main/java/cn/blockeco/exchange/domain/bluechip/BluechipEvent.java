package cn.blockeco.exchange.domain.bluechip;

import java.time.Instant;

/** A scheduled market event that may affect a company or an industry. */
public record BluechipEvent(
        String id,
        String scope,
        String companyId,
        String industry,
        String headline,
        String body,
        int priceImpactBps,
        int profitImpactBps,
        Instant startsAt,
        Instant endsAt,
        String state) {
}
