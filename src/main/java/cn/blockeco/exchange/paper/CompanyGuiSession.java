package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

/** Immutable, owner-bound state for a company-centre inventory flow. */
final class CompanyGuiSession {
    enum Page {
        HOME, CREATE_NAME, CREATE_CAPITAL, CREATE_DIVIDEND, CONFIRM_CREATE,
        ASSETS, CREATE_NATIVE_ASSET, CONFIRM_CREATE_NATIVE_ASSET, CONFIRM_BIND
    }

    sealed interface Draft permits CompanyDraft, NativeAssetDraft, AssetBindingDraft { }

    record CompanyDraft(String name, Money capital, int dividendPercent) implements Draft {
        CompanyDraft {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(capital, "capital");
        }
    }

    record NativeAssetDraft(String name) implements Draft {
        NativeAssetDraft { Objects.requireNonNull(name, "name"); }
    }

    record AssetBindingDraft(String adapterId, String externalKey, String displayName) implements Draft {
        AssetBindingDraft {
            if (adapterId == null || adapterId.isBlank() || externalKey == null || externalKey.isBlank()
                    || displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("asset binding fields must be non-blank");
            }
        }
    }

    private final UUID id;
    private final UUID playerId;
    private final Page page;
    private final Draft draft;

    private CompanyGuiSession(UUID id, UUID playerId, Page page, Draft draft) {
        this.id = id;
        this.playerId = playerId;
        this.page = page;
        this.draft = draft;
    }

    static CompanyGuiSession open(UUID playerId) {
        return new CompanyGuiSession(UUID.randomUUID(), Objects.requireNonNull(playerId, "playerId"), Page.HOME, null);
    }

    CompanyGuiSession next(Page nextPage, Draft nextDraft) {
        return new CompanyGuiSession(UUID.randomUUID(), playerId, Objects.requireNonNull(nextPage, "nextPage"), nextDraft);
    }

    UUID id() { return id; }
    Page page() { return page; }
    Draft draft() { return draft; }
    boolean belongsTo(UUID player) { return playerId.equals(player); }
}
