async function pvpKill(victim, killer) {
  for (let i = 0; i < 8; i++) {
    if (/Applied/.test(await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`))) break
    await sleep(700)
  }
  await sleep(1500)
}
const dimension = async name => ((await rcon(`data get entity ${name} Dimension`)).match(/"([^"]+)"/) || [])[1]
async function useHeld(n, slot) { bots[n].setQuickBarSlot(slot); await sleep(300); bots[n].activateItem(); await sleep(1200); bots[n].deactivateItem() }
const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
const agedHeart = days => `minecraft:red_dye[custom_data={PublicBukkitValues:{"lifestealplus:heart_item":"HEART","lifestealplus:heart_minted":${Date.now() - days * 86400000}L}}]`
const cfg = path.join(SERVER, 'plugins', 'LifestealPlus', 'config.yml')
const fs = require('fs')

async function main() {
  require('child_process').execFileSync('python', ['-c',
    "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('UPDATE players SET shield_ms=0'); c.execute(\"DELETE FROM heart_ledger WHERE cause IN ('PVP_DEATH','PVP_KILL')\"); c.execute('DELETE FROM vengeance_marks'); c.commit()", DB])
  for (const n of ['Alice', 'Bob', 'Cara', 'Dana']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  await rcon('lifesteal shield Dana 0')
  await sleep(800)
  check('schema upgraded to v8', sql('PRAGMA user_version'), '[[8]]')

  // Withdraw and decay
  await say('Alice', '/withdraw 2')
  check('withdrawing takes hearts', hearts('Alice'), 8)
  check('and gives Heart items', await count('Alice', 'minecraft:red_dye'), 2)
  await useHeld('Alice', 0)
  check('a fresh withdrawn Heart gives a heart back', hearts('Alice'), 9)
  await say('Alice', '/withdraw 9')
  check('you must keep a heart', saw('Alice', 'must keep at least 1'), true)
  await rcon('clear Alice'); await rcon(`give Alice ${agedHeart(2)} 1`); await sleep(500)
  await useHeld('Alice', 0)
  check('a 2-day-old Heart breaks into 2 fragments', [await count('Alice', 'minecraft:pink_dye'), hearts('Alice')], [2, 9])
  await rcon('clear Alice'); await rcon(`give Alice ${agedHeart(5)} 1`); await sleep(500)
  await useHeld('Alice', 0)
  check('a 5-day-old Heart crumbles', [await count('Alice', 'minecraft:red_dye'), await count('Alice', 'minecraft:pink_dye'), saw('Alice', 'crumbles to dust')], [0, 0, true])

  // Rising cap
  fs.writeFileSync(cfg, fs.readFileSync(cfg, 'utf8').replace('max-by-week: []', 'max-by-week: [12, 20]'))
  await rcon('lifesteal reload'); await sleep(1500)
  await rcon('lifesteal set Alice 15'); await sleep(800)
  check('week one caps at 12', hearts('Alice'), 12)
  require('child_process').execFileSync('python', ['-c',
    "import sqlite3,sys,time; c=sqlite3.connect(sys.argv[1]); c.execute(\"UPDATE server_state SET value=? WHERE key='season.started'\", (str(int(time.time()*1000) - 8*86400000),)); c.commit()", DB])
  await rcon('lifesteal reload'); await sleep(1500)
  check('the rise is announced', saw('Bob', 'heart cap has risen to 20 for week 2'), true)
  await rcon('lifesteal set Alice 15'); await sleep(800)
  check('week two allows 15', hearts('Alice'), 15)
  fs.writeFileSync(cfg, fs.readFileSync(cfg, 'utf8').replace('max-by-week: [12, 20]', 'max-by-week: []'))
  await rcon('lifesteal reload'); await rcon('lifesteal set Alice 10'); await sleep(800)

  // Rift
  await rcon('lifesteal set Dana 0'); await sleep(2500)
  await say('Alice', '/rift Dana')
  check('a rift asks first', saw('Alice', 'costs 6 of your hearts'), true)
  await say('Alice', '/rift confirm', 2500)
  check('the rift costs Alice 6 and brings Dana back with 3', [hearts('Alice'), hearts('Dana')], [4, 3])
  check('everyone hears', saw('Bob', 'Alice tore open a rift and pulled Dana out of Purgatory'), true)
  check('Dana is home', await dimension('Dana'), 'minecraft:overworld')

  // Revival pledges
  for (const n of ['Alice', 'Bob', 'Cara']) await rcon(`lifesteal set ${n} 10`)
  await rcon('lifesteal set Dana 0'); await sleep(2000)
  await say('Alice', '/pledge Dana 2')
  await say('Bob', '/pledge Dana 2')
  check('progress is announced', saw('Cara', 'Bob pledged 2 heart(s) to bring Dana back') && saw('Cara', '4/6 hearts, 2/3 players'), true)
  await say('Bob', '/pledge cancel Dana')
  check('a pledge can be taken back', hearts('Bob'), 10)
  await say('Bob', '/pledge Dana 3')
  await say('Cara', '/pledge Dana 1', 2500)
  check('6 hearts from 3 players brings Dana back', [hearts('Dana'), hearts('Alice'), hearts('Bob'), hearts('Cara')], [3, 8, 7, 9])
  check('the vote is announced', saw('Alice', '3 players pledged 6 hearts and voted Dana back to life'), true)
  await rcon('lifesteal set Dana 0'); await sleep(1500)
  await say('Alice', '/pledge Dana 2')
  await rcon('lifesteal revive Dana'); await sleep(2000)
  check('pledges are refunded if Dana comes back another way', hearts('Alice'), 8)
  check('and Alice is told', saw('Alice', 'came back another way'), true)

  // Blood Pact
  for (const n of ['Alice', 'Bob', 'Cara']) await rcon(`lifesteal set ${n} 10`)
  await say('Alice', '/pact Bob')
  await say('Bob', '/pact accept Alice')
  check('the pact is announced', saw('Cara', 'Alice and Bob have sealed a Blood Pact'), true)
  await pvpKill('Cara', 'Alice'); await sleep(500)
  check('Alice\'s kill pays Bob too', [hearts('Alice'), hearts('Bob'), hearts('Cara')], [11, 11, 9])
  check('Bob is told', saw('Bob', 'Alice won hearts, so you gain 1 too'), true)
  await rcon('kill Alice'); await sleep(2000)
  check('Alice\'s death costs both', [hearts('Alice'), hearts('Bob')], [10, 10])
  check('Bob is told about the death', saw('Bob', 'Alice died, and it costs you a heart'), true)
  await say('Alice', '/pact break')
  check('breaking is announced', saw('Cara', 'Alice has broken their Blood Pact with Bob'), true)
  await say('Alice', '/pact Cara'); await say('Cara', '/pact accept Alice')
  check('a cooldown follows', saw('Cara', 'broke a pact recently'), true)

  // Ghosts
  await rcon('lifesteal set Dana 0'); await sleep(2500)
  await say('Dana', '/ghost', 2000)
  check('a ghost roams the overworld as a spectator', [await dimension('Dana'), await gamemode('Dana')], ['minecraft:overworld', 3])
  await say('Dana', 'can anyone hear me')
  check('living players don\'t hear ghost chat', chat.Alice.some(m => m.includes('can anyone hear me')), false)
  await say('Dana', '/msg Alice psst')
  check('ghosts can\'t /msg the living', saw('Dana', 'The living can\'t hear you') && !saw('Alice', 'psst'), true)
  await say('Dana', '/whisper Alice base at 120 64 -300')
  check('a whisper gets through', saw('Alice', "Dana's ghost whispers to you: base at 120 64 -300"), true)
  await say('Dana', '/whisper Alice again')
  check('but only once an hour', saw('Dana', 'Your voice is spent'), true)
  await say('Dana', '/ghost', 2000)
  check('/ghost again returns to Purgatory', [await dimension('Dana'), await gamemode('Dana')], ['minecraft:lifesteal_purgatory', 2])
  await rcon('lifesteal revive Dana'); await sleep(2000)
  check('a revived ghost goes home in survival', [await dimension('Dana'), await gamemode('Dana')], ['minecraft:overworld', 0])

  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'Heart change failed' in l or 'ommand failed' in l or 'Couldn' in l))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  for (const b of Object.values(bots)) b.quit()
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
