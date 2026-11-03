// /heartshop with real money: needs Vault (or VaultUnlocked), EssentialsX for the economy, and
// LuckPerms for the rank limit, on the test server.
const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
function uuid(name) {
  const h = require('crypto').createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const balance = async n => Number(((await rcon(`balance ${n}`)).replace(/,/g, '').match(/\$([\d.]+)/) || [0, -1])[1])

const write = dbWrite

// Run again with "after" once the server has restarted: what was bought today still counts.
async function afterRestart() {
  await enter('Alice'); await rcon('lifesteal config heart-shop.enabled on'); await rcon('eco set Alice 30000')
  await say('Alice', '/heartshop buy heart')
  check('a restart keeps the daily limit', saw('Alice', 'You can buy 2 item(s) a day; 0 left today'), true)
  check('so nothing is charged', await balance('Alice'), 30000)
  await rcon('lifesteal config heart-shop.enabled off')
}

async function main() {
  if (process.argv[2] === 'after') {
    await afterRestart()
    for (const b of Object.values(bots)) { try { b.quit() } catch {} }
    await sleep(500)
    console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
    process.exit(failures === 0 ? 0 : 1)
  }
  write("DELETE FROM server_state WHERE key LIKE 'heartshop.bought.%'")
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`clear ${n}`)
  }
  await rcon('lifesteal config heart-shop.enabled on')
  await rcon('eco set Alice 30000'); await rcon('eco set Bob 100')
  await sleep(1000)

  await say('Alice', '/heartshop')
  check('the shop lists its prices in the server\'s money', saw('Alice', 'heart - $5,000'), true)
  check('and the daily limit', saw('Alice', 'up to 2 item(s) a day'), true)

  await say('Alice', '/heartshop buy heart')
  check('buying a Heart takes the money', await balance('Alice'), 25000)
  check('and gives the Heart', await count('Alice', 'minecraft:red_dye'), 1)
  check('and says so', saw('Alice', 'You bought 1 heart(s) for $5,000'), true)

  await say('Alice', '/heartshop buy fragment 2')
  check('the daily limit counts everything bought', saw('Alice', 'You can buy 2 item(s) a day; 1 left today'), true)
  check('and nothing is charged', await balance('Alice'), 25000)
  await say('Alice', '/heartshop buy fragment')
  check('up to the limit is fine', await count('Alice', 'minecraft:pink_dye'), 1)
  check('at the fragment price', await balance('Alice'), 24000)

  await say('Bob', '/heartshop buy heart')
  check('too little money buys nothing', saw('Bob', "That costs $5,000, and you don't have enough"), true)
  check('and takes nothing', await balance('Bob'), 100)

  // A rank's own limit.
  await rcon(`lp user ${uuid('Bob')} permission set lifestealplus.shop-limit.5 true`); await sleep(800)
  await rcon('eco set Bob 100000')
  await say('Bob', '/heartshop buy heart 3')
  check('a rank\'s limit replaces the daily limit', await count('Bob', 'minecraft:red_dye'), 3)
  await say('Bob', '/heartshop buy charm')
  check('charms are sold too', await count('Bob', 'minecraft:heart_of_the_sea'), 1)
  check('at their price', await balance('Bob'), 100000 - 3 * 5000 - 20000)

  await rcon('lifesteal config heart-shop.charm-price 0')
  await say('Bob', '/heartshop buy charm')
  check('a price of 0 means not for sale', saw('Bob', "charms aren't for sale here"), true)

  await rcon('lifesteal config heart-shop.enabled off')
  await say('Bob', '/heartshop buy heart')
  check('switched off, the shop is closed', await count('Bob', 'minecraft:red_dye'), 3)

  await rcon('lifesteal config heart-shop.charm-price 20000')
  await rcon(`lp user ${uuid('Bob')} permission unset lifestealplus.shop-limit.5`)
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'ommand failed' in l or 'missing message' in l or 'LifestealPlus' in l and ('SEVERE' in l or 'ERROR' in l)))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  for (const b of Object.values(bots)) { try { b.quit() } catch {} }
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
