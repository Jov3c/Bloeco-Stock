package cn.blockeco.exchange.paper;

import java.util.OptionalLong;

/** Stable external asset keys prevent a display name or location from becoming an authority key. */
final class ProviderAssetKeys {
    private ProviderAssetKeys() { }

    static String format(String type, long id) {
        if (id <= 0) throw new IllegalArgumentException("id must be positive");
        return type + ":" + id;
    }

    static OptionalLong parse(String type, String externalKey) {
        if (externalKey == null || !externalKey.startsWith(type + ":")) return OptionalLong.empty();
        try {
            long id = Long.parseLong(externalKey.substring(type.length() + 1));
            return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }
}
