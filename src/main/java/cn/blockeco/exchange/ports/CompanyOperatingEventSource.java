package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import java.time.Instant;
import java.util.List;

public interface CompanyOperatingEventSource {
    String adapterId();

    /** Returns only events whose adapterId equals this source's adapterId. */
    List<VerifiedOperatingEvent> readSince(AssetBinding binding, Instant afterExclusive, Instant throughInclusive);
}
