package cn.blockeco.exchange.application;

/** User-facing, non-sensitive outcome of a primary-offering subscription. */
public record SubscriptionResult(Status status) {
    public enum Status { SUCCESS, INSUFFICIENT_FUNDS, NOT_OPEN, SOLD_OUT, INVALID, RECOVERY_REQUIRED, PROVIDER_FAILURE }
    public static SubscriptionResult of(Status status) { return new SubscriptionResult(status); }
}
