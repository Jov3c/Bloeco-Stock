/*
 * Isolated Paper acceptance for the bluechip market.  This file deliberately
 * connects only to qa-server's loopback ports (25566/25567), never the normal
 * development/live Paper port.  Run from qa-server/mcp.
 *
 * The phase runner executes this once for each QA_CLOCK_PHASE (PREOPEN, OPEN,
 * POSTCLOSE), after starting the QA server with the corresponding QA-only
 * market.time-zone fixture.  It then stops Paper and runs `--ledger` to check
 * the durable SQLite invariants.  Keeping the clock fixture outside the bot
 * is important: Mineflayer remains a real unmodified client.
 */
import assert from 'node:assert/strict'
import net from 'node:net'
import { readFileSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import mineflayer from 'mineflayer'

const HOST = '127.0.0.1'
const GAME_PORT = 25566
const RCON_PORT = 25567
const QA_PLAYER = 'BluechipQA'
const timeoutMs = 20_000
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const phase = process.env.QA_CLOCK_PHASE ?? 'OPEN'
const dbPath = '../plugins/BlockStock/blockeco.db'

function packet(id, type, body) { const text = Buffer.from(body); const p = Buffer.alloc(14 + text.length); p.writeInt32LE(10 + text.length); p.writeInt32LE(id, 4); p.writeInt32LE(type, 8); text.copy(p, 12); return p }
function rcon(command) {
  const password = /^rcon.password=(.*)$/m.exec(readFileSync('../server.properties', 'utf8'))?.[1]
  assert.ok(password, 'QA RCON password must be configured')
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: HOST, port: RCON_PORT }); let authenticated = false; let buffer = Buffer.alloc(0)
    socket.setTimeout(timeoutMs, () => reject(new Error(`RCON timeout: ${command}`)))
    socket.on('connect', () => socket.write(packet(101, 3, password)))
    socket.on('data', data => { buffer = Buffer.concat([buffer, data]); if (buffer.length < 4 || buffer.length < buffer.readInt32LE(0) + 4) return; const id = buffer.readInt32LE(4); const body = buffer.subarray(12, buffer.readInt32LE(0) + 2).toString(); if (!authenticated) { if (id === -1) return reject(new Error('QA RCON authentication failed')); authenticated = true; socket.write(packet(102, 2, command)); } else { socket.end(); resolve(body) } })
    socket.on('error', reject)
  })
}
function title(window) { return typeof window?.title === 'string' ? window.title : JSON.stringify(window?.title ?? '') }
function itemName(item) { return item?.displayName ?? item?.name ?? '' }
function connect() { return new Promise((resolve, reject) => { const bot = mineflayer.createBot({ host: HOST, port: GAME_PORT, username: QA_PLAYER, auth: 'offline', version: '1.21.4' }); bot.once('spawn', () => resolve(bot)); bot.once('kicked', reason => reject(new Error(`bot kicked: ${JSON.stringify(reason)}`))); bot.once('error', reject) }) }
async function waitForWindow(bot, expected, sequence = -1) { if (bot.windowSequence > sequence && title(bot.currentWindow).includes(expected)) return bot.currentWindow; return new Promise((resolve, reject) => { const timer = setTimeout(() => { bot.off('windowOpen', opened); reject(new Error(`timed out waiting for ${expected}; current=${title(bot.currentWindow)}`)) }, timeoutMs); const opened = window => { if (!title(window).includes(expected)) return; clearTimeout(timer); bot.off('windowOpen', opened); resolve(window) }; bot.on('windowOpen', opened) }) }
async function clickAndWait(bot, slot, expected) { const next = waitForWindow(bot, expected, bot.windowSequence); await delay(150); try { await bot.clickWindow(slot, 0, 0) } catch { /* GUI cancels vanilla inventory mutations. */ } const window = await next; await delay(200); return window }
async function sendAnvilText(bot, value, expected) { if (bot.supportFeature('useMCItemName')) { bot._client.registerChannel('MC|ItemName', 'string'); for (let n = 1; n <= value.length; n++) { bot._client.writeChannel('MC|ItemName', value.slice(0, n)); await delay(50) } } else { for (let n = 1; n <= value.length; n++) { bot._client.write('name_item', { name: value.slice(0, n) }); await delay(50) } } return clickAndWait(bot, 2, expected) }
async function home(bot) { bot.chat('/stock'); return waitForWindow(bot, 'BlockStock', -1) }
function assertTenBluechips(window) { const names = window.slots.slice(0, 10).filter(Boolean).map(itemName); assert.equal(names.length, 10, `market must contain exactly ten bluechips, saw ${names.length}`); for (const code of ['NOVA', 'AURORA', 'TERRAN', 'SKYLINE', 'IRONWOOD', 'LUMEN', 'RIVERMINT', 'ORBITAL', 'CINDER', 'VERDANT']) assert.ok(names.some(name => name.includes(code)), `missing ${code} from original-client market GUI`) }
function assertFiveLevels(window) { for (const slot of [...Array(5).keys()].map(n => 10 + n).concat([...Array(5).keys()].map(n => 28 + n))) assert.ok(window.slots[slot], `missing five-level quote at GUI slot ${slot}`) }
async function deposit(bot) { await home(bot); await clickAndWait(bot, 13, '证券账户'); await clickAndWait(bot, 29, '输入金额'); await sendAnvilText(bot, '10000', '确认执行'); try { await bot.clickWindow(29, 0, 0) } catch {} await delay(1200) }
async function placeGuiBuy(bot) { await home(bot); const market = await clickAndWait(bot, 11, '市场'); assertTenBluechips(market); const detail = await clickAndWait(bot, 0, 'BlockStock NOVA'); assertFiveLevels(detail); await clickAndWait(bot, 48, '输入正整数股数'); await sendAnvilText(bot, '10', '输入限价'); await sendAnvilText(bot, '12.00', '确认执行'); try { await bot.clickWindow(29, 0, 0) } catch {} await delay(1200) }
async function runGui() {
  await rcon(`op ${QA_PLAYER}`); await rcon(`eco give ${QA_PLAYER} 1000000`); await rcon('stockadmin bluechip init')
  const bot = await connect(); bot.windowSequence = 0; bot.on('windowOpen', () => { bot.windowSequence++ })
  try {
    await deposit(bot)
    if (phase === 'OPEN') { await placeGuiBuy(bot); await home(bot); const news = await clickAndWait(bot, 33, '市场快讯'); await rcon('stockadmin bluechip event market 100'); await home(bot); const afterEvent = await clickAndWait(bot, 33, '市场快讯'); assert.ok(afterEvent.slots.some(item => itemName(item).includes('大盘') || itemName(item).includes('市场')), 'market event must appear in original-client news GUI'); console.log('BLUECHIP_GUI_PASS|open|ten-companies|five-levels|buy|news') }
    else { await placeGuiBuy(bot); console.log(`BLUECHIP_GUI_PASS|${phase}|order-submitted`) }
  } finally { bot.quit('Bluechip QA finished') }
}
function scalar(db, sql) { return db.prepare(sql).get()?.value }
function ledger() {
  const db = new DatabaseSync(dbPath, { readOnly: true })
  try {
    assert.equal(scalar(db, 'SELECT COUNT(*) AS value FROM bluechip_companies'), 10, 'exactly ten bluechip companies')
    assert.equal(scalar(db, 'SELECT COUNT(*) AS value FROM securities_cash_accounts WHERE available_minor < 0 OR reserved_minor < 0'), 0, 'no negative securities cash')
    assert.equal(scalar(db, 'SELECT COUNT(*) AS value FROM share_holdings WHERE available_shares < 0 OR reserved_shares < 0'), 0, 'no negative holdings')
    assert.equal(scalar(db, 'SELECT COUNT(*) AS value FROM bluechip_fund_audit WHERE cash_delta_minor < 0 AND shares_delta < 0'), 0, 'fund audit cannot debit cash and shares together')
    assert.equal(scalar(db, `SELECT COUNT(*) AS value FROM (SELECT sl.company_id FROM stock_listings sl JOIN bluechip_companies bc ON bc.company_id=sl.company_id LEFT JOIN share_holdings h ON h.company_id=sl.company_id GROUP BY sl.company_id, sl.issued_shares HAVING COALESCE(SUM(h.available_shares + h.reserved_shares),0) <> sl.issued_shares)`), 0, 'bluechip holdings must be conserved')
    assert.ok(scalar(db, 'SELECT COUNT(*) AS value FROM bluechip_events') >= 1, 'event row must exist')
    assert.ok(scalar(db, 'SELECT COUNT(*) AS value FROM market_candles') >= 1, 'candle row must exist')
    assert.ok(scalar(db, "SELECT COUNT(*) AS value FROM dividend_runs WHERE state = 'COMPLETED'") >= 1, 'at least one completed 15-day dividend run must exist')
    assert.equal(scalar(db, 'SELECT COUNT(*) AS value FROM (SELECT company_id, dividend_at, COUNT(*) n FROM dividend_runs GROUP BY company_id, dividend_at HAVING n > 1)'), 0, 'dividend runs are idempotent')
    assert.equal(scalar(db, 'SELECT COUNT(*) AS value FROM (SELECT company_id, trading_day, COUNT(*) n FROM market_candles GROUP BY company_id, trading_day HAVING n > 1)'), 0, 'candles are idempotent')
    console.log('BLUECHIP_LEDGER_PASS|ten|nonnegative|conservation|event|candle|dividend|idempotency')
  } finally { db.close() }
}

if (process.argv.includes('--ledger')) ledger()
else runGui().catch(error => { console.error(`BLUECHIP_GUI_FAIL|${error.stack ?? error.message}`); process.exitCode = 1 })
