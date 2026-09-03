/* Isolated Paper acceptance: an unmodified 1.21.4 protocol client reaches the public issuance market and returns. */
import assert from 'node:assert/strict'
import mineflayer from 'mineflayer'

const bot = mineflayer.createBot({host: '127.0.0.1', port: 25566, username: `Iss${Date.now().toString(36)}`, auth: 'offline', version: '1.21.4'})
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const title = window => typeof window?.title === 'string' ? window.title : JSON.stringify(window?.title ?? '')
function nextWindow(expected, before) {
  if (bot.windowSequence > before && title(bot.currentWindow).includes(expected)) return Promise.resolve(bot.currentWindow)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { bot.off('windowOpen', onOpen); reject(new Error(`Timed out waiting for ${expected}; current=${title(bot.currentWindow)}`)) }, 15_000)
    const onOpen = window => { if (!title(window).includes(expected)) return; clearTimeout(timer); bot.off('windowOpen', onOpen); resolve(window) }
    bot.on('windowOpen', onOpen)
  })
}
async function click(slot, expected) {
  const wait = nextWindow(expected, bot.windowSequence)
  await delay(120)
  try { await bot.clickWindow(slot, 0, 0) } catch { /* BlockStock correctly cancels inventory movement. */ }
  return wait
}
bot.once('spawn', async () => {
  bot.windowSequence = 0
  bot.on('windowOpen', () => { bot.windowSequence += 1 })
  try {
    bot.chat('/stock')
    await nextWindow('BlockStock 交易所', -1)
    await click(24, 'BlockStock 增发市场')
    assert.ok(title(bot.currentWindow).includes('BlockStock 增发市场'), 'public issuance page must be reachable from the exchange home')
    await click(49, 'BlockStock 交易所')
    console.log('ISSUANCE_PUBLIC_GUI_E2E_PASS|vanilla-client|public-entry|return-home')
  } catch (error) {
    console.error(`ISSUANCE_PUBLIC_GUI_E2E_FAIL|${error.stack ?? error.message}`)
    process.exitCode = 1
  } finally { bot.quit('issuance GUI QA complete') }
})
bot.once('error', error => { console.error(`ISSUANCE_PUBLIC_GUI_E2E_FAIL|${error.stack ?? error.message}`); process.exitCode = 1 })
