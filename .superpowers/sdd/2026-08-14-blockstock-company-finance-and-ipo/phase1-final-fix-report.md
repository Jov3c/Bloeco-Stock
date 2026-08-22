# Phase 1 final-fix report

- RED: `subscription_does_not_list_company_until_the_offering_closes` failed because `completeSubscription` directly changed company status to `LISTED`.
- GREEN: completion now only credits cash, holding, total shares and audit.  A closed offering lists exactly once in the same SQLite transaction only when it has at least one `COMPLETED` subscription; both sold-out and expiry closure use that path.
- RED: announcement terms test failed because the stored public announcement was only a generic sentence.
- GREEN: the announcement records its UUID, target, issue price, maximum shares, opening and closing time, paid-in capital, and a no-guaranteed-return risk notice.
- Focused verification: `PrimaryOfferingServiceTest` and `SqlPrimaryOfferingRepositoryTest` pass; `git diff --check` passes.
- Not implemented in this narrow fix: player `/company ipo subscribe`, public IPO list/info projections, and subscription crash-state startup recovery.  Consequently no authenticated-player Paper smoke was performed.
