package cn.blockeco.exchange.domain.company;

import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class Company {

    private static final long INITIAL_SHARE_COUNT = 1000L;

    private final CompanyId id;
    private final String displayName;
    private final String normalizedName;
    private final UUID founderId;
    private final Money treasury;
    private final long totalShares;
    private final DividendRate dividendRate;
    private final CompanyStatus status;
    private final Instant createdAt;

    private Company(
            CompanyId id,
            String displayName,
            String normalizedName,
            UUID founderId,
            Money treasury,
            long totalShares,
            DividendRate dividendRate,
            CompanyStatus status,
            Instant createdAt) {
        this.id = id;
        this.displayName = displayName;
        this.normalizedName = normalizedName;
        this.founderId = founderId;
        this.treasury = treasury;
        this.totalShares = totalShares;
        this.dividendRate = dividendRate;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Company register(
            CompanyId id,
            String name,
            UUID founderId,
            Money capital,
            DividendRate dividendRate,
            Instant createdAt) {
        String displayName = normalizeDisplayName(name);
        return rehydrate(
                id,
                displayName,
                displayName.toLowerCase(Locale.ROOT),
                founderId,
                capital,
                INITIAL_SHARE_COUNT,
                dividendRate,
                CompanyStatus.PENDING_ASSET_BINDING,
                createdAt);
    }

    public static Company rehydrate(
            CompanyId id,
            String displayName,
            String normalizedName,
            UUID founderId,
            Money treasury,
            long totalShares,
            DividendRate dividendRate,
            CompanyStatus status,
            Instant createdAt) {
        Objects.requireNonNull(id, "id");
        requireNonZero(id.value(), "id");
        Objects.requireNonNull(founderId, "founderId");
        requireNonZero(founderId, "founderId");
        Objects.requireNonNull(treasury, "treasury");
        treasury.requireNonNegative("treasury");
        if (totalShares < INITIAL_SHARE_COUNT) {
            throw new IllegalArgumentException("totalShares must be at least " + INITIAL_SHARE_COUNT);
        }
        Objects.requireNonNull(dividendRate, "dividendRate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");

        String canonicalDisplayName = normalizeDisplayName(displayName);
        if (!canonicalDisplayName.equals(displayName)) {
            throw new IllegalArgumentException("displayName must be normalized");
        }
        String expectedNormalizedName = displayName.toLowerCase(Locale.ROOT);
        if (!expectedNormalizedName.equals(normalizedName)) {
            throw new IllegalArgumentException("normalizedName must match displayName");
        }
        return new Company(
                id,
                displayName,
                normalizedName,
                founderId,
                treasury,
                totalShares,
                dividendRate,
                status,
                createdAt);
    }

    public CompanyId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String normalizedName() {
        return normalizedName;
    }

    public UUID founderId() {
        return founderId;
    }

    public Money treasury() {
        return treasury;
    }

    public long totalShares() {
        return totalShares;
    }

    public DividendRate dividendRate() {
        return dividendRate;
    }

    public CompanyStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Company withTotalShares(long totalShares) {
        if (totalShares < this.totalShares) {
            throw new IllegalArgumentException("totalShares must not decrease");
        }
        return rehydrate(id, displayName, normalizedName, founderId, treasury, totalShares, dividendRate, status, createdAt);
    }

    public static String normalizeName(String name) {
        return normalizeDisplayName(name).toLowerCase(Locale.ROOT);
    }

    private static String normalizeWhitespace(String name) {
        Objects.requireNonNull(name, "name");
        StringBuilder normalized = new StringBuilder();
        boolean pendingSpace = false;
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (normalized.length() > 0) {
                    pendingSpace = true;
                }
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return normalized.toString();
    }

    private static String normalizeDisplayName(String name) {
        String displayName = normalizeWhitespace(name);
        int codePointCount = displayName.codePointCount(0, displayName.length());
        if (codePointCount < 2 || codePointCount > 24) {
            throw new IllegalArgumentException("name must contain between 2 and 24 Unicode code points");
        }
        return displayName;
    }

    private static void requireNonZero(UUID value, String field) {
        if (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0) {
            throw new IllegalArgumentException(field + " must not be the zero UUID");
        }
    }
}
