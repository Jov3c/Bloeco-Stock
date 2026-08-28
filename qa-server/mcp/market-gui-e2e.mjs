/* Stand-alone native market smoke.  Fixed loopback ports deliberately exclude a user's normal 25565 server. */
import assert from 'node:assert/strict'
import net from 'node:net'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { DatabaseSync } from 'node:sqlite'
import mineflayer from 'mineflayer'

// Minecraft's login protocol limits player names to 16 characters.
const HOST = '127.0.0.1', GAME_PORT = 25566, RCON_PORT = 25567, INVESTOR = 'MarketInvestor', timeoutMs = 25_000
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms)), dbPath = '../plugins/BlockStock/blockeco.db'
function packet(id, type, body) { const payload = Buffer.from(body); const out = Buffer.alloc(14 + payload.length); out.writeInt32LE(10 + payload.length); out.writeInt32LE(id, 4); out.writeInt32LE(type, 8); payload.copy(out, 12); return out }
function rcon(command) { const password = /^rcon.password=(.*)$/m.exec(readFileSync('../server.properties', 'utf8'))?.[1]; assert.ok(password, 'isolated QA RCON password missing'); return new Promise((resolve, reject) => { const socket = net.createConnection({ host: HOST, port: RCON_PORT }); let auth = false, buffer = Buffer.alloc(0); socket.setTimeout(timeoutMs, () => reject(new Error(`RCON timeout: ${command}`))); socket.on('connect', () => socket.write(packet(1, 3, password))); socket.on('data', data => { buffer = Buffer.concat([buffer, data]); if (buffer.length < 4 || buffer.length < buffer.readInt32LE(0) + 4) return; const id = buffer.readInt32LE(4); if (!auth) { if (id === -1) return reject(new Error('RCON auth failed')); auth = true; socket.write(packet(2, 2, command)); return } socket.end(); resolve() }); socket.on('error', reject) }) }
function offlineUuid(username) { const bytes = createHash('md5').update(`OfflinePlayer:${username}`).digest(); bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80; const hex = bytes.toString('hex'); return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}` }
function title(window) { return typeof window?.title === 'string' ? window.title : JSON.stringify(window?.title ?? '') }
function itemText(item) { return JSON.stringify(item ?? '') }
function screenText(window) { return window.slots.map(itemText).join('\n') }
function openDb() { return new DatabaseSync(dbPath, { readOnly: true }) }
function one(db, sql, ...values) { const row = db.prepare(sql).get(...values); return row && Object.values(row)[0] }
function readDb(read) { const db = openDb(); try { return read(db) } finally { db.close() } }
async function waitDb(label, check) { const deadline = Date.now() + 15_000; while (Date.now() < deadline) { if (readDb(check)) return; await sleep(250) } throw new Error(`${label} did not complete`) }
function connect() { return new Promise((resolve, reject) => { const bot = mineflayer.createBot({ host: HOST, port: GAME_PORT, username: INVESTOR, auth: 'offline', version: '1.21.4' }); const timer = setTimeout(() => { bot.quit('timeout'); reject(new Error('Mineflayer did not reach isolated QA spawn')) }, timeoutMs); bot.once('spawn', () => { clearTimeout(timer); resolve(bot) }); bot.once('kicked', reason => { clearTimeout(timer); reject(new Error(`kicked: ${JSON.stringify(reason)}`)) }); bot.once('error', reject) }) }
async function window(bot, expected, sequence = -1) { if (bot.windowSequence > sequence && title(bot.currentWindow).includes(expected)) return bot.currentWindow; return new Promise((resolve, reject) => { const timer = setTimeout(() => { bot.off('windowOpen', opened); reject(new Error(`window timeout ${expected}; current=${title(bot.currentWindow)}`)) }, timeoutMs); const opened = value => { if (!title(value).includes(expected)) return; clearTimeout(timer); bot.off('windowOpen', opened); resolve(value) }; bot.on('windowOpen', opened) }) }
async function click(bot, slot, expected) { const next = window(bot, expected, bot.windowSequence); await sleep(150); try { await bot.clickWindow(slot, 0, 0) } catch { /* inventory movement is intentionally cancelled */ } const opened = await next; await sleep(200); return opened }
async function input(bot, text, expected) { for (let i = 1; i <= text.length; i += 1) { bot._client.write('name_item', { name: text.slice(0, i) }); await sleep(45) } return click(bot, 2, expected) }
async function doubleConfirm(bot) { try { await bot.clickWindow(29, 0, 0) } catch {} await sleep(40); try { await bot.clickWindow(29, 0, 0) } catch {} }
async function home(bot) { bot.chat('/stock'); return window(bot, 'BlockStock', -1) }

async function main() {
  // Essentials creates an offline-mode account on first login.  Funding before
  // that login is silently discarded, so connect before issuing the QA grant.
  const bot = await connect(), playerId = offlineUuid(INVESTOR); bot.windowSequence = 0; bot.on('windowOpen', () => { bot.windowSequence += 1 })
  await rcon(`op ${INVESTOR}`); await rcon(`eco give ${INVESTOR} 1000000`); await sleep(300)
  try {
    const initial = readDb(db => ({ operations: one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId), cash: one(db, 'SELECT COALESCE(available_minor+reserved_minor,0) FROM securities_cash_accounts WHERE player_uuid=?', playerId) ?? 0 }))
    await home(bot); await click(bot, 13, '证券账户'); await click(bot, 29, '输入金额'); await input(bot, '10000', '确认执行'); await doubleConfirm(bot)
    await waitDb('one market smoke deposit', db => one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId) === initial.operations + 1)
    const deposited = readDb(db => ({ operations: one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId), cash: one(db, 'SELECT COALESCE(available_minor+reserved_minor,0) FROM securities_cash_accounts WHERE player_uuid=?', playerId) }))
    assert.equal(deposited.operations, initial.operations + 1, 'two confirm clicks must create one cash operation')
    assert.equal(deposited.cash, initial.cash + 1_000_000, 'two confirm clicks must credit cash once')

    await home(bot); const market = await click(bot, 11, '市场'), marketText = screenText(market)
    assert.ok(marketText.includes('星铸工业') && marketText.includes('NOVA'), 'market must show localized 星铸工业 and NOVA together')
    const novaSlot = market.slots.slice(0, 10).findIndex(item => itemText(item).includes('星铸工业') && itemText(item).includes('NOVA')); assert.ok(novaSlot >= 0, 'localized NOVA must be selectable')
    let detail = await click(bot, novaSlot, 'BlockStock NOVA')
    assert.equal(detail.slots[15]?.name, 'clock', 'slot 15 must be the intraday clock control'); assert.equal(detail.slots[16]?.name, 'enchanted_book', 'slot 16 must be the daily-K enchanted-book control')
    detail = await click(bot, 15, 'BlockStock NOVA'); assert.ok(screenText(detail).includes('分时线'), 'clock click must visibly render 分时线')
    const daily = await click(bot, 16, 'BlockStock NOVA'); assert.ok(screenText(daily).includes('日K线'), 'enchanted-book click must visibly render 日K线'); assert.notEqual(screenText(detail), screenText(daily), 'chart mode must materially alter the detail screen')

    const ordersBefore = readDb(db => one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code='NOVA'", playerId))
    await click(bot, 40, '输入正整数股数'); await input(bot, '10', '输入限价'); await input(bot, '12.00', '确认执行'); await doubleConfirm(bot)
    await waitDb('one market smoke order', db => one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code='NOVA'", playerId) === ordersBefore + 1)
    assert.equal(readDb(db => one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code='NOVA'", playerId)), ordersBefore + 1, 'two confirm clicks must create one NOVA order')
    console.log('MARKET_GUI_E2E_PASS|isolated-25566|localized|chart-toggle|one-shot-deposit|one-shot-order')
  } finally { bot.quit('native market QA complete') }
}
main().catch(error => { console.error(`MARKET_GUI_E2E_FAIL|${error.stack ?? error.message}`); process.exitCode = 1 })
