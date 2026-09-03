package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.ports.AssetCatalogAdapter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/** Verified adapter for QuickShop-Hikari's public API, loaded without a compile-time dependency. */
final class QuickShopAssetAdapter implements AssetCatalogAdapter {
    private final Plugin provider;

    QuickShopAssetAdapter(Plugin provider) { this.provider = Objects.requireNonNull(provider, "provider"); }
    @Override public String id() { return "quickshop"; }

    @Override public List<AssetChoice> listOwned(UUID requester, String search, int limit) {
        if (limit <= 0) return List.of();
        try {
            Object manager = call(api(), "getShopManager");
            Object raw = call(manager, "getAllShops", requester);
            if (!(raw instanceof List<?> shops)) return List.of();
            String needle = search == null ? "" : search.trim().toLowerCase(java.util.Locale.ROOT);
            List<AssetChoice> choices = new ArrayList<>();
            for (Object shop : shops) {
                long id = (Long) call(shop, "getShopId"); if (id <= 0) continue;
                String display = describe(shop, id);
                if (!needle.isEmpty() && !display.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
                choices.add(new AssetChoice(ProviderAssetKeys.format("shop", id), display, "QuickShop 箱子商店"));
                if (choices.size() >= limit) break;
            }
            return List.copyOf(choices);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            return List.of();
        }
    }

    @Override public Verification verify(UUID requester, String externalKey) {
        var shopId = ProviderAssetKeys.parse("shop", externalKey);
        if (shopId.isEmpty()) return new Verification(false, null, "商店标识无效");
        try {
            Object shop = call(call(api(), "getShopManager"), "getShop", shopId.getAsLong());
            if (shop == null) return new Verification(false, null, "商店不存在或已删除");
            Object owner = call(shop, "getOwner"); UUID ownerId = (UUID) call(owner, "getUniqueId");
            return new Verification(requester.equals(ownerId), ownerId, requester.equals(ownerId) ? "已验证商店所有者" : "你不是该商店所有者");
        } catch (ReflectiveOperationException | ClassCastException failure) {
            return new Verification(false, null, "无法读取 QuickShop 商店信息");
        }
    }

    private Object api() throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI", true, provider.getClass().getClassLoader());
        return type.getMethod("getInstance").invoke(null);
    }
    private static String describe(Object shop, long id) throws ReflectiveOperationException {
        Location location = (Location) call(shop, "bukkitLocation");
        Object item = call(shop, "getItem"); Object material = call(item, "getType");
        return "商店 #" + id + " · " + material + " · " + location.getWorld().getName() + " (" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, java.util.Arrays.stream(args).map(QuickShopAssetAdapter::parameterType).toArray(Class[]::new));
        return method.invoke(target, args);
    }
    private static Class<?> parameterType(Object value) { return value instanceof Long ? long.class : value.getClass(); }
}
