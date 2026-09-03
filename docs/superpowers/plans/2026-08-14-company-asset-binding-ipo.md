# Company Asset Binding and IPO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind verified provider-neutral company assets and safely announce, subscribe to, and close primary offerings.

**Architecture:** Asset adapters are injected ports, so no optional gameplay-provider API is linked. SQL repositories use the V003 finance tables and atomically update offerings, holdings, cash, company state, announcements, and audits. Subscriptions persist a treasury operation before Vault interaction; only a confirmed escrow deposit can issue shares.

**Tech Stack:** Java 21, Paper, SQLite, Vault, JUnit 5, AssertJ.

## Global Constraints

- Do not modify V001–V005 or add Lands, QuickShop, Slimefun, or BetonQuest runtime dependencies.
- All time comes from `AppClock`; announcement/open boundary is 12 hours and open/close boundary is 2 days.
- All money movements retain durable saga states, expected-state transitions, and audit entries.
- A company becomes `LISTED` only after an actual issued primary share; unsold shares are never issued.

---

### Task 1: Provider-neutral asset binding

**Files:**
- Create: `ports/CompanyAssetAdapter.java`, `ports/AssetBindingRepository.java`, `application/AssetBindingService.java`, `infrastructure/sql/SqlAssetBindingRepository.java`
- Test: `application/AssetBindingServiceTest.java`

- [ ] Write adapter ownership and duplicate/active-count tests, run them RED, then implement verification, transaction persistence, and audits.

### Task 2: Primary offering saga and SQL persistence

**Files:**
- Create: `ports/PrimaryOfferingRepository.java`, `application/PrimaryOfferingService.java`, `infrastructure/sql/SqlPrimaryOfferingRepository.java`
- Modify: company repository interfaces/SQL implementation as required for expected-state company updates.
- Test: `application/PrimaryOfferingServiceTest.java`, `infrastructure/sql/SqlPrimaryOfferingRepositoryTest.java`

- [ ] Write cap, time-boundary, exact-funds, idempotency, exhaustion, and expiry tests; run RED; implement a persisted prepared subscription operation, external withdrawal/deposit, and transactional issuance/auditing; run GREEN.

### Task 3: Paper command integration

**Files:**
- Modify: plugin bootstrap, command, completer, messages, config, and plugin metadata.
- Test: `paper/CompanyCommandTest.java`, `paper/CompanyTabCompleterTest.java`

- [ ] Write command/permission parser tests, run RED, wire services and Chinese messages, then run focused tests.

### Task 4: Verification and report

- [ ] Run the brief's focused tests, full `./gradlew.bat test`, `git diff --check`, write the requested report, review diff, and commit.
