package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.domain.bluechip.BluechipDefinition;
import cn.blockeco.exchange.domain.company.Company;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.FileConfiguration;

/** Loads the fixed fictional bluechip roster from the Paper configuration. */
public record BluechipConfig(List<BluechipDefinition> definitions) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]{1,11}");

    public BluechipConfig {
        definitions = List.copyOf(definitions);
    }

    public static BluechipConfig load(FileConfiguration config, int scale) {
        if (scale < 0) throw new IllegalArgumentException("currency scale must not be negative");
        List<Map<?, ?>> entries = config.getMapList("bluechips");
        if (entries.size() != 10) throw new IllegalArgumentException("bluechips must contain exactly ten entries");
        Set<String> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<BluechipDefinition> definitions = entries.stream()
                .map(entry -> definition(entry, scale, codes, names))
                .toList();
        return new BluechipConfig(definitions);
    }

    private static BluechipDefinition definition(Map<?, ?> entry, int scale, Set<String> codes, Set<String> names) {
        String code = text(entry, "code");
        String displayName = text(entry, "display-name");
        String industry = text(entry, "industry");
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("invalid bluechip code: " + code);
        if (!codes.add(code)) throw new IllegalArgumentException("duplicate bluechip code: " + code);
        if (!names.add(Company.normalizeName(displayName))) {
            throw new IllegalArgumentException("duplicate bluechip display name: " + displayName);
        }
        long lower = minor(entry, "lower-bound", scale);
        long reference = minor(entry, "reference-price", scale);
        long upper = minor(entry, "upper-bound", scale);
        if (lower <= 0 || reference <= 0 || upper <= 0 || lower >= reference || reference >= upper) {
            throw new IllegalArgumentException("bluechip price bounds must satisfy 0 < lower < reference < upper");
        }
        long totalShares = positiveLong(entry, "total-shares");
        long initialFundCash = positiveMinor(entry, "initial-fund-cash", scale);
        long initialFundShares = positiveLong(entry, "initial-fund-shares");
        if (initialFundShares > totalShares) throw new IllegalArgumentException("initial fund shares exceed total shares");
        return new BluechipDefinition(code, displayName, industry, reference, lower, upper, totalShares,
                initialFundCash, initialFundShares, basisPoints(entry, "spread-bps"),
                basisPoints(entry, "event-sensitivity-bps"), basisPoints(entry, "dividend-payout-bps"));
    }

    private static String text(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("bluechip " + key + " is required");
        return value.toString();
    }

    private static long minor(Map<?, ?> entry, String key, int scale) {
        try { return new BigDecimal(text(entry, key)).movePointRight(scale).longValueExact(); }
        catch (ArithmeticException | NumberFormatException exception) { throw new IllegalArgumentException("invalid bluechip " + key, exception); }
    }

    private static long positiveMinor(Map<?, ?> entry, String key, int scale) {
        long value = minor(entry, key, scale);
        if (value <= 0) throw new IllegalArgumentException("bluechip " + key + " must be positive");
        return value;
    }

    private static long positiveLong(Map<?, ?> entry, String key) {
        try {
            long value = Long.parseLong(text(entry, key));
            if (value <= 0) throw new IllegalArgumentException("bluechip " + key + " must be positive");
            return value;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid bluechip " + key, exception); }
    }

    private static int basisPoints(Map<?, ?> entry, String key) {
        try {
            int value = Integer.parseInt(text(entry, key));
            if (value < 0 || value > 10_000) throw new IllegalArgumentException("bluechip " + key + " must be between 0 and 10000");
            return value;
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid bluechip " + key, exception); }
    }
}
