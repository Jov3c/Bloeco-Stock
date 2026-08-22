# Phase 1 final-fix report

- RED: `subscription_does_not_list_company_until_the_offering_closes` failed because `completeSubscription` directly changed company status to `LISTED`.
- GREEN: completion now only credits cash, holding, total shares and audit.  A closed offering lists exactly once in the same SQLite transaction only when it has at least one `COMPLETED` subscription; both sold-out and expiry closure use that path.
- RED: announcement terms test failed because the stored public announcement was only a generic sentence.
- GREEN: the announcement records its UUID, target, issue price, maximum shares, opening and closing time, paid-in capital, and a no-guaranteed-return risk notice.
- Focused verification: `PrimaryOfferingServiceTest` and `SqlPrimaryOfferingRepositoryTest` pass; `git diff --check` passes.
- Not implemented in this narrow fix: player `/company ipo subscribe`, public IPO list/info projections, and subscription crash-state startup recovery.  Consequently no authenticated-player Paper smoke was performed.

## Round 2: expiry/completion race

- RED: an `ESCROW_DEPOSITED` subscription can be followed by the expiry scheduler closing the offering while its completed-share count is zero; completing that subscription afterwards previously left the company unlisted.
- GREEN: `completeSubscription` reads its current offering id before completion, then after its cash/holding/share/operation/audit writes checks the offering state in that same SQLite transaction.  It lists only when that offering is `CLOSED`; the conditional company update remains the once-only `IPO_LISTED` audit gate.
- The race regression repeats both completion and scheduler invocation and proves exactly one listing audit.  The zero-subscription announced-expiry characterization proves neither shares nor listing is created.

## Round 3: typed subscription outcome

- `PrimaryOfferingService.subscribe` now returns `SubscriptionResult` rather than exposing its exception text. Existing open/recovery tests assert the typed statuses.
- A definitive Vault withdrawal failure transitions the durable `PREPARED` operation to terminal `REFUNDED`, so it no longer reserves capacity or gets converted to ambiguous. The command maps typed results to Chinese player messages and returns completion on the Paper main thread.
- Added `blockeco.company.ipo.subscribe` as a default-true player permission and permission-aware IPO tab candidate. Authenticated Paper smoke remains unperformed.
- Remaining work: public `ipo list` / `ipo info` SQL projections and their command presentation were not completed in this commit.

## Round 4: external-failure boundary

- Vault exceptions and each post-Vault SQL boundary now return `RECOVERY_REQUIRED` after a best-effort durable ambiguous transition; failure to persist that marker does not downgrade the player result.
- Only a definite non-success withdrawal outcome is terminally released (`PREPARED` to `REFUNDED`). Retrying a terminal deterministic id returns its stable provider-failure result; nonterminal persisted ids never replay Vault.
- Capacity now excludes `REFUNDED`, while early close counts only `COMPLETED` subscriptions. Configuration includes every IPO subscription Chinese message key.

## Round 5: actual-state ambiguity

## Round 6: typed prepare rejection and subscribe command hardening

- RED: a second buyer of an exhausted one-share offering received `NOT_OPEN`, because the service collapsed every prepare failure to that status.
- GREEN: `PrimaryOfferingRepository.SubscriptionPreparationRejectedException` carries only `NOT_OPEN`, `SOLD_OUT`, or `INVALID`; SQL opening, missing-offering, capacity, and arithmetic boundaries classify deliberately, while unknown pre-Vault failures map to `PROVIDER_FAILURE`.
- New regression tests: `sold_out_is_a_typed_result_and_does_not_call_vault_for_the_rejected_buyer`, `unknown_prepare_failure_is_provider_failure_not_not_open_and_never_calls_vault`, `persisted_withdrawn_or_escrow_deposited_subscription_never_replays_vault`, `post_vault_failures_return_recovery_and_record_ambiguous_without_replaying_a_second_vault_call`, `ipo_subscribe_validates_player_permission_and_arguments_before_dispatch`, `ipo_subscribe_dispatches_exact_arguments_and_completes_only_on_main_thread`, and `ipo_second_level_candidates_are_player_only_and_individually_permission_scoped`.

## Round 7: real SQLite subscription failure injection

- Added migrated-Database regressions using `SqlPrimaryOfferingRepository` and a transaction wrapper that rolls back a selected invocation after real SQL work. They cover thrown withdrawal, rollback after successful withdrawal, thrown/failed escrow deposit, final completion rollback, failed insufficient-funds cancellation, and persisted `ESCROW_DEPOSITED` replay prevention.
- Each ambiguous case verifies its durable operation state, exactly one `IPO_TREASURY_AMBIGUOUS` audit, and exact gateway call counts; the final rollback additionally proves company cash, buyer holding, and total shares remain unchanged.

- The repository now owns ambiguity transitions: it reads the durable operation state, only conditionally changes `PREPARED`, `PLAYER_WITHDRAWN`, or `ESCROW_DEPOSITED`, and never mutates completed/refunded records. The immutable audit records `actualState`, `externalStage`, and `reason`.
- Service failure boundaries pass an external stage/reason only; they cannot guess a state. Focused service/repository verification passed after this contract change.

## Round 8: public IPO discovery

- Added immutable `PublicOfferingView` and public repository/service queries. The SQL projection uses prepared joins only and exposes offering/company identity, terms, lifecycle times and safe share totals; it never exposes subscribers, escrow identity or saga diagnostics.
- `issuedShares` counts only `COMPLETED` treasury operations. `reservedShares` counts every non-`REFUNDED` operation, including prepared/in-flight/ambiguous records, and `availableShares` is checked as `maximum-reserved`.
- `/company ipo list` and `/company ipo info <发行UUID>` are public after the normal accepting gate, execute read work asynchronously and publish Chinese results on the Paper main thread. Help, config, metadata, README and tab completion include `list`/`info`; the latter are always offered while player-only `announce`/`subscribe` remain permission scoped.
