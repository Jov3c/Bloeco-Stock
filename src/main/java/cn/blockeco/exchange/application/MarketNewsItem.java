package cn.blockeco.exchange.application;

import java.time.Instant;
import java.util.Objects;

/** A clearly labelled fictional BlockStock market bulletin. */
public record MarketNewsItem(String headline, String body, Instant publishedAt) {
    public MarketNewsItem { Objects.requireNonNull(headline); Objects.requireNonNull(body); Objects.requireNonNull(publishedAt); }
}
