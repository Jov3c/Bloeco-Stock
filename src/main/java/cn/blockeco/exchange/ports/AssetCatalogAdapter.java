package cn.blockeco.exchange.ports;

import java.util.List;
import java.util.UUID;

/** Optional safe picker capability for GUI asset binding. */
public interface AssetCatalogAdapter extends CompanyAssetAdapter {
    List<AssetChoice> listOwned(UUID requester, String search, int limit);
    record AssetChoice(String externalKey, String displayName, String type) { public AssetChoice { if (externalKey == null || externalKey.isBlank() || displayName == null || displayName.isBlank() || type == null || type.isBlank()) throw new IllegalArgumentException("asset choice fields must be non-blank"); } }
}
