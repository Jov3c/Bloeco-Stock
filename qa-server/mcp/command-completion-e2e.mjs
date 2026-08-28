/* Proves Paper sends the same permission-aware command suggestions a vanilla client sees. */
import assert from 'node:assert/strict'
import net from 'node:net'
import { readFileSync } from 'node:fs'
import mineflayer from 'mineflayer'

const HOST = '127.0.0.1', GAME_PORT = 25566, RCON_PORT = 25567, timeoutMs = 15_000
const run = Date.now().toString(36), ADMIN = `TabAdm${run}`, PLAYER = `TabUsr${run}`
function packet(id, type, body) { const b = Buffer.from(body), out = Buffer.alloc(14 + b.length); out.writeInt32LE(10 + b.length); out.writeInt32LE(id, 4); out.writeInt32LE(type, 8); b.copy(out, 12); return out }
function rcon(command) { const password = /^rcon.password=(.*)$/m.exec(readFileSync('../server.properties', 'utf8'))?.[1]; assert.ok(password, 'QA RCON password missing'); return new Promise((resolve, reject) => { const socket = net.createConnection({ host: HOST, port: RCON_PORT }); let auth = false; socket.setTimeout(timeoutMs, () => reject(new Error(`RCON timeout: ${command}`))); socket.on('connect', () => socket.write(packet(1, 3, password))); socket.on('data', data => { const id = data.readInt32LE(4); if (!auth) { if (id === -1) return reject(new Error('RCON auth failed')); auth = true; socket.write(packet(2, 2, command)); return } socket.end(); resolve() }); socket.on('error', reject) }) }
function connect(username) { return new Promise((resolve, reject) => { const bot = mineflayer.createBot({ host: HOST, port: GAME_PORT, username, auth: 'offline', version: '1.21.4' }); const timer = setTimeout(() => reject(new Error(`${username} spawn timeout`)), timeoutMs); bot.once('spawn', () => { clearTimeout(timer); resolve(bot) }); bot.once('error', reject); bot.once('kicked', reason => reject(new Error(`${username} kicked: ${JSON.stringify(reason)}`))) }) }
async function completionOrHidden(bot, text) {
  try { return await bot.tabComplete(text, true) }
  catch (error) {
    // Paper omits the command tree branch entirely for a sender without the
    // root permission, so a vanilla client receives no completion packet.
    if (String(error.message).includes('tab_complete did not fire')) return []
    throw error
  }
}
function suggestions(matches) { return matches.map(value => typeof value === 'string' ? value : value.match) }

async function main() {
  const [admin, player] = await Promise.all([connect(ADMIN), connect(PLAYER)])
  try {
    await rcon(`op ${ADMIN}`)
    // 1.21 tab-complete replies are not correlated with an id.  Serial requests
    // avoid a client-protocol race that would otherwise consume the wrong reply.
    const stock = suggestions(await admin.tabComplete('/stock ', true))
    const company = suggestions(await admin.tabComplete('/company ', true))
    const adminCommand = suggestions(await admin.tabComplete('/stockadmin bluechip ', true))
    const playerAdmin = suggestions(await completionOrHidden(player, '/stockadmin '))
    assert.ok(stock.includes('market') && stock.includes('buy') && stock.includes('sell'), `stock suggestions missing trade options: ${stock}`)
    assert.ok(company.includes('create') && company.includes('asset') && company.includes('ipo'), `company suggestions missing player options: ${company}`)
    assert.deepEqual([...adminCommand].sort(), ['event', 'fund', 'init', 'pause', 'resume'], `admin bluechip options changed: ${adminCommand}`)
    assert.ok(!playerAdmin.includes('bluechip'), `ordinary player leaked admin option: ${playerAdmin}`)
    console.log('COMMAND_COMPLETION_E2E_PASS|vanilla-tab|player-options|admin-isolation')
  } finally { admin.quit('completion QA complete'); player.quit('completion QA complete') }
}
main().catch(error => { console.error(`COMMAND_COMPLETION_E2E_FAIL|${error.stack ?? error.message}`); process.exitCode = 1 })
