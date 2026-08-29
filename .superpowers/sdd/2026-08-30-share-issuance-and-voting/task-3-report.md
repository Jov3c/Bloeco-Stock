# Task 3 report — securities-cash subscriptions and settlement

- Added correlation-key idempotent subscription reservation against the segregated securities-cash account.
- Closing is atomic: confirmation-ordered capacity allocation, unfilled-cash release, share delivery, company cash/capital, total shares, public issued shares, immutable settlement records, audit/announcement, and both escrow ledger projections are committed together.
- A second close is a no-op, so it cannot move cash or shares twice.

Verification:

```text
gradlew.bat test --tests cn.blockeco.exchange.application.ShareIssuanceServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlShareIssuanceRepositoryTest
BUILD SUCCESSFUL

gradlew.bat test --tests cn.blockeco.exchange.application.SecuritiesCashServiceTest --tests cn.blockeco.exchange.application.SecondaryMarketServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepositoryTest
BUILD SUCCESSFUL
```

Review follow-up:

- Correlation-key retries now reject a different holder or share count.
- Holding and paid-in-capital increments use checked Java arithmetic plus conditional projection writes; `Long.MAX_VALUE` regressions prove settlement rolls back without moving cash, shares, or proposal state.
