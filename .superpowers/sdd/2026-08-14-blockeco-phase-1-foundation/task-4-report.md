# Task 4 report — Vault gateway and registration saga

## Delivered

- Added the `EconomyGateway`, `AppClock`, and `MainThreadExecutor` ports.
- Added asynchronous `CompanyRegistrationService` with a durable saga: `PREPARED → WITHDRAWN → COMPLETED`; post-withdrawal SQL failure causes a full deposit compensation and persists either `REFUNDED` or `REFUND_REQUIRED`.
- Added startup recovery API: stale `PREPARED` records become `AMBIGUOUS`; it never invokes Vault or infers whether a crash-window debit completed.
- Added Vault adapter backed by Paper `Server`/`ServicesManager`, resolves `OfflinePlayer` from the UUID, verifies a finite exact `double` round trip at configured scale, and returns typed outcomes rather than leaking provider exceptions.
- Added `RegistrationSagaRepository.save` and stale-prepared lookup, including SQLite persistence.

## TDD evidence

1. Added `CompanyRegistrationServiceTest` first; `./gradlew.bat test --tests '*CompanyRegistrationServiceTest'` initially failed because the requested ports, service, request, and result types did not exist.
2. Added the minimal ports/service/repository persistence; the same focused test then passed all six safety scenarios.
3. Added `VaultEconomyGatewayTest` first; it initially failed because Vault classes and adapter were absent from test compilation.
4. Added test-scoped Vault API dependency and the minimal adapter; focused adapter tests passed.

## Verification

- `./gradlew.bat test --tests '*CompanyRegistrationServiceTest' --tests '*VaultEconomyGatewayTest'` — passed.
- `./gradlew.bat test` — passed.

## API references

- Vault official repository demonstrates `ServicesManager.getRegistration(Economy.class)`, `OfflinePlayer` debit/deposit calls, and accepting only `transactionSuccess()`: <https://github.com/MilkBowl/VaultAPI>
- Vault's official `AbstractEconomy` source confirms the `OfflinePlayer` overloads for `withdrawPlayer` and `depositPlayer`: <https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/AbstractEconomy.java>
- Paper's `Server` API supplies the services manager and UUID-based offline-player lookup: <https://jd.papermc.io/paper/1.21.4/org/bukkit/Server.html>

## Deliberate outcome choice

There is no dedicated state/result for a rejected debit before money moves. The saga remains `PREPARED`, while the command outcome is explicitly `INSUFFICIENT_FUNDS` or `PROVIDER_FAILURE`; no product-state enum was added.

## Remaining concern

Vault's `EconomyResponse` only gives a generic failure outcome; a provider-returned withdrawal failure is conservatively mapped to `INSUFFICIENT_FUNDS`, while exceptions/missing provider/non-representable values are `PROVIDER_FAILURE`. Operators should treat unusual provider failure messages as provider diagnostics.

## Fix round 1

- Vault withdrawal now calls the provider's documented `has(OfflinePlayer, amount)` first. Only `has == false` is `INSUFFICIENT_FUNDS`; `FAILURE`, `NOT_IMPLEMENTED`, null responses, and exceptions after `has == true` are `PROVIDER_FAILURE`. Source: <https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/EconomyResponse.java> and <https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/AbstractEconomy.java>.
- Added technical terminal `REJECTED`; known non-debits transition there asynchronously and are audited, so recovery cannot reclassify them as ambiguous. V001 includes this state and a partial unique name reservation index for active monetary sagas.
- Compensation now durably attempts `REFUND_REQUIRED` before the refund and uses asynchronous continuations only; failed refund diagnostics include both SQL and provider details. Stale `WITHDRAWN` rows are recovered to `REFUND_REQUIRED`.
- RED: the focused suite first failed after the changed rejection contract and mandatory Vault `has` precondition. GREEN: updated service/adapter and focused tests passed; then added null/exception response coverage and reran green.
- Commands: `./gradlew.bat test --tests '*VaultEconomyGatewayTest' --tests '*CompanyRegistrationServiceTest'` and `./gradlew.bat test` — both passed.
