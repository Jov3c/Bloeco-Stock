import net from 'node:net'
import { readFileSync } from 'node:fs'
import mineflayer from 'mineflayer'

const HOST = '127.0.0.1'
const GAME_PORT = 25566
const RCON_PORT = 25567
// Keep the Bot name protocol-valid and unique so this flow is safe to rerun
// against the same isolated database.
const FOUNDER = `Founder${Date.now().toString(36)}`
const COMPANY_NAME = `公司${Date.now().toString(36)}`
const timeoutMs = 15000
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))

function packet(id, type, body) {
  const bodyBuffer = Buffer.from(body, 'utf8')
  const result = Buffer.alloc(14 + bodyBuffer.length)
  result.writeInt32LE(10 + bodyBuffer.length, 0)
  result.writeInt32LE(id, 4)
  result.writeInt32LE(type, 8)
  bodyBuffer.copy(result, 12)
  return result
}

function rcon(command) {
  const properties = readFileSync('../server.properties', 'utf8')
  const password = /^rcon.password=(.*)$/m.exec(properties)?.[1]
  if (!password) throw new Error('QA RCON password missing')
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: HOST, port: RCON_PORT })
    let buffer = Buffer.alloc(0)
    let authenticated = false
    socket.setTimeout(timeoutMs, () => reject(new Error(`RCON timed out: ${command}`)))
    socket.on('connect', () => socket.write(packet(1, 3, password)))
    socket.on('data', data => {
      buffer = Buffer.concat([buffer, data])
      while (buffer.length >= 4) {
        const length = buffer.readInt32LE(0)
        if (buffer.length < length + 4) return
        const id = buffer.readInt32LE(4)
        const body = buffer.subarray(12, length + 2).toString('utf8')
        buffer = buffer.subarray(length + 4)
        if (!authenticated) {
          if (id === -1) return reject(new Error('RCON authentication failed'))
          authenticated = true
          socket.write(packet(2, 2, command))
        } else {
          socket.end()
          return resolve(body)
        }
      }
    })
    socket.on('error', reject)
  })
}

function title(window) {
  const value = window?.title
  if (typeof value === 'string') return value
  return JSON.stringify(value ?? '')
}

function matches(window, expected) {
  return title(window).includes(expected)
}

function connect() {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: HOST, port: GAME_PORT, username: FOUNDER, auth: 'offline', version: '1.21.4' })
    bot.once('spawn', () => resolve(bot))
    bot.once('kicked', reason => reject(new Error(`Founder bot kicked: ${JSON.stringify(reason)}`)))
    bot.once('error', reject)
  })
}

async function waitForWindow(bot, expected, sequence) {
  if (bot.windowSequence > sequence && matches(bot.currentWindow, expected)) return bot.currentWindow
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.off('windowOpen', opened)
      reject(new Error(`Timed out waiting for “${expected}”; current title is “${title(bot.currentWindow)}”`))
    }, timeoutMs)
    const opened = window => {
      if (!matches(window, expected)) return
      clearTimeout(timer)
      bot.off('windowOpen', opened)
      resolve(window)
    }
    bot.on('windowOpen', opened)
  })
}

async function clickAndWait(bot, slot, expected) {
  const sequence = bot.windowSequence
  const next = waitForWindow(bot, expected, sequence)
  // Paper schedules a replacement inventory for the next tick.  Let Mineflayer receive the
  // complete previous window before issuing the click, then wait for the replacement to settle.
  await delay(150)
  const item = bot.currentWindow?.slots?.[slot]
  console.log(`CLICK|title=${title(bot.currentWindow)}|slot=${slot}|item=${JSON.stringify(item?.nbt ?? null)}`)
  try {
    await bot.clickWindow(slot, 0, 0)
  } catch (error) {
    // BlockStock intentionally cancels inventory mutations; the ensuing window is the assertion.
    console.log(`CLICK_CANCELLED|slot=${slot}|${error.message}`)
  }
  const window = await next
  await delay(150)
  return window
}

async function sendAnvilText(bot, value, expected) {
  if (bot.supportFeature('useMCItemName')) {
    bot._client.registerChannel('MC|ItemName', 'string')
    for (let index = 1; index <= value.length; index += 1) {
      bot._client.writeChannel('MC|ItemName', value.slice(0, index))
      await delay(60)
    }
  } else {
    for (let index = 1; index <= value.length; index += 1) {
      bot._client.write('name_item', { name: value.slice(0, index) })
      await delay(60)
    }
  }
  await delay(300)
  const populated = (bot.currentWindow?.slots ?? []).map((item, slot) => item ? ({ slot, name: item.displayName, type: item.type }) : null).filter(Boolean)
  console.log(`ANVIL_RESULT|input=${value}|${JSON.stringify(populated)}`)
  return clickAndWait(bot, 2, expected)
}

async function main() {
  const bot = await connect()
  bot.windowSequence = 0
  bot.on('windowOpen', () => { bot.windowSequence += 1 })
  try {
    // Essentials creates an offline-mode account at login.  Grant after that
    // point so a clean isolated server cannot silently discard QA funding.
    await rcon(`op ${FOUNDER}`)
    await rcon(`eco give ${FOUNDER} 1000000`)
    await delay(300)

    bot.chat('/stock')
    await waitForWindow(bot, 'BlockStock', -1)
    await clickAndWait(bot, 20, '公司中心')
    await clickAndWait(bot, 22, '输入公司名称')
    await sendAnvilText(bot, COMPANY_NAME, '输入实缴资本')
    await sendAnvilText(bot, '10000', '选择分红比例')
    await clickAndWait(bot, 20, '确认执行')
    await clickAndWait(bot, 29, '公司中心')

    await clickAndWait(bot, 29, '资产管理')
    await clickAndWait(bot, 45, '输入资产名称')
    await sendAnvilText(bot, 'QA Mine', '确认执行')
    await clickAndWait(bot, 29, '资产管理')
    await clickAndWait(bot, 0, '确认执行')
    await clickAndWait(bot, 29, '资产管理')

    await clickAndWait(bot, 49, '公司中心')
    await clickAndWait(bot, 31, 'IPO 管理')
    await clickAndWait(bot, 31, '输入募资目标')
    await sendAnvilText(bot, '10000', '输入每股发行价')
    await sendAnvilText(bot, '100', '确认 IPO 操作')
    await clickAndWait(bot, 29, 'IPO 管理')
    console.log(`FOUNDER_GUI_E2E_PASS|founder=${FOUNDER}|company-created|asset-bound|ipo-announced`)
  } finally {
    bot.quit('Founder QA flow complete')
  }
}

main().catch(error => {
  console.error(`FOUNDER_GUI_E2E_FAIL|${error.stack ?? error.message}`)
  process.exitCode = 1
})
