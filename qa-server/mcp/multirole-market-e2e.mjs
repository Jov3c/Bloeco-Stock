/*
 * Four simultaneous original-client roles against isolated Paper only:
 * admin, ordinary visitor, seller, and buyer.  No SQL writes fabricate
 * balances, holdings, orders, or trades; SQLite is read only for assertions.
 */
import assert from 'node:assert/strict'
import net from 'node:net'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { DatabaseSync } from 'node:sqlite'
import mineflayer from 'mineflayer'

const HOST = '127.0.0.1', GAME_PORT = 25566, RCON_PORT = 25567, timeoutMs = 25_000
const runId = Date.now().toString(36)
const ADMIN = `Adm${runId}`, VISITOR = `Vis${runId}`, SELLER = `Sel${runId}`, BUYER = `Buy${runId}`
const dbPath = '../plugins/BlockStock/blockeco.db'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const blockingSleep = ms => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)

function packet(id, type, body) { const bytes = Buffer.from(body); const result = Buffer.alloc(14 + bytes.length); result.writeInt32LE(10 + bytes.length); result.writeInt32LE(id, 4); result.writeInt32LE(type, 8); bytes.copy(result, 12); return result }
function rcon(command) {
  const password = /^rcon.password=(.*)$/m.exec(readFileSync('../server.properties', 'utf8'))?.[1]
  assert.ok(password, 'isolated QA RCON password missing')
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: HOST, port: RCON_PORT }); let authenticated = false; let buffer = Buffer.alloc(0)
    socket.setTimeout(timeoutMs, () => reject(new Error(`RCON timeout: ${command}`)))
    socket.on('connect', () => socket.write(packet(1, 3, password)))
    socket.on('data', data => {
      buffer = Buffer.concat([buffer, data]); if (buffer.length < 4 || buffer.length < buffer.readInt32LE(0) + 4) return
      const id = buffer.readInt32LE(4), body = buffer.subarray(12, buffer.readInt32LE(0) + 2).toString()
      if (!authenticated) { if (id === -1) return reject(new Error('isolated QA RCON auth failed')); authenticated = true; socket.write(packet(2, 2, command)); return }
      socket.end(); resolve(body)
    })
    socket.on('error', reject)
  })
}
function offlineUuid(name) { const bytes = createHash('md5').update(`OfflinePlayer:${name}`).digest(); bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80; const hex = bytes.toString('hex'); return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}` }
function title(window) { return typeof window?.title === 'string' ? window.title : JSON.stringify(window?.title ?? '') }
function itemText(item) { return JSON.stringify(item ?? '') }
function openDb() { const db = new DatabaseSync(dbPath, { readOnly: true }); db.exec('PRAGMA busy_timeout=5000'); return db }
function snapshot(read) {
  const until = Date.now() + 15_000; let failure
  do { let db; try { db = openDb(); return read(db) } catch (error) { if (!/database is locked|database is busy/i.test(error.message) || Date.now() >= until) throw error; failure = error } finally { db?.close() }; blockingSleep(100) } while (Date.now() < until)
  throw failure
}
function one(db, sql, ...values) { const row = db.prepare(sql).get(...values); return row && Object.values(row)[0] }
async function waitDb(label, check, limit = 20_000) { const deadline = Date.now() + limit; let observed; while (Date.now() < deadline) { try { observed = snapshot(check); if (observed) return observed } catch (error) { observed = error.message }; await sleep(200) } throw new Error(`${label} timed out (${observed ?? 'no observation'})`) }

function connect(name) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: HOST, port: GAME_PORT, username: name, auth: 'offline', version: '1.21.4' })
    const timer = setTimeout(() => { bot.quit('spawn timeout'); reject(new Error(`${name} did not reach the isolated spawn`)) }, timeoutMs)
    bot.once('spawn', () => { clearTimeout(timer); bot.windowSequence = 0; bot.on('windowOpen', () => { bot.windowSequence += 1 }); resolve(bot) })
    bot.once('kicked', reason => { clearTimeout(timer); reject(new Error(`${name} kicked: ${JSON.stringify(reason)}`)) })
    bot.once('error', reject)
  })
}
async function waitWindow(bot, expected, sequence = -1) {
  if (bot.windowSequence > sequence && title(bot.currentWindow).includes(expected)) return bot.currentWindow
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { bot.off('windowOpen', opened); reject(new Error(`${bot.username} window timeout ${expected}; current=${title(bot.currentWindow)}`)) }, timeoutMs)
    const opened = window => { if (!title(window).includes(expected)) return; clearTimeout(timer); bot.off('windowOpen', opened); resolve(window) }
    bot.on('windowOpen', opened)
  })
}
async function click(bot, slot, expected) { const next = waitWindow(bot, expected, bot.windowSequence); await sleep(150); try { await bot.clickWindow(slot, 0, 0) } catch { /* BlockStock cancels inventory movement. */ }; const window = await next; await sleep(200); return window }
async function input(bot, value, expected) {
  for (let index = 1; index <= value.length; index += 1) { bot._client.write('name_item', { name: value.slice(0, index) }); await sleep(40) }
  // Mineflayer can occasionally send the output-slot click before the server has
  // applied the last name_item packet.  Retry only while the same anvil screen is
  // still open; once accepted, AnvilGUI closes it so a duplicate cannot occur.
  for (let attempt = 0; attempt < 4; attempt += 1) {
    bot._client.write('name_item', { name: value })
    await sleep(150)
    try { await bot.clickWindow(2, 0, 0) } catch { /* server may reject an early container click */ }
    await sleep(350)
    if (title(bot.currentWindow).includes(expected)) return bot.currentWindow
  }
  throw new Error(`${bot.username} input did not open ${expected}; current=${title(bot.currentWindow)}`)
}
async function home(bot) { bot.chat('/stock'); return waitWindow(bot, 'BlockStock', -1) }
async function deposit(bot, playerId) {
  const before = snapshot(db => one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId))
  await home(bot); await click(bot, 13, '证券账户'); await click(bot, 29, '输入金额'); await input(bot, '10000', '确认执行'); await click(bot, 29, '提交结果')
  await waitDb(`${bot.username} deposit`, db => one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId) === before + 1)
}
async function nova(bot) { await home(bot); const market = await click(bot, 11, '市场'); const slot = market.slots.slice(0, 10).findIndex(item => itemText(item).includes('NOVA')); assert.ok(slot >= 0, 'NOVA must be visible to every trader'); return click(bot, slot, 'BlockStock NOVA') }
async function place(bot, side, shares, price) { await nova(bot); await click(bot, side === 'BUY' ? 40 : 41, '输入正整数股数'); await input(bot, String(shares).padStart(2, '0'), '输入限价'); await input(bot, price, '确认执行'); await click(bot, 29, '提交结果') }

async function main() {
  const bots = await Promise.all([ADMIN, VISITOR, SELLER, BUYER].map(connect))
  const [admin, visitor, seller, buyer] = bots
  const visitorMessages = []; visitor.on('messagestr', message => visitorMessages.push(message))
  try {
    console.log(`MULTIROLE_STAGE|connected|run=${runId}`)
    await rcon(`op ${ADMIN}`)
    for (const name of [SELLER, BUYER]) await rcon(`eco give ${name} 1000000`)
    await sleep(400)

    // The previous acceptance case deliberately pauses the market maker at close.
    // This scenario starts from an explicit, observable open-book precondition so
    // that its first acquisition is not coupled to the order tests that ran before it.
    admin.chat('/stockadmin bluechip resume')
    await waitDb('maker quotes resumed', db => one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid='00000000-0000-0000-0000-000000000099' AND state IN ('OPEN','PARTIALLY_FILLED')") > 0)

    // A player without a company must reach public IPO from the native home GUI.
    await home(visitor); await click(visitor, 22, '公开 IPO')
    console.log('MULTIROLE_STAGE|visitor-public-ipo')

    // Only the admin role may invoke market event controls.
    const eventsBefore = snapshot(db => one(db, 'SELECT COUNT(*) FROM bluechip_events'))
    admin.chat('/stockadmin bluechip event market 100')
    await waitDb('admin market event', db => one(db, 'SELECT COUNT(*) FROM bluechip_events') === eventsBefore + 1)
    visitor.chat('/stockadmin bluechip event market 100'); await sleep(500)
    assert.ok(visitorMessages.some(message => message.includes('没有权限')), 'ordinary visitor must be denied the bluechip admin command')
    console.log('MULTIROLE_STAGE|admin-and-permission')

    const sellerId = offlineUuid(SELLER), buyerId = offlineUuid(BUYER)
    await deposit(seller, sellerId)
    await place(seller, 'BUY', 10, '12.00')
    await waitDb('seller acquires real NOVA shares from the market', db => one(db, "SELECT COALESCE(available_shares+reserved_shares,0) FROM share_holdings h JOIN stock_listings l ON l.company_id=h.company_id WHERE h.holder_uuid=? AND l.stock_code='NOVA'", sellerId) >= 10)
    console.log('MULTIROLE_STAGE|seller-acquired')

    // Remove maker quotes only after the seller has acquired shares.  The subsequent
    // ordinary seller/buyer orders must therefore trade with each other, not a system quote.
    admin.chat('/stockadmin bluechip pause')
    await waitDb('maker quotes paused', db => one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid='00000000-0000-0000-0000-000000000099' AND state IN ('OPEN','PARTIALLY_FILLED')") === 0)
    await place(seller, 'SELL', 5, '12.00')
    await waitDb('seller resting order', db => one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code='NOVA' AND side='SELL' AND state IN ('OPEN','PARTIALLY_FILLED')", sellerId) > 0)
    console.log('MULTIROLE_STAGE|seller-resting-order')

    await deposit(buyer, buyerId)
    await place(buyer, 'BUY', 5, '12.00')
    const trade = await waitDb('buyer and seller real trade', db => db.prepare("SELECT t.shares,t.price_minor FROM stock_trades t JOIN stock_orders b ON b.id=t.buy_order_id JOIN stock_orders s ON s.id=t.sell_order_id WHERE b.player_uuid=? AND s.player_uuid=? AND t.stock_code='NOVA' ORDER BY t.occurred_at DESC LIMIT 1").get(buyerId, sellerId))
    assert.equal(trade.shares, 5, 'buyer must receive exactly the seller order quantity')
    assert.equal(trade.price_minor, 1200, 'trade must settle at the seller resting limit price')
    const balances = snapshot(db => ({
      buyer: one(db, "SELECT COALESCE(available_shares+reserved_shares,0) FROM share_holdings h JOIN stock_listings l ON l.company_id=h.company_id WHERE h.holder_uuid=? AND l.stock_code='NOVA'", buyerId),
      seller: one(db, "SELECT COALESCE(available_shares+reserved_shares,0) FROM share_holdings h JOIN stock_listings l ON l.company_id=h.company_id WHERE h.holder_uuid=? AND l.stock_code='NOVA'", sellerId),
      negativeCash: one(db, 'SELECT COUNT(*) FROM securities_cash_accounts WHERE available_minor<0 OR reserved_minor<0'),
      negativeShares: one(db, 'SELECT COUNT(*) FROM share_holdings WHERE available_shares<0 OR reserved_shares<0')
    }))
    assert.equal(balances.buyer, 5, 'buyer must own the transferred shares')
    assert.equal(balances.seller, 5, 'seller must retain its unlisted shares')
    assert.equal(balances.negativeCash, 0, 'multi-role trade must not create negative cash')
    assert.equal(balances.negativeShares, 0, 'multi-role trade must not create negative holdings')
    console.log('MULTIROLE_E2E_PASS|roles=admin,visitor,seller,buyer|public-ipo-gui|permission-denied|real-peer-trade|nonnegative-ledger')
  } finally { for (const bot of bots) bot.quit('multi-role QA complete') }
}

main().catch(error => { console.error(`MULTIROLE_E2E_FAIL|${error.stack ?? error.message}`); process.exitCode = 1 })
