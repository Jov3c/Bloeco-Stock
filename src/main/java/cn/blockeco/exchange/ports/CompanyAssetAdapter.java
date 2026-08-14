package cn.blockeco.exchange.ports;

import java.util.UUID;

/** Optional provider bridge; implementations are registered only when their provider is present. */
public interface CompanyAssetAdapter {
    String id();
    Verification verify(UUID requester, String externalKey);
    record Verification(boolean ownedByRequester, UUID ownerId, String diagnostic) { }
}
