package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

/** Small, user-facing IPO calculations; the offering domain remains the final authority. */
final class IpoOfferPreview {
    private IpoOfferPreview() { }

    static long estimatedShares(Money target, Money issuePrice) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(issuePrice, "issuePrice");
        if (target.minorUnits() <= 0 || issuePrice.minorUnits() <= 0) {
            throw new IllegalArgumentException("target and issue price must be positive");
        }
        return target.minorUnits() / issuePrice.minorUnits();
    }
}
