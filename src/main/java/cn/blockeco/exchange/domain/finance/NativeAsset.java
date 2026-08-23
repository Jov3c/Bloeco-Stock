package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** BlockStock-owned asset which is usable without any optional land or shop provider. */
public record NativeAsset(UUID id, CompanyId companyId, UUID founderId, String name, Instant createdAt) {
    public NativeAsset { Objects.requireNonNull(id,"id"); Objects.requireNonNull(companyId,"companyId"); Objects.requireNonNull(founderId,"founderId"); Objects.requireNonNull(name,"name"); Objects.requireNonNull(createdAt,"createdAt"); if(name.isBlank()||name.length()>32)throw new IllegalArgumentException("name must contain 1-32 characters"); }
    public String externalKey() { return id.toString(); }
}
