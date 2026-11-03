const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const write = q => execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, q])
async function pvpKill(victim, killer) {
  for (let i = 0; i < 20; i++) { if ((await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`)).includes('Applied')) return; await sleep(500) }
  throw new Error(`couldn't kill ${victim}`)
}
async function tagHit(victim, attacker) {
  for (let i = 0; i < 20; i++) { if ((await rcon(`damage ${victim} 1 minecraft:player_attack by ${attacker}`)).includes('Applied')) return; await sleep(500) }
}
const papi = async (player, text) => (await rcon(`papi parse ${player} ${text}`)).trim()
const resetPvp = () => { write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties') }

async function main() {
  await rcon('difficulty peaceful')
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  write('UPDATE players SET shield_ms = 0'); resetPvp()
  await sleep(1500)

  // ---- PlaceholderAPI
  console.log('     papi list:', (await rcon('papi list')).replace(/\n/g, ' ').slice(0, 160))
  check('%lifestealplus_hearts%', await papi('Alice', '%lifestealplus_hearts%'), '10')
  check('%lifestealplus_max_hearts%', await papi('Alice', '%lifestealplus_max_hearts%'), '20')
  check('%lifestealplus_eliminated%', await papi('Alice', '%lifestealplus_eliminated%'), 'false')
  await pvpKill('Bob', 'Alice'); await sleep(2500)
  check('hearts follow a kill at once', await papi('Alice', '%lifestealplus_hearts%'), '11')
  await logOut('Alice'); await enter('Alice'); await sleep(2500)   // joining refreshes the slower numbers
  check('%lifestealplus_kills%', await papi('Alice', '%lifestealplus_kills%'), '1')
  check('%lifestealplus_streak%', await papi('Alice', '%lifestealplus_streak%'), '1')
  await sleep(31000)   // the leaderboard refreshes every 30 seconds
  check('%lifestealplus_top_1_name%', await papi('Alice', '%lifestealplus_top_1_name%'), 'Alice')
  check('%lifestealplus_top_1_hearts%', await papi('Alice', '%lifestealplus_top_1_hearts%'), '11')
  console.log('     season:', await papi('Alice', '%lifestealplus_season%'), '| unknown:', await papi('Alice', '%lifestealplus_nonsense%'))
  await rcon('lifesteal set Alice 10'); await rcon('lifesteal set Bob 10'); resetPvp(); await sleep(1000)

  // ---- WorldGuard: the whole overworld through its global region
  console.log('     wg:', (await rcon('rg flag __global__ -w world lifesteal deny')).replace(/\n/g, ' ').slice(0, 120))
  await sleep(500)
  await pvpKill('Bob', 'Alice'); await sleep(2500)
  check('lifesteal deny: a kill takes no heart', [hearts('Alice'), hearts('Bob')], [10, 10])
  await rcon('kill Bob'); await sleep(2500)
  check('nor does any other death', hearts('Bob'), 10)
  await rcon('rg flag __global__ -w world lifesteal'); resetPvp(); await sleep(500)

  await rcon('rg flag __global__ -w world lifesteal-natural-loss deny'); await sleep(500)
  await rcon('kill Bob'); await sleep(2500)
  check('natural-loss deny: dying to the world costs nothing', hearts('Bob'), 10)
  await pvpKill('Bob', 'Alice'); await sleep(2500)
  check('but PvP still takes hearts', [hearts('Alice'), hearts('Bob')], [11, 9])
  await rcon('rg flag __global__ -w world lifesteal-natural-loss'); resetPvp()
  await rcon('lifesteal set Alice 10'); await rcon('lifesteal set Bob 10'); await sleep(1000)

  // Combat tags, seen through bounty undo (refused while the target is in a fight).
  await say('Alice', '/bounty place Bob 1')
  await tagHit('Bob', 'Alice'); await sleep(500)
  await say('Alice', '/bounty undo Bob')
  check('normally a hit tags the target', saw('Alice', 'Bob is in a fight right now'), true)
  await sleep(16000); await say('Alice', '/bounty undo Bob')
  await rcon('rg flag __global__ -w world lifesteal-combat-tag deny'); await sleep(500)
  chat.Alice = []
  await say('Alice', '/bounty place Bob 1')
  await tagHit('Bob', 'Alice'); await sleep(500)
  await say('Alice', '/bounty undo Bob')
  check('combat-tag deny: no tag, so the undo goes through', saw('Alice', 'You took back 1 heart(s)'), true)
  await rcon('rg flag __global__ -w world lifesteal-combat-tag')

  await rcon('difficulty normal')
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
