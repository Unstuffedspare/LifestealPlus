async function pvpKill(victim, killer) {
  for (let i = 0; i < 8; i++) {
    if (/Applied/.test(await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`))) break
    await sleep(700)
  }
  await sleep(1500)
}
const dimension = async name => ((await rcon(`data get entity ${name} Dimension`)).match(/"([^"]+)"/) || [])[1]
const shieldMs = name => JSON.parse(sql(`SELECT shield_ms FROM players WHERE name='${name}'`))[0]?.[0]

async function main() {
  for (const n of ['Alice', 'Bob', 'Cara']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  await rcon('op Alice')
  require('child_process').execFileSync('python', ['-c',
    "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(\"DELETE FROM heart_ledger WHERE cause IN ('PVP_DEATH','PVP_KILL')\"); c.execute('DELETE FROM vengeance_marks'); c.execute('UPDATE players SET shield_ms=0'); c.commit()", DB])
  check('schema upgraded to v7', sql('PRAGMA user_version'), '[[7]]')
  check('logins are stored as hashes, not addresses',
    /^[0-9a-f]{64}$/.test(JSON.parse(sql("SELECT address_hash FROM logins LIMIT 1"))[0][0]), true)

  // All the bots come from 127.0.0.1, so they're all linked.
  await pvpKill('Bob', 'Alice')
  check('the kill still counts', [hearts('Bob'), hearts('Alice')], [9, 11])
  check('admins are alerted', saw('Alice', 'Alice killed Bob, and they\'ve logged in from the same address'), true)
  check('non-admins are not', saw('Cara', 'logged in from the same address'), false)
  bots.Alice.chat('/lifesteal flags'); await sleep(1200)
  check('/lifesteal flags lists it', chat.Alice.some(m => /Alice.*killed.*Bob/.test(m) && /\d\d-\d\d/.test(m)), true)
  bots.Alice.chat('/lifesteal alts Bob'); await sleep(1200)
  check('/lifesteal alts shows the links', saw('Alice', 'Bob shares an address with:') && saw('Alice', ' Alice ') || saw('Alice', 'Alice (11 hearts)'), true)

  bots.Bob.chat('/leaderboard'); await sleep(1200)
  check('everyone can see the live leaderboard', saw('Bob', 'Season 1: most hearts') && saw('Bob', '#1'), true)

  // Season reset
  await rcon('lifesteal set Cara 0'); await sleep(2500)
  check('Cara is in Purgatory before the reset', await dimension('Cara'), 'minecraft:lifesteal_purgatory')
  bots.Alice.chat('/lifesteal season reset Season 2'); await sleep(1200)
  check('reset asks for confirmation', saw('Alice', 'This ends Season 1 for everyone'), true)
  check('nothing wiped yet', hearts('Alice'), 11)
  bots.Alice.chat('/lifesteal season confirm'); await sleep(3500)
  check('everyone is told', saw('Bob', 'Season 1 is over') && saw('Bob', 'Welcome to Season 2'), true)
  check('online players start fresh', [hearts('Alice'), hearts('Bob'), hearts('Cara')], [10, 10, 10])
  check('with a new-player shield', shieldMs('Bob') > 0, true)
  check('Cara is home from Purgatory', await dimension('Cara'), 'minecraft:overworld')
  check('the old ledger was archived', JSON.parse(sql("SELECT COUNT(*) FROM heart_ledger_archive WHERE season='Season 1'"))[0][0] > 0, true)
  check('standings were archived', JSON.parse(sql("SELECT name FROM season_standings WHERE season='Season 1' AND rank=1"))[0][0], 'Alice')
  bots.Bob.chat('/leaderboard Season 1'); await sleep(1200)
  check('anyone can see the old standings', saw('Bob', 'Season 1: final standings') && chat.Bob.some(m => /#1 Alice 11❤ 1 kills/.test(m)), true)
  bots.Alice.chat('/lifesteal season'); await sleep(1200)
  check('current season is Season 2', saw('Alice', 'Current season: Season 2'), true)
  bots.Alice.chat('/lifesteal season reset Season 2'); await sleep(1200)
  check('a new season needs a new name', saw('Alice', 'needs a different name'), true)
  bots.Alice.chat('/lifesteal alts Bob'); await sleep(1200)
  check('links survive the reset', chat.Alice.filter(m => m.includes('Bob shares an address with')).length >= 2, true)

  await rcon('deop Alice')
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'Heart change failed' in l or 'command failed' in l or 'reset failed' in l or 'Couldn' in l))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  for (const b of Object.values(bots)) b.quit()
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
