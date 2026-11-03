// The paths that matter most on Folia, checked only with what works there: the database and
// "execute if entity" (Folia doesn't let players use /data). Run with LIFESTEAL_FOLIA=1.
const write = q => execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, q])
const is = async selector => /passed/i.test(await rcon(`execute if entity ${selector}`))
async function land(victim, attacker, amount) {
  let reply = ''
  for (let i = 0; i < 20; i++) {
    reply = await rcon(`damage ${victim} ${amount} minecraft:player_attack by ${attacker}`)
    if (reply.includes('Applied')) return
    await sleep(500)
  }
  throw new Error(`couldn't hit ${victim}: ${reply}`)
}

async function main() {
  await rawRcon('difficulty peaceful')
  for (const n of ['Alice', 'Bob', 'Cara']) {
    await enter(n)
    await rawRcon(`lifesteal set ${n} 10`)
  }
  for (const n of ['Alice', 'Bob', 'Cara']) await rcon(`gamemode survival ${n}`)
  write('UPDATE players SET shield_ms = 0, streak = 0'); write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`)
  write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties')
  await rcon('kill @e[type=minecraft:item]'); await rcon('kill @e[type=minecraft:mannequin]')
  await sleep(1500)

  // A kill: hearts move, health follows (max health is set on the player's own thread).
  await land('Bob', 'Alice', 1000); await sleep(2500)
  check('a kill moves a heart', [hearts('Alice'), hearts('Bob')], [11, 9])
  check('the killer\'s max health follows', await is('@a[name=Alice,nbt={attributes:[{id:"minecraft:max_health",base:22.0d}]}]'), true)
  // A bot on Folia doesn't get past the death screen; joining again brings it back.
  await logOut('Bob'); await enter('Bob')

  // A death to the world drops the heart as an item (placed on the region's thread).
  await rcon('kill Cara'); await sleep(2500)
  check('a natural death costs a heart', hearts('Cara'), 9)
  // It lands where the bots stand, so someone may already have picked it up.
  check('and drops a Heart', await is('@e[type=minecraft:item,nbt={Glowing:1b}]')
    || await is('@a[nbt={Inventory:[{id:"minecraft:red_dye"}]}]'), true)
  await rcon('kill @e[type=minecraft:item]'); await rcon('clear @a minecraft:red_dye')
  await logOut('Cara'); await enter('Cara')

  // Combat logging: a body appears, and killing it is a PvP kill.
  await land('Bob', 'Alice', 1); await sleep(800)
  await logOut('Bob')
  check('logging out in combat leaves a body', await is('@e[type=minecraft:mannequin]'), true)
  write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks')
  await rcon('damage @e[type=minecraft:mannequin,limit=1] 1000 minecraft:player_attack by Alice'); await sleep(2000)
  check('killing the body costs Bob a heart', hearts('Bob'), 8)
  await enter('Bob'); await sleep(1500)

  // Elimination: with no Purgatory on Folia, an eliminated player spectates, and comes back on revival.
  await rawRcon('lifesteal set Bob 0'); await sleep(2500)
  check('an eliminated player spectates', await is('@a[name=Bob,gamemode=spectator]'), true)
  await rawRcon('lifesteal revive Bob'); await sleep(2500)
  check('and is back in survival when revived', await is('@a[name=Bob,gamemode=survival]'), true)
  check('with the revive hearts', hearts('Bob'), 3)

  // Admin item gifts land in another player's inventory from their own thread.
  await rcon('clear Cara'); await rawRcon('lifesteal item Cara heart 2'); await sleep(1500)
  check('an admin gift arrives', await is('@a[name=Cara,nbt={Inventory:[{id:"minecraft:red_dye"}]}]'), true)

  // Events that change the world itself (time, the border) from the global region.
  await rawRcon('lifesteal event bloodmoon start 1'); await sleep(1500)
  check('a Blood Moon makes it night', JSON.parse(sql("SELECT COUNT(*) FROM server_state WHERE key LIKE '%blood_moon.ends'"))[0][0], 1)
  await rawRcon('lifesteal event bloodmoon stop')
  await rawRcon('lifesteal event doublehearts start 1'); await sleep(500); await rawRcon('lifesteal event doublehearts stop')

  await rawRcon('lifesteal set Bob 10'); await rawRcon('difficulty normal')
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'dev.lifestealplus' in l or 'LifestealPlus' in l and ('SEVERE' in l or 'ERROR' in l)))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('nothing from the plugin in the error log', errors, '0')
  for (const b of Object.values(bots)) { try { b.quit() } catch {} }
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
