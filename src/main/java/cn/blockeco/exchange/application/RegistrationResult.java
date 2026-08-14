package cn.blockeco.exchange.application;
public record RegistrationResult(Status status, String message) {
    public enum Status { SUCCESS, DUPLICATE_NAME, INSUFFICIENT_FUNDS, PROVIDER_FAILURE, REFUNDED_AFTER_FAILURE, RECOVERY_REQUIRED }
    public static RegistrationResult of(Status status, String message) { return new RegistrationResult(status, message == null ? "" : message); }
}
