/* A vanilla-client negative IPO case: announced shares cannot debit a buyer before opening. */
import assert from 'node:assert/strict'
import net from 'node:net'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { DatabaseSync } from 'node:sqlite'
import mineflayer from 'mineflayer'

const HOST = '127.0.0.1', GAME_PORT = 25566, RCON_PORT = 25567, timeoutMs = 20_000
const BUYER = `IpoBuy${Date.now().toString(36)}`
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
function packet(id, type, body) { const b = Buffer.from(body), out = Buffer.alloc(14 + b.length); out.writeInt32LE(10 + b.length); out.writeInt32LE(id, 4); out.writeInt32LE(type, 8); b.copy(out, 12); return out }
function rcon(command) { const pw = /^rcon.password=(.*)$/m.exec(readFileSync('../server.properties', 'utf8'))?.[1]; assert.ok(pw, 'QA RCON password missing'); return new Promise((resolve, reject) => { const socket = net.createConnection({ host: HOST, port: RCON_PORT }); let auth = false; socket.setTimeout(timeoutMs, () => reject(new Error(`RCON timeout: ${command}`))); socket.on('connect', () => socket.write(packet(1, 3, pw))); socket.on('data', data => { const id = data.readInt32LE(4); if (!auth) { if (id === -1) return reject(new Error('RCON auth failed')); auth = true; socket.write(packet(2, 2, command)); return } socket.end(); resolve() }); socket.on('error', reject) }) }
function offlineUuid(name) { const bytes = createHash('md5').update(`OfflinePlayer:${name}`).digest(); bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128; const hex = bytes.toString('hex'); return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}` }
function dbRead(read) { const db = new DatabaseSync('../plugins/BlockStock/blockeco.db', { readOnly: true }); try { return read(db) } finally { db.close() } }
function one(db, sql, ...params) { const row = db.prepare(sql).get(...params); return row && Object.values(row)[0] }
function title(window) { return typeof window?.title === 'string' ? window.title : JSON.stringify(window?.title ?? '') }
function itemText(item) { return JSON.stringify(item ?? '') }
function connect() { return new Promise((resolve, reject) => { const bot = mineflayer.createBot({ host: HOST, port: GAME_PORT, username: BUYER, auth: 'offline', version: '1.21.4' }); const timer = setTimeout(() => reject(new Error('IPO buyer spawn timeout')), timeoutMs); bot.once('spawn', () => { clearTimeout(timer); bot.windowSequence = 0; bot.on('windowOpen', () => { bot.windowSequence += 1 }); resolve(bot) }); bot.once('error', reject); bot.once('kicked', reason => reject(new Error(`IPO buyer kicked: ${JSON.stringify(reason)}`))) }) }
async function waitWindow(bot, expected, sequence = -1) { if (bot.windowSequence > sequence && title(bot.currentWindow).includes(expected)) return bot.currentWindow; return new Promise((resolve, reject) => { const timer = setTimeout(() => { bot.off('windowOpen', opened); reject(new Error(`window timeout ${expected}; current=${title(bot.currentWindow)}`)) }, timeoutMs); const opened = window => { if (!title(window).includes(expected)) return; clearTimeout(timer); bot.off('windowOpen', opened); resolve(window) }; bot.on('windowOpen', opened) }) }
async function click(bot, slot, expected) { const next = waitWindow(bot, expected, bot.windowSequence); await sleep(150); try { await bot.clickWindow(slot, 0, 0) } catch { } const opened = await next; await sleep(150); return opened }
async function input(bot, value, expected) { for (let index = 1; index <= value.length; index += 1) { bot._client.write('name_item', { name: value.slice(0, index) }); await sleep(60) }; return click(bot, 2, expected) }
async function doubleConfirm(bot, expected) { const next = waitWindow(bot, expected, bot.windowSequence); await sleep(150); try { await bot.clickWindow(29, 0, 0) } catch { }; await sleep(40); try { await bot.clickWindow(29, 0, 0) } catch { }; return next }

async function main() {
  const announced = dbRead(db => db.prepare("SELECT o.id,c.display_name FROM primary_offerings o JOIN companies c ON c.id=o.company_id WHERE o.state='ANNOUNCED' ORDER BY o.announced_at DESC LIMIT 1").get())
  assert.ok(announced, 'QA requires an announced IPO from founder GUI acceptance')
  const bot = await connect(), buyerId = offlineUuid(BUYER)
  try {
    await rcon(`eco give ${BUYER} 1000000`); await sleep(250)
    const before = dbRead(db => one(db, 'SELECT COUNT(*) FROM treasury_operations WHERE player_uuid=?', buyerId))
    bot.chat('/stock'); await waitWindow(bot, 'BlockStock', -1)
    const list = await click(bot, 22, '公开 IPO')
    const offerSlot = list.slots.slice(0, 45).findIndex(item => itemText(item).includes(announced.display_name))
    assert.ok(offerSlot >= 0, `announced IPO ${announced.display_name} not public`)
    await click(bot, offerSlot, 'IPO 详情')
    await click(bot, 42, '输入认购股数')
    await input(bot, '01', '确认 IPO 操作')
    await doubleConfirm(bot, '公开 IPO')
    const after = dbRead(db => one(db, 'SELECT COUNT(*) FROM treasury_operations WHERE player_uuid=?', buyerId))
    assert.equal(after, before, 'pre-open double confirmation must not create an escrow operation')
    console.log('IPO_PREOPEN_E2E_PASS|public-gui|not-open-no-debit|double-confirm-safe')
  } finally { bot.quit('IPO pre-open QA complete') }
}
main().catch(error => { console.error(`IPO_PREOPEN_E2E_FAIL|${error.stack ?? error.message}`); process.exitCode = 1 })
