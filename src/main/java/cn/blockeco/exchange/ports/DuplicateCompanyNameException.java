package cn.blockeco.exchange.ports;

public final class DuplicateCompanyNameException extends RuntimeException {

    public DuplicateCompanyNameException(String normalizedName, Throwable cause) {
        super("company name already exists: " + normalizedName, cause);
    }
}
