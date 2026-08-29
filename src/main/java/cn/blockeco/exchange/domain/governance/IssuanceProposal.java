package cn.blockeco.exchange.domain.governance;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IssuanceProposal(UUID id, CompanyId companyId, UUID proposerUuid, long newShares, long issuePriceMinor, Instant announcedAt, IssuanceProposalState state) {
    public IssuanceProposal(UUID id, CompanyId companyId, UUID proposerUuid, long newShares, long issuePriceMinor, Instant announcedAt) {
        this(id, companyId, proposerUuid, newShares, issuePriceMinor, announcedAt, IssuanceProposalState.ANNOUNCED);
    }
    public IssuanceProposal {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(companyId, "companyId"); Objects.requireNonNull(proposerUuid, "proposerUuid"); Objects.requireNonNull(announcedAt, "announcedAt"); Objects.requireNonNull(state, "state");
        if (newShares <= 0 || issuePriceMinor <= 0) throw new IllegalArgumentException("new shares and issue price must be positive");
        Math.multiplyExact(newShares, issuePriceMinor);
    }
}
