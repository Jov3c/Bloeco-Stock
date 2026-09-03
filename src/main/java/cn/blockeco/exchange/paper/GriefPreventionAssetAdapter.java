package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.ports.AssetCatalogAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/** Verified adapter for GriefPrevention's public claim API, loaded without a compile-time dependency. */
final class GriefPreventionAssetAdapter implements AssetCatalogAdapter {
    private final Plugin provider;

    GriefPreventionAssetAdapter(Plugin provider) { this.provider = Objects.requireNonNull(provider, "provider"); }
    @Override public String id() { return "griefprevention"; }

    @Override public List<AssetChoice> listOwned(UUID requester, String search, int limit) {
        if (limit <= 0) return List.of();
        try {
            Object dataStore = field(provider, "dataStore");
            Object rawClaims = call(dataStore, "getClaims");
            if (!(rawClaims instanceof Collection<?> claims)) return List.of();
            String needle = search == null ? "" : search.trim().toLowerCase(java.util.Locale.ROOT);
            List<AssetChoice> choices = new ArrayList<>();
            for (Object claim : claims) {
                UUID owner = (UUID) call(claim, "getOwnerID");
                Long claimId = (Long) call(claim, "getID");
                if (!requester.equals(owner) || claimId == null || claimId <= 0) continue;
                String display = describe(claim, claimId);
                if (!needle.isEmpty() && !display.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
                choices.add(new AssetChoice(ProviderAssetKeys.format("claim", claimId), display, "GriefPrevention 领地"));
                if (choices.size() >= limit) break;
            }
            return List.copyOf(choices);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            return List.of();
        }
    }

    @Override public Verification verify(UUID requester, String externalKey) {
        var claimId = ProviderAssetKeys.parse("claim", externalKey);
        if (claimId.isEmpty()) return new Verification(false, null, "领地标识无效");
        try {
            Object claim = call(field(provider, "dataStore"), "getClaim", claimId.getAsLong());
            if (claim == null) return new Verification(false, null, "领地不存在或已删除");
            UUID owner = (UUID) call(claim, "getOwnerID");
            return new Verification(requester.equals(owner), owner, requester.equals(owner) ? "已验证领地主人" : "你不是该领地所有者");
        } catch (ReflectiveOperationException | ClassCastException failure) {
            return new Verification(false, null, "无法读取 GriefPrevention 领地信息");
        }
    }

    private static String describe(Object claim, long id) throws ReflectiveOperationException {
        Location lesser = (Location) call(claim, "getLesserBoundaryCorner");
        Location greater = (Location) call(claim, "getGreaterBoundaryCorner");
        return "领地 #" + id + " · " + lesser.getWorld().getName() + " (" + lesser.getBlockX() + ", " + lesser.getBlockZ() + ") → (" + greater.getBlockX() + ", " + greater.getBlockZ() + ")";
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException { Field field = target.getClass().getField(name); return field.get(target); }
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, java.util.Arrays.stream(args).map(GriefPreventionAssetAdapter::parameterType).toArray(Class[]::new));
        return method.invoke(target, args);
    }
    private static Class<?> parameterType(Object value) { return value instanceof Long ? long.class : value.getClass(); }
}
