package cn.blockeco.exchange.domain.company;

public enum DividendRate {
    THIRTY(3000),
    FIFTY(5000),
    SEVENTY(7000);

    private final int basisPoints;

    DividendRate(int basisPoints) {
        this.basisPoints = basisPoints;
    }

    public int basisPoints() {
        return basisPoints;
    }

    public static DividendRate fromPercent(int percent) {
        return switch (percent) {
            case 30 -> THIRTY;
            case 50 -> FIFTY;
            case 70 -> SEVENTY;
            default -> throw new IllegalArgumentException("unsupported dividend percent: " + percent);
        };
    }
}
