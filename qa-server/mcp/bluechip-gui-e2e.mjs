/* Original-client release acceptance.  It deliberately only ever reaches the isolated 25566 Paper server. */
import assert from 'node:assert/strict'
import net from 'node:net'
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { DatabaseSync } from 'node:sqlite'
import mineflayer from 'mineflayer'

const HOST = '127.0.0.1', GAME_PORT = 25566, RCON_PORT = 25567
const timeoutMs = 25_000, participantWaitMs = Number(process.env.QA_PARTICIPANT_WAIT_MS ?? 390_000)
const phase = process.env.QA_CLOCK_PHASE ?? 'OPEN'
const player = phase === 'POSTCLOSE' || phase === 'POSTSEED' ? 'BluechipPOST' : `Bluechip${phase}`
const dbPath = '../plugins/BlockStock/blockeco.db', configPath = '../plugins/BlockStock/config.yml'
// The runner intentionally stops after OPEN and starts POSTCLOSE in a new Node process.
// This short-lived, per-harness marker carries the immutable database identity across that boundary.
const participantCloseMarkerPath = new URL('./bluechip-participant-close.qa.json', import.meta.url)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function configUuid(key) {
  const value = new RegExp(`^\\s*${key}:\\s*['\"]?([^'\"\\s]+)`, 'm').exec(readFileSync(configPath, 'utf8'))?.[1]
  assert.ok(value, `missing market.${key} in isolated QA config`)
  return value
}
const participantId = configUuid('participant-account-uuid'), makerId = configUuid('system-account-uuid')

function packet(id, type, body) { const payload = Buffer.from(body); const packet = Buffer.alloc(14 + payload.length); packet.writeInt32LE(10 + payload.length); packet.writeInt32LE(id, 4); packet.writeInt32LE(type, 8); payload.copy(packet, 12); return packet }
function rcon(command) {
  const password = /^rcon.password=(.*)$/m.exec(readFileSync('../server.properties', 'utf8'))?.[1]
  assert.ok(password, 'isolated QA RCON password missing')
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: HOST, port: RCON_PORT }); let authenticated = false; let buffer = Buffer.alloc(0)
    socket.setTimeout(timeoutMs, () => reject(new Error(`RCON timeout: ${command}`)))
    socket.on('connect', () => socket.write(packet(101, 3, password)))
    socket.on('data', data => {
      buffer = Buffer.concat([buffer, data]); if (buffer.length < 4 || buffer.length < buffer.readInt32LE(0) + 4) return
      const id = buffer.readInt32LE(4), body = buffer.subarray(12, buffer.readInt32LE(0) + 2).toString()
      if (!authenticated) { if (id === -1) return reject(new Error('isolated QA RCON auth failed')); authenticated = true; socket.write(packet(102, 2, command)); return }
      socket.end(); resolve(body)
    })
    socket.on('error', reject)
  })
}
function openDb() { return new DatabaseSync(dbPath, { readOnly: true }) }
function one(db, sql, ...values) { const row = db.prepare(sql).get(...values); return row && Object.values(row)[0] }
function snapshot(read) { const db = openDb(); try { return read(db) } finally { db.close() } }
async function waitForDb(label, check, limitMs = 15_000) {
  const deadline = Date.now() + limitMs; let last
  while (Date.now() < deadline) {
    try { const value = snapshot(check); if (value) return value; last = `observed ${value}` } catch (error) { last = error.message }
    await sleep(250)
  }
  throw new Error(`${label} did not become true within ${limitMs}ms (${last ?? 'no observation'})`)
}
function offlineUuid(username) { const bytes = createHash('md5').update(`OfflinePlayer:${username}`).digest(); bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80; const hex = bytes.toString('hex'); return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}` }
function title(window) { return typeof window?.title === 'string' ? window.title : JSON.stringify(window?.title ?? '') }
function itemText(item) { return JSON.stringify(item ?? '') }
function screenText(window) { return window.slots.map(itemText).join('\n') }
function material(item, expected) { return item?.name === expected }

function connect() {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: HOST, port: GAME_PORT, username: player, auth: 'offline', version: '1.21.4' })
    const timer = setTimeout(() => { bot.quit('spawn timeout'); reject(new Error('Mineflayer did not reach the isolated QA spawn')) }, timeoutMs)
    bot.once('spawn', () => { clearTimeout(timer); resolve(bot) })
    bot.once('kicked', reason => { clearTimeout(timer); reject(new Error(`kicked: ${JSON.stringify(reason)}`)) })
    bot.once('error', error => { clearTimeout(timer); reject(error) })
  })
}
async function waitWindow(bot, expected, sequence = -1) {
  if (bot.windowSequence > sequence && title(bot.currentWindow).includes(expected)) return bot.currentWindow
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { bot.off('windowOpen', opened); reject(new Error(`window timeout ${expected}; current=${title(bot.currentWindow)}`)) }, timeoutMs)
    const opened = window => { if (!title(window).includes(expected)) return; clearTimeout(timer); bot.off('windowOpen', opened); resolve(window) }
    bot.on('windowOpen', opened)
  })
}
async function clickAndWait(bot, slot, expected) { const next = waitWindow(bot, expected, bot.windowSequence); await sleep(150); try { await bot.clickWindow(slot, 0, 0) } catch { /* Paper cancels item movement by design. */ } const window = await next; await sleep(250); return window }
async function clickRefresh(bot, slot, expected) { return clickAndWait(bot, slot, expected) }
async function doubleConfirm(bot) {
  await sleep(150)
  try { await bot.clickWindow(29, 0, 0) } catch { /* confirmation may already have advanced */ }
  await sleep(40)
  try { await bot.clickWindow(29, 0, 0) } catch { /* second physical client click is intentional */ }
  await sleep(300)
}
async function input(bot, text, expected) { for (let i = 1; i <= text.length; i += 1) { bot._client.write('name_item', { name: text.slice(0, i) }); await sleep(45) } return clickAndWait(bot, 2, expected) }
async function home(bot) { bot.chat('/stock'); return waitWindow(bot, 'BlockStock', -1) }

function assertLocalizedMarket(window) {
  const top = window.slots.slice(0, 10), text = top.map(itemText).join('\n')
  assert.ok(text.includes('NOVA'), 'market must expose NOVA code')
  assert.ok(text.includes('星铸工业'), 'market must expose the localized NOVA name 星铸工业')
  for (const code of ['NOVA', 'AURORA', 'TERRAN', 'SKYLINE', 'IRONWOOD', 'LUMEN', 'RIVERMINT', 'ORBITAL', 'CINDER', 'VERDANT']) assert.ok(text.includes(code), `missing configured ticker ${code} in vanilla market GUI`)
}
function novaSlot(window) { return window.slots.slice(0, 10).findIndex(item => itemText(item).includes('NOVA') && itemText(item).includes('星铸工业')) }
function assertDepth(window) { for (const slot of [17, 18, 19, 20, 21]) assert.ok(itemText(window.slots[slot]).includes(`卖${slot - 16}`), `missing visible ask level ${slot - 16}`); for (const slot of [23, 24, 25, 26, 27]) assert.ok(itemText(window.slots[slot]).includes(`买${slot - 22}`), `missing visible bid level ${slot - 22}`) }
async function assertChartControls(bot, detail) {
  assert.ok(material(detail.slots[15], 'clock'), 'detail slot 15 must be the real clock / 分时线 control')
  assert.ok(material(detail.slots[16], 'enchanted_book'), 'detail slot 16 must be the real enchanted-book / 日K线 control')
  assert.ok(screenText(detail).includes('分时线'), 'detail initially presents 分时线')
  const intraday = await clickRefresh(bot, 15, 'BlockStock NOVA')
  assert.ok(screenText(intraday).includes('分时线'), 'clock click must render the intraday screen')
  const daily = await clickRefresh(bot, 16, 'BlockStock NOVA')
  assert.ok(screenText(daily).includes('日K线'), 'enchanted-book click must render the daily-K screen')
  assert.notEqual(screenText(intraday), screenText(daily), 'chart mode click must materially replace visible detail content')
  return daily
}
function cashMinor(db, id) { return one(db, 'SELECT COALESCE(available_minor + reserved_minor, 0) FROM securities_cash_accounts WHERE player_uuid=?', id) ?? 0 }
async function depositOnce(bot, playerId) {
  const before = snapshot(db => ({ operations: one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId), cash: cashMinor(db, playerId) }))
  await home(bot); await clickAndWait(bot, 13, '证券账户'); await clickAndWait(bot, 29, '输入金额'); await input(bot, '10000', '确认执行'); await doubleConfirm(bot)
  await waitForDb('one confirmed cash transfer', db => one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId) === before.operations + 1)
  const after = snapshot(db => ({ operations: one(db, 'SELECT COUNT(*) FROM securities_cash_operations WHERE player_uuid=?', playerId), cash: cashMinor(db, playerId) }))
  assert.equal(after.operations, before.operations + 1, 'repeated confirmation must create exactly one cash operation')
  assert.equal(after.cash, before.cash + 1_000_000, 'repeated confirmation must credit the transfer once, not twice')
}
async function buyOnce(bot, playerId) {
  const beforeOrders = snapshot(db => one(db, 'SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code=\'NOVA\'', playerId))
  await home(bot); const market = await clickAndWait(bot, 11, '市场'); assertLocalizedMarket(market)
  const slot = novaSlot(market); assert.ok(slot >= 0, 'localized NOVA must be selectable in the vanilla market GUI')
  let detail = await clickAndWait(bot, slot, 'BlockStock NOVA'); if (phase === 'OPEN') { assertDepth(detail); detail = await assertChartControls(bot, detail) }
  await clickAndWait(bot, 40, '输入正整数股数'); await input(bot, '10', '输入限价'); await input(bot, '12.00', '确认执行'); await doubleConfirm(bot)
  await waitForDb('one confirmed NOVA order', db => one(db, 'SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code=\'NOVA\'', playerId) === beforeOrders + 1)
  const afterOrders = snapshot(db => one(db, 'SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code=\'NOVA\'', playerId))
  assert.equal(afterOrders, beforeOrders + 1, 'repeated confirmation must create exactly one NOVA order')
}
function participantTradeCount(db) {
  return one(db, `SELECT COUNT(*) FROM stock_trades t JOIN stock_orders b ON b.id=t.buy_order_id JOIN stock_orders s ON s.id=t.sell_order_id WHERE (b.player_uuid=? AND s.player_uuid=?) OR (s.player_uuid=? AND b.player_uuid=?)`, participantId, makerId, participantId, makerId)
}
function participantResidualOrderCount(db) { return one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND state IN ('OPEN','PARTIALLY_FILLED')", participantId) }
function makerResidualOrderCount(db) { return one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND state IN ('OPEN','PARTIALLY_FILLED')", makerId) }
function participantResidualOrder(db) { return db.prepare("SELECT id, priority_sequence FROM stock_orders WHERE player_uuid=? AND state IN ('OPEN','PARTIALLY_FILLED') ORDER BY priority_sequence DESC, id DESC LIMIT 1").get(participantId) }
function clearParticipantCloseMarker() { if (existsSync(participantCloseMarkerPath)) unlinkSync(participantCloseMarkerPath) }
function persistParticipantCloseMarker(order) {
  assert.ok(Number.isSafeInteger(order?.id) && order.id > 0, 'QA close seed must identify one persisted participant order by immutable id')
  assert.ok(Number.isSafeInteger(order.priority_sequence) && order.priority_sequence > 0, 'QA close seed must record the participant order priority sequence')
  writeFileSync(participantCloseMarkerPath, JSON.stringify({ orderId: order.id, prioritySequence: order.priority_sequence }), { encoding: 'utf8', flag: 'wx' })
  return order.id
}
function readParticipantCloseMarker() {
  assert.ok(existsSync(participantCloseMarkerPath), 'POSTCLOSE must receive the OPEN participant-order marker')
  const marker = JSON.parse(readFileSync(participantCloseMarkerPath, 'utf8'))
  assert.ok(Number.isSafeInteger(marker?.orderId) && marker.orderId > 0, 'participant-order marker must contain an immutable positive order id')
  assert.ok(Number.isSafeInteger(marker.prioritySequence) && marker.prioritySequence > 0, 'participant-order marker must contain its priority sequence')
  return marker
}
async function assertParticipantTrade() {
  assert.notEqual(participantId, makerId, 'participant UUID must differ from the bluechip maker UUID')
  const observed = await waitForDb('open-session participant trade against the maker', db => participantTradeCount(db) > 0, participantWaitMs)
  assert.ok(observed > 0, 'scheduler must persist at least one participant-owned stock_trades row')
  const stats = snapshot(db => ({
    participantTrades: participantTradeCount(db),
    volume: one(db, `SELECT COALESCE(SUM(t.shares), 0) FROM stock_trades t JOIN stock_orders b ON b.id=t.buy_order_id JOIN stock_orders s ON s.id=t.sell_order_id WHERE (b.player_uuid=? AND s.player_uuid=?) OR (s.player_uuid=? AND b.player_uuid=?)`, participantId, makerId, participantId, makerId),
    quoteFirst: one(db, `SELECT COUNT(*) FROM stock_trades t JOIN stock_orders b ON b.id=t.buy_order_id JOIN stock_orders s ON s.id=t.sell_order_id WHERE ((b.player_uuid=? AND s.player_uuid=?) OR (s.player_uuid=? AND b.player_uuid=?)) AND CASE WHEN b.player_uuid=? THEN s.priority_sequence < b.priority_sequence ELSE b.priority_sequence < s.priority_sequence END`, participantId, makerId, participantId, makerId, participantId)
  }))
  assert.ok(stats.volume > 0, 'public market participant trade volume must be positive')
  assert.equal(stats.quoteFirst, stats.participantTrades, 'observable participant fills must use maker quotes created before their participant order')
}
async function seedParticipantCloseCancellation() {
  // This QA-only control cancels maker quotes but intentionally leaves the participant scheduler
  // running. Its next ordinary bounded order therefore rests in this same isolated database.
  assert.equal(snapshot(db => participantResidualOrderCount(db)), 0, 'QA close seed must begin without an earlier participant residual order')
  await rcon('stockadmin bluechip pause')
  await waitForDb('QA close seed maker-quote cancellation', db => makerResidualOrderCount(db) === 0)
  const order = await waitForDb('open-session QA participant residual order', participantResidualOrder, participantWaitMs)
  const orderId = persistParticipantCloseMarker(order)
  console.log(`BLUECHIP_QA_CLOSE_SEED|participant_order_id=${orderId}|priority_sequence=${order.priority_sequence}`)
  return orderId
}
function assertParticipantCloseCancellation() {
  const marker = readParticipantCloseMarker()
  const order = snapshot(db => db.prepare('SELECT player_uuid, state, priority_sequence FROM stock_orders WHERE id=?').get(marker.orderId))
  assert.ok(order, `participant order ${marker.orderId} seeded before close must still exist`)
  assert.equal(order.player_uuid, participantId, `marker order ${marker.orderId} must belong to the configured participant`)
  assert.equal(order.priority_sequence, marker.prioritySequence, `marker order ${marker.orderId} priority sequence must remain exact`)
  assert.equal(order.state, 'CANCELLED', `participant order ${marker.orderId} seeded before close must be CANCELLED`)
  return marker.orderId
}

async function gui() {
  if (phase === 'OPEN') clearParticipantCloseMarker()
  await rcon(`op ${player}`); await rcon(`eco give ${player} 1000000`)
  const bot = await connect(), playerId = offlineUuid(player); bot.windowSequence = 0
  bot.on('windowOpen', window => { bot.windowSequence += 1; console.log(`BLUECHIP_WINDOW|${title(window)}`) })
  bot.on('messagestr', message => console.log(`BLUECHIP_CHAT|${message}`))
  try {
    if (phase === 'OPEN') await waitForDb('PREOPEN buy order opening-session fill', db => one(db, "SELECT COUNT(*) FROM stock_trades t JOIN stock_orders o ON o.id=t.buy_order_id WHERE o.player_uuid=?", offlineUuid('BluechipPREOPEN')) > 0)
    const tradesBefore = snapshot(db => one(db, "SELECT COUNT(*) FROM stock_trades WHERE stock_code='NOVA'"))
    if (phase === 'POSTSEED') { await depositOnce(bot, playerId); console.log('BLUECHIP_GUI_PASS|POSTSEED|one-shot-deposit'); return }
    if (phase !== 'POSTCLOSE') await depositOnce(bot, playerId)
    await buyOnce(bot, playerId)
    await sleep(1_500)
    if (phase === 'OPEN') {
      assert.ok(snapshot(db => one(db, "SELECT COUNT(*) FROM stock_trades WHERE stock_code='NOVA'")) > tradesBefore, 'open GUI buy must receive a real trade')
      assert.ok(snapshot(db => one(db, "SELECT COALESCE(available_shares+reserved_shares,0) FROM share_holdings h JOIN stock_listings l ON l.company_id=h.company_id WHERE h.holder_uuid=? AND l.stock_code='NOVA'", playerId)) > 0, 'open GUI buy must credit actual NOVA shares')
      await assertParticipantTrade()
      await rcon('stockadmin bluechip event market 100'); await home(bot); const news = await clickAndWait(bot, 33, '市场快讯')
      assert.ok(screenText(news).includes('大盘') || screenText(news).includes('市场'), 'admin event must be visible in GUI news')
      const participantCloseOrderId = await seedParticipantCloseCancellation()
      console.log(`BLUECHIP_GUI_PASS|OPEN|localized|depth|chart-toggle|one-shot|participant-trade|positive-volume|news|participant-close-seeded=${participantCloseOrderId}`)
    } else {
      assert.equal(snapshot(db => one(db, "SELECT COUNT(*) FROM stock_orders WHERE player_uuid=? AND stock_code='NOVA' AND state IN ('OPEN','PARTIALLY_FILLED')", playerId)), 1, `${phase} order must remain queued outside matching hours`)
      assert.equal(snapshot(db => one(db, "SELECT COUNT(*) FROM stock_trades WHERE stock_code='NOVA'")), tradesBefore, `${phase} cannot match`)
      const participantCloseOrderId = phase === 'POSTCLOSE' ? assertParticipantCloseCancellation() : undefined
      console.log(`BLUECHIP_GUI_PASS|${phase}|queued${participantCloseOrderId ? `|participant-close-cancelled=${participantCloseOrderId}` : ''}`)
    }
  } finally { bot.quit('isolated QA complete') }
}
function ledger() {
  const db = openDb()
  try {
    assert.equal(one(db, 'SELECT COUNT(*) FROM bluechip_companies'), 10, 'ten bluechips')
    assert.equal(one(db, 'SELECT COUNT(*) FROM securities_cash_accounts WHERE available_minor<0 OR reserved_minor<0'), 0, 'negative securities cash')
    assert.equal(one(db, 'SELECT COUNT(*) FROM company_cash_accounts WHERE cash_minor<0'), 0, 'negative company treasury cash')
    assert.equal(one(db, 'SELECT COUNT(*) FROM share_holdings WHERE available_shares<0 OR reserved_shares<0'), 0, 'negative holdings')
    assert.equal(one(db, 'SELECT COUNT(*) FROM stock_orders WHERE original_shares<=0 OR remaining_shares<0 OR reserved_cash_minor<0 OR filled_notional_minor<0 OR fee_charged_minor<0'), 0, 'invalid persisted order balances')
    assert.equal(one(db, 'SELECT COUNT(*) FROM stock_trades WHERE shares<=0 OR price_minor<=0 OR notional_minor<=0 OR buyer_fee_minor<0'), 0, 'invalid persisted trade balances')
    assert.equal(one(db, "SELECT COUNT(*) FROM bluechip_bootstrap_funding WHERE state='COMPLETED'"), 1, 'bootstrap escrow funding')
    assert.equal(one(db, 'SELECT COUNT(*) FROM (SELECT sl.company_id FROM stock_listings sl JOIN bluechip_companies bc ON bc.company_id=sl.company_id LEFT JOIN share_holdings h ON h.company_id=sl.company_id GROUP BY sl.company_id,sl.issued_shares HAVING COALESCE(SUM(h.available_shares+h.reserved_shares),0)<>sl.issued_shares)'), 0, 'issued shares reconcile to holdings')
    assert.equal(one(db, "SELECT COUNT(*) FROM (SELECT company_id,trading_day,COUNT(*) n FROM market_candles GROUP BY company_id,trading_day HAVING n>1)"), 0, 'candle idempotency')
    assert.equal(one(db, "SELECT COUNT(*) FROM (SELECT company_id,dividend_at,COUNT(*) n FROM dividend_runs GROUP BY company_id,dividend_at HAVING n>1)"), 0, 'dividend idempotency')
    const participantCloseOrderId = assertParticipantCloseCancellation()
    assert.ok(participantTradeCount(db) > 0, 'participant must own persisted trade rows')
    assert.ok(one(db, `SELECT COALESCE(SUM(t.shares),0) FROM stock_trades t JOIN stock_orders b ON b.id=t.buy_order_id JOIN stock_orders s ON s.id=t.sell_order_id WHERE (b.player_uuid=? AND s.player_uuid=?) OR (s.player_uuid=? AND b.player_uuid=?)`, participantId, makerId, participantId, makerId) > 0, 'participant persisted volume must be positive')
    const company = one(db, 'SELECT COALESCE(SUM(cash_minor),0) FROM company_cash_accounts'), cash = one(db, 'SELECT COALESCE(SUM(available_minor+reserved_minor),0) FROM securities_cash_accounts'), fund = one(db, 'SELECT balance_minor FROM compensation_fund WHERE singleton=1'), liabilities = one(db, 'SELECT COALESCE(SUM(amount_minor),0) FROM escrow_ledger_entries'), actualEscrow = Number(process.env.QA_ESCROW_MINOR)
    assert.equal(liabilities, company + cash + fund, 'ledger liabilities reconcile to all authoritative cash balances')
    assert.ok(Number.isSafeInteger(actualEscrow), 'QA must provide the stopped Vault escrow balance in minor units')
    assert.equal(actualEscrow, liabilities, 'stopped Vault escrow must have zero reconciliation difference')
    console.log(`BLUECHIP_LEDGER_PASS|liabilities_minor=${liabilities}|vault_escrow_minor=${actualEscrow}|participant_volume=positive|participant_close_order_id=${participantCloseOrderId}`)
  } finally { db.close(); clearParticipantCloseMarker() }
}

if (process.argv.includes('--ledger')) ledger()
else gui().catch(error => { console.error(`BLUECHIP_GUI_FAIL|${error.stack ?? error.message}`); process.exitCode = 1 })
