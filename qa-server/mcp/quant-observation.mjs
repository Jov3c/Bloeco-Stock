/*
 * Read-only local-QA observation for the finite bluechip quant participant.
 * It deliberately talks only to the loopback QA database; it never connects to
 * a player-facing Minecraft server and it does not write any state.
 *
 * Usage (from qa-server/mcp):
 *   node quant-observation.mjs
 *   $env:QUANT_OBSERVATION_MS=130000; node quant-observation.mjs
 */
import assert from 'node:assert/strict'
import { DatabaseSync } from 'node:sqlite'

const DB_PATH = '../plugins/BlockStock/blockeco.db'
const DURATION_MS = Number.parseInt(process.env.QUANT_OBSERVATION_MS ?? '600000', 10)
const SAMPLE_MS = 2000
// Must match BlockecoPlugin's finite bluechip system participant, not a maker account.
const QUANT_PARTICIPANT_ID = '00000000-0000-0000-0000-000000000077'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

assert.ok(Number.isInteger(DURATION_MS) && DURATION_MS >= 8_000, 'QUANT_OBSERVATION_MS must be at least 8000')

function readSnapshot() {
  // A short busy timeout makes the observer yield to the server's write
  // transaction instead of indefinitely blocking a QA run.
  const db = new DatabaseSync(DB_PATH, { readOnly: true, timeout: 250 })
  try {
    const one = (sql, ...values) => db.prepare(sql).get(...values)
    const all = (sql, ...values) => db.prepare(sql).all(...values)
    return {
      decisions: all('SELECT stock_code, action, requested_shares, filled_shares, decided_at FROM bluechip_quant_decisions ORDER BY decided_at'),
      cash: one('SELECT available_minor, reserved_minor FROM securities_cash_accounts WHERE player_uuid=?', QUANT_PARTICIPANT_ID) ?? { available_minor: 0, reserved_minor: 0 },
      negativeShares: one('SELECT COUNT(*) AS count FROM share_holdings WHERE available_shares < 0 OR reserved_shares < 0')?.count ?? 0,
      riskOutOfBounds: one('SELECT COUNT(*) AS count FROM bluechip_quant_risk WHERE risk_level < 0 OR risk_level > 3')?.count ?? 0,
      compensationFund: one('SELECT balance_minor FROM compensation_fund WHERE singleton=1')?.balance_minor ?? 0,
    }
  } finally { db.close() }
}

async function readWithRetry() {
  let last
  for (let attempt = 0; attempt < 6; attempt += 1) {
    try { return readSnapshot() } catch (error) { last = error }
    await sleep(150)
  }
  throw last
}

function assertSafe(snapshot, baselineFund) {
  assert.ok(snapshot.cash.available_minor >= 0, 'quant available cash must never become negative')
  assert.ok(snapshot.cash.reserved_minor >= 0, 'quant reserved cash must never become negative')
  assert.equal(snapshot.negativeShares, 0, 'all share balances must remain nonnegative')
  assert.equal(snapshot.riskOutOfBounds, 0, 'risk level must remain within 0..3')
  // Normal matching fees may increase this fund.  The strategy must never use it.
  assert.ok(snapshot.compensationFund >= baselineFund, 'quant strategy must not reduce compensation fund')
}

function assertOrderCadence(decisions) {
  const buckets = new Map()
  for (const decision of decisions) {
    if (decision.action !== 'BUY' && decision.action !== 'SELL') continue
    const bucket = Math.floor(Date.parse(decision.decided_at) / 8000)
    buckets.set(bucket, (buckets.get(bucket) ?? 0) + 1)
  }
  for (const [bucket, count] of buckets) assert.ok(count <= 1, `more than one quant order in eight-second bucket ${bucket}`)
}

const baseline = await readWithRetry()
assertSafe(baseline, baseline.compensationFund)
const startedAt = Date.now()
let latest = baseline
while (Date.now() - startedAt < DURATION_MS) {
  await sleep(SAMPLE_MS)
  latest = await readWithRetry()
  assertSafe(latest, baseline.compensationFund)
  assertOrderCadence(latest.decisions)
}

assert.ok(latest.decisions.length > baseline.decisions.length, 'quant decisions must grow during an open observation')
assertOrderCadence(latest.decisions)
const added = latest.decisions.slice(baseline.decisions.length)
const orders = added.filter(decision => decision.action === 'BUY' || decision.action === 'SELL').length
const filled = added.reduce((sum, decision) => sum + decision.filled_shares, 0)
console.log(`QUANT_OBSERVATION_PASS|duration_ms=${DURATION_MS}|decisions_added=${added.length}|orders_added=${orders}|filled_shares=${filled}|cash=${latest.cash.available_minor}:${latest.cash.reserved_minor}|fund_delta=${latest.compensationFund - baseline.compensationFund}`)
