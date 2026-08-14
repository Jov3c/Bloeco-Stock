# BlockecoExchange

BlockecoExchange is a **server-only** Paper plugin foundation for a Minecraft economy and company exchange. Players do not install a client mod or resource pack. This Phase 1 build safely creates persistent companies, withdraws their registration money through Vault, and gives administrators recovery visibility. Trading, charts, land/shop/production adapters, dividends, issuance, and delisting are planned later phases.

## Requirements

- Paper `1.21.4+`; the tested baseline is Paper `1.21.4` build `232`.
- Java `21+` for the tested baseline. Newer Paper releases may raise their Java requirement; follow the [Paper documentation](https://docs.papermc.io/paper/getting-started) for the server version you choose.
- [Vault](https://github.com/MilkBowl/Vault/releases) and exactly one enabled Vault-compatible economy provider. The smoke environment uses [EssentialsX](https://github.com/EssentialsX/Essentials/releases) Economy.

The plugin uses public Bukkit/Paper and Vault APIs only; it does not use NMS/CraftBukkit internals.

## Build and install

Build with Java 21:

```powershell
.\gradlew.bat clean test shadowJar
```

Install the one `*-all.jar` from `build/libs/` into the server's `plugins/` folder, together with Vault and an economy-provider JAR obtained from their official releases. Start the server once. Blockeco creates:

```text
plugins/BlockecoExchange/config.yml
plugins/BlockecoExchange/blockeco.db
```

Do not place the Paper API, Vault API, or a client-side mod in the client. Vault itself and the chosen economy provider must be server plugins.

For local integration testing, `runServer` is pinned to Paper 1.21.4:

```powershell
.\gradlew.bat runServer
```

`run/` is deliberately ignored by Git. Before running it, accept Mojang's EULA in `run/eula.txt` and manually place Vault plus the economy provider in `run/plugins/`. The Gradle run-paper task automatically uses the Shadow JAR.

## Configuration

All defaults in `plugins/BlockecoExchange/config.yml` are listed below. Monetary values are decimal strings and are converted exactly into signed `long` minor units using `currency.scale`.

| Key | Default | Meaning |
| --- | --- | --- |
| `currency.scale` | `2` | Decimal places accepted by the economy adapter; valid range is 0–8. |
| `company.registration-fee` | `'1000.00'` | Positive fee removed from circulation on a successful registration. |
| `company.minimum-capital` | `'10000.00'` | Positive capital transferred into the new company's internal treasury. |
| `company.initial-shares` | `1000` | Reserved Phase 1/IPO setting. Initial companies use exactly 1,000 shares. |
| `company.allowed-dividend-percent` | `[30, 50, 70]` | Reserved configuration mirror of the three immutable registration choices. |
| `database.file` | `blockeco.db` | SQLite filename relative to `plugins/BlockecoExchange/`. |
| `database.pool-size` | `2` | Reserved pool-size setting; this Phase 1 build uses its fixed small Hikari configuration. |
| `messages.*` | See generated file | All player-visible command messages, including processing, recovery, and lookup text. Keep `%name%`, `%state%`, `%id%`, `%player%`, `%amount%`, `%time%`, and `%error%` placeholders where applicable. |

Restart after changing configuration. The plugin rejects invalid scales and non-positive fee/capital instead of silently rounding them.

## Commands and permissions

| Command | Permission | Notes |
| --- | --- | --- |
| `/company create <name> <30\|50\|70>` | `blockeco.company.create` (default: true) | Player only. The name may contain spaces; quoted names are accepted. It starts an asynchronous registration. |
| `/company info <name>` | `blockeco.company.info` (default: true) | Shows the stored company name and current lifecycle state. |
| `/company recovery list` | `blockeco.admin.recovery` (default: op) | Lists unresolved/visible registration records; it never performs a forced refund. |

Creating a company withdraws **registration fee + minimum capital exactly once** in the normal path. Only minimum capital is placed in the company treasury. A company begins as `PENDING_ASSET_BINDING`, with 1,000 shares and its selected immutable 30%, 50%, or 70% dividend tier. The founder has no treasury-withdrawal command.

## Backup, restoration, and recovery

The authoritative Phase 1 data is the SQLite database at `plugins/BlockecoExchange/blockeco.db`.

1. Stop Paper cleanly (`stop`) and wait for shutdown to finish.
2. Copy `blockeco.db` to a timestamped backup location.
3. Start Paper again and retain the backup until the server has completed its health checks.

Do **not** copy a live SQLite database while omitting its matching `-wal` and `-shm` files. For a live backup, use a SQLite-consistent backup method/tool; the safest routine operation is a clean server stop followed by a copy.

Each money-affecting registration writes an audit event and follows a persisted saga. Use `/company recovery list` to inspect unresolved records:

- `REFUND_REQUIRED`: the withdrawal was known to have happened, a later persistence step failed, and the compensating deposit failed. Check Vault/economy-provider history, then compensate only the recorded full withdrawal after confirming it was not already refunded.
- `AMBIGUOUS`: the server may have stopped between the provider call and durable `WITHDRAWN` state. Blockeco intentionally does **not** guess whether money moved. Inspect the Blockeco audit log and the economy provider's transaction/history data before any manual compensation. Never blind auto-refund an ambiguous record.

Other normal terminal states are `COMPLETED`, `REFUNDED`, and `REJECTED`. `PREPARED` and `WITHDRAWN` can be visible after interruption and are handled conservatively during startup recovery.

## Real-server smoke checklist

Use a clean ignored `run/` folder, then:

1. Place official Vault and economy-provider JARs in `run/plugins/`; set `run/eula.txt` to `eula=true`.
2. Run `.\gradlew.bat runServer` with Java 21 and confirm Paper, Vault, the economy provider, and BlockecoExchange load.
3. Confirm the log contains `Blockeco ready; stale registration records scanned=...` and Vault reports the economy provider as hooked.
4. Join as a test player with a known balance; run `/company create "Red Stone" 50`. Confirm one debit of fee + capital, `PENDING_ASSET_BINDING`, 1,000 shares, 50% tier, and capital-only treasury using the database/audit data.
5. Run `/company info "Red Stone"`, then repeat the create command and verify duplicate-name rejection without another debit.
6. Stop with `stop`, restart, and verify the company/audit data remain.
7. For recovery rehearsal, stop only in a controlled test environment during a deliberately delayed registration; verify the record is visible through `/company recovery list`. Never use production funds for this drill.

### Executed verification matrix (2026-08-14)

| Check | Result | Evidence |
| --- | --- | --- |
| Gradle run task syntax | Passed | `help --task runServer` reported `xyz.jpenilla.runpaper.task.RunServer`. |
| First Paper boot | Passed | Paper 1.21.4 build 232 on Java 21.0.11 loaded BlockecoExchange, Vault 1.7.3-b131, and Essentials 2.21.0. |
| Vault provider resolution | Passed | Vault reported `Essentials Economy hooked`; Blockeco logged its ready line. |
| Schema migration/database creation | Passed | `plugins/BlockecoExchange/blockeco.db` was created and Hikari started successfully. |
| Clean Blockeco shutdown | Passed | Hikari logged shutdown initiated/completed before Vault shutdown. |
| Restart | Passed | A second boot reused the same `run/` database and again reached the Blockeco ready line. |
| Authenticated player create/info/duplicate debit | Not executed | No automated authenticated Minecraft client/player session was available in this local smoke environment. Follow steps 4–5 above on an operator test server. |
| Deliberately interrupted registration | Not executed | Requires a controlled delayed provider call and player session; retained as an operator-only test. |

The rapid piped `stop` used for the local smoke run caused an EssentialsX update-checker shutdown warning after Blockeco had already shut down cleanly. It is an EssentialsX asynchronous-task warning, not a Blockeco stack trace; allow normal test-server idle time before stopping when evaluating provider logs.

## Compatibility sources

- [run-paper 3.0.2 on the Gradle Plugin Portal](https://plugins.gradle.org/plugin/xyz.jpenilla.run-paper/3.0.2) and the [official run-task usage](https://github.com/jpenilla/run-task) document the `runServer { minecraftVersion(...) }` configuration.
- [Vault official releases](https://github.com/MilkBowl/Vault/releases) provide Vault 1.7.3.
- [EssentialsX official releases](https://github.com/EssentialsX/Essentials/releases/tag/2.21.0) provide the smoke-test economy provider.
