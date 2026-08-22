package cn.blockeco.exchange.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public announcement body; intentionally excludes subscription and escrow details. */
public record PublicAnnouncement(UUID id, String companyName, String body, Instant publishedAt) {
    public PublicAnnouncement { Objects.requireNonNull(id); Objects.requireNonNull(companyName); Objects.requireNonNull(body); Objects.requireNonNull(publishedAt); }
}
