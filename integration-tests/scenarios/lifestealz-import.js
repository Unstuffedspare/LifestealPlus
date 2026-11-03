const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
const fs = require('fs')
const DIM = 'minecraft:lifesteal_purgatory'
async function dimension(name) { return ((await rcon(`data get entity ${name} Dimension`)).match(/"([^"]+)"/) || [])[1] }
const bannedFile = path.join(SERVER, 'banned-players.json')
const isBanned = n => JSON.parse(fs.readFileSync(bannedFile, 'utf8')).some(b => b.name === n)

async function main() {
  for (const n of ['Alice', 'Cara']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  await rcon('lifesteal set Bob 10')
  await rcon('ban Bob LifeStealZ elimination')
  await rcon('op Alice')
  await sleep(1500)
  check('Bob starts banned, as LifeStealZ leaves eliminated players', isBanned('Bob'), true)

  // Nothing pending yet; a path outside LifeStealZ's folder is refused.
  await say('Alice', '/lifesteal import confirm')
  check('confirm with nothing pending', saw('Alice', 'Nothing to confirm'), true)
  await say('Alice', '/lifesteal import lifestealz ..')
  check('a path outside plugins/LifeStealZ is refused', saw('Alice', "Can't find"), true)

  // Preview from userData.db
  await say('Alice', '/lifesteal import lifestealz', 2500)
  check('preview counts players', saw('Alice', 'Ready to import 4 players from LifeStealZ'), true)
  check('preview counts alive and eliminated', saw('Alice', '3 with hearts, 1 eliminated'), true)
  check('preview counts players already here', saw('Alice', '3 already on this server'), true)
  check('preview names the capped player', chat.Alice.some(m => m.includes("more than this server's cap") && m.includes('Cara')), true)
  check('preview warns about bans', saw('Alice', '1 eliminated player(s) are banned'), true)
  check('nothing changed yet', [hearts('Alice'), hearts('Cara'), hearts('Dora')], [10, 10, undefined])

  await say('Alice', '/lifesteal import confirm unban', 3000)
  check('import reports what it did', saw('Alice', 'Imported 4 players from LifeStealZ: 1 new here, 3 updated'), true)
  check('and the unbans', saw('Alice', 'Unbanned 1 eliminated player(s)'), true)
  check('hearts carried over (half hearts, rounded down, capped)', [hearts('Alice'), hearts('Bob'), hearts('Cara'), hearts('Dora')], [15, 0, 20, 15])
  check('ledger says IMPORT', lastLedger('Cara').slice(0, 3).concat(lastLedger('Cara')[4]), ['IMPORT', 10, 20, 'brought down to the cap'])
  check('online players get their new max health', [await maxHealth('Alice'), await maxHealth('Cara')], [30, 40])
  check('Bob is unbanned', isBanned('Bob'), false)
  await say('Alice', '/lifesteal import confirm')
  check('a confirm only works once', chat.Alice.filter(m => m.includes('Nothing to confirm')).length, 2)

  await enter('Bob')
  await sleep(2500)
  check('eliminated import goes to Purgatory on join', await dimension('Bob'), DIM)
  await logOut('Bob')

  // CSV export
  await say('Alice', '/lifesteal import lifestealz lifestealz', 2500)
  check('CSV preview', saw('Alice', 'Ready to import 1 players'), true)
  check('CSV bad line reported', saw('Alice', "1 line(s) couldn't be read"), true)
  await say('Alice', '/lifesteal import confirm', 2500)
  check('CSV import applied', hearts('Dora'), 12)

  // LifeStealZ heart items: 3 items worth 2 hearts each become 6 Hearts on the next join.
  await rcon('clear Cara')
  await rcon('give Cara minecraft:nether_star[custom_data={PublicBukkitValues:{"lifestealz:customitemtype":"heart","lifestealz:customheartvalue":2}}] 3')
  await rcon('give Cara minecraft:nether_star[custom_data={PublicBukkitValues:{"lifestealz:customitemtype":"revive"}}] 1')
  check('Cara holds the old items', await count('Cara', 'minecraft:nether_star'), 4)
  await logOut('Cara'); await enter('Cara'); await sleep(1500)
  check('old hearts became our Hearts', await count('Cara', 'minecraft:red_dye'), 6)
  check('revive items are left alone', await count('Cara', 'minecraft:nether_star'), 1)
  check('Cara is told', saw('Cara', 'Your old LifeStealZ hearts became 6 Heart item(s)'), true)

  await rcon('deop Alice')
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'ommand failed' in l or 'missing message' in l or 'SEVERE' in l))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  for (const b of Object.values(bots)) { try { b.quit() } catch {} }
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
