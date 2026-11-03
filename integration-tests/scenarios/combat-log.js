const write = q => execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, q])
// A hit can bounce off during hurt-immunity frames, so keep trying until it lands.
async function land(victim, attacker, amount = 1) {
  for (let i = 0; i < 20; i++) {
    if ((await rcon(`damage ${victim} ${amount} minecraft:player_attack by ${attacker}`)).includes('Applied')) return
    await sleep(500)
  }
  throw new Error(`couldn't hit ${victim}`)
}

async function main() {
  await rcon('difficulty peaceful')
  await rcon('lifesteal config combat-log.body-seconds 8')
  for (const n of ['Alice', 'Bob', 'Cara']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  write('UPDATE players SET shield_ms = 0'); write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties')
  await rcon('kill @e[type=minecraft:mannequin]'); await rcon('kill @e[type=minecraft:item]')
  await sleep(1500)

  // Plain logout: no body.
  await logOut('Bob')
  check('quitting out of combat leaves no body', await bodies(), 0)
  await enter('Bob')

  // Logging out in combat leaves a body; killing it is killing Bob.
  await rcon('give Bob minecraft:diamond 5')
  await land('Bob', 'Alice'); await sleep(800)
  await logOut('Bob')
  check('a body appears', await bodies(), 1)
  check('everyone is told', saw('Cara', 'Bob logged out in combat'), true)
  await rcon('damage @e[type=minecraft:mannequin,limit=1] 1000 minecraft:player_attack by Alice'); await sleep(1500)
  check('killing the body costs Bob a heart', [hearts('Bob'), hearts('Alice')], [9, 11])
  check('as a PvP kill', lastLedger('Bob').slice(0, 4), ['PVP_DEATH', 10, 9, 'Alice'])
  check('the body is gone', await bodies(), 0)
  // They drop at the body, which is where the bots stand, so Alice may already have picked them up.
  const onGround = /passed/i.test(await rcon('execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:diamond"}}]'))
  check('his diamonds dropped', onGround || await count('Alice', 'minecraft:diamond') === 5, true)
  await rcon('kill @e[type=minecraft:item]')
  await enter('Bob'); await sleep(1500)
  check('Bob comes back with nothing', await count('Bob', 'minecraft:diamond'), 0)
  check('and is told why', saw('Bob', 'your body was killed'), true)

  // A body nobody kills fades, and its owner keeps everything.
  await rcon('give Cara minecraft:emerald 3')
  await land('Cara', 'Alice'); await sleep(800)
  await logOut('Cara')
  check('Cara leaves a body', await bodies(), 1)
  await sleep(9000)
  check('it fades after its time', await bodies(), 0)
  check('the fade is announced', saw('Alice', "Cara's body fades away"), true)
  await enter('Cara'); await sleep(1000)
  check('Cara still has her emeralds', await count('Cara', 'minecraft:emerald'), 3)
  check('and her hearts', hearts('Cara'), 10)

  // Coming back while the body is still there: step back into it, hurt as it was.
  await land('Cara', 'Alice'); await sleep(800)
  await logOut('Cara')
  await rcon('damage @e[type=minecraft:mannequin,limit=1] 6 minecraft:player_attack by Alice')
  await enter('Cara')
  // Read straight away: Peaceful heals within a second.
  const health = Number(((await rcon('data get entity Cara Health')).match(/([\d.]+)f\s*$/) || [0, 20])[1])
  await sleep(1500)
  check('rejoining removes the body', await bodies(), 0)
  check('Cara is told', saw('Cara', 'back in your body'), true)
  check('the damage to the body carried over', health < 20, true)

  // An admin kick isn't combat logging.
  await land('Bob', 'Alice'); await sleep(800)
  await rcon('kick Bob'); await sleep(1500)
  check('kicked mid-fight leaves no body', await bodies(), 0)
  await enter('Bob')

  // A body's death can eliminate, and the owner wakes up eliminated.
  write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks')
  await rcon('lifesteal set Bob 1'); await sleep(800)
  await land('Bob', 'Cara'); await sleep(800)
  await logOut('Bob')
  await rcon('damage @e[type=minecraft:mannequin,limit=1] 1000 minecraft:player_attack by Cara'); await sleep(1500)
  check('Bob is eliminated by his body\'s death', hearts('Bob'), 0)
  await enter('Bob'); await sleep(3000)
  const where = ((await rcon('data get entity Bob Dimension')).match(/"([^"]+)"/) || [])[1]
  console.log('     Bob is in', where)
  check('and wakes up eliminated (Purgatory, or spectating where there is none)',
    where === 'minecraft:lifesteal_purgatory' || /: 3/.test(await rcon('data get entity Bob playerGameType')), true)

  await rcon('lifesteal revive Bob 10'); await rcon('lifesteal config combat-log.body-seconds 30'); await rcon('difficulty normal')
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
