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
            DividendRate dividendRate,
            Instant createdAt) {
        this.id = id;
        this.displayName = displayName;
        this.normalizedName = normalizedName;
        this.founderId = founderId;
        this.treasury = treasury;
        this.totalShares = INITIAL_SHARE_COUNT;
        this.dividendRate = dividendRate;
        this.status = CompanyStatus.PENDING_ASSET_BINDING;
        this.createdAt = createdAt;
    }

    public static Company register(
            CompanyId id,
            String name,
            UUID founderId,
            Money capital,
            DividendRate dividendRate,
            Instant createdAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(founderId, "founderId");
        Objects.requireNonNull(capital, "capital");
        Objects.requireNonNull(dividendRate, "dividendRate");
        Objects.requireNonNull(createdAt, "createdAt");
        if (founderId.getMostSignificantBits() == 0 && founderId.getLeastSignificantBits() == 0) {
            throw new IllegalArgumentException("founderId must not be the zero UUID");
        }
        capital.requireNonNegative("capital");

        String displayName = normalizeWhitespace(name);
        int codePointCount = displayName.codePointCount(0, displayName.length());
        if (codePointCount < 2 || codePointCount > 24) {
            throw new IllegalArgumentException("name must contain between 2 and 24 Unicode code points");
        }
        return new Company(
                id,
                displayName,
                displayName.toLowerCase(Locale.ROOT),
                founderId,
                capital,
                dividendRate,
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
}
