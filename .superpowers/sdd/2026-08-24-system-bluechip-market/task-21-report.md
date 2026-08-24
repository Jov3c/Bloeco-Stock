# Task 21 — Deferred Market-Maker Replenishment

## Root cause

`replenishAfterMatch()` discarded a settlement callback whenever a system quote pass was
active.  An opening queued sweep can consume all five system asks during that pass, leaving
no later request to restore the book.

## Change

- Coalesce callbacks received during a system quote pass in a boolean pending flag.
- On pass completion, clear the active guard and enqueue exactly one detached refill behind
  the completed lifecycle stage.
- The deferred call still evaluates pause/session/close conditions through the ordinary
  `replenishAfterMatch()` path; it never runs recursively or blocks settlement/player work.

## Test-first evidence

Added `openingQuotePassDefersMakerRefillRequestedByItsOwnQueuedSweep`.

The test first failed on the prior implementation (`queuedSweeps` stayed at 1).  It models a
pre-open player order consuming all five asks and issues two callbacks while the first quote
batch is active.  It now verifies one deferred second sweep and restored five bid/five ask
levels.

## Verification

- Focused maker test: passed.
- Full `./gradlew.bat test shadowJar --rerun-tasks --no-build-cache --no-daemon --console=plain`:
  all JUnit XML suites report zero failures/errors and `build/libs/blockstock-0.1.0-SNAPSHOT-all.jar` exists.

No QA server or live server was started, changed, or deployed.
