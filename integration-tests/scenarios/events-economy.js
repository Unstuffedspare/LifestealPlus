const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const write = q => execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, q])
async function pvpKill(victim, killer) {
  for (let i = 0; i < 20; i++) { if ((await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`)).includes('Applied')) return; await sleep(500) }
  throw new Error(`couldn't kill ${victim}`)
}
const resetPvp = () => { write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties'); write('UPDATE players SET streak = 0') }
const itemsNear = async (x, y, z, r = 4) => Number(((await rcon(`execute if entity @e[type=minecraft:item,x=${x},y=${y},z=${z},distance=..${r}]`)).match(/count: (\d+)/i) || [0, 0])[1])
let win = null
const nextWindow = () => new Promise(r => bots.Alice.once('windowOpen', w => { win = w; r(w) }))

async function main() {
  await rcon('difficulty peaceful'); await rcon('kill @e[type=minecraft:item]')
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`); await rcon(`clear ${n}`)
  }
  write('UPDATE players SET shield_ms = 0'); resetPvp()
  await rcon('op Alice')
  await sleep(1500)

  // ---- Double Hearts
  await rcon('lifesteal event doublehearts start 5')
  await sleep(500)
  check('Double Hearts is announced', saw('Bob', 'Double Hearts! For the next 5 minutes'), true)
  await pvpKill('Bob', 'Alice'); await sleep(2500)
  check('a kill takes twice the hearts', [hearts('Alice'), hearts('Bob')], [12, 8])
  await rcon('lifesteal event doublehearts stop'); resetPvp()
  await rcon('lifesteal set Alice 10'); await rcon('lifesteal set Bob 10'); await sleep(1000)

  // ---- Cap Boost
  await rcon('lifesteal event capboost start 5'); await sleep(500)
  await say('Bob', '/hearts')
  check('the cap goes up', saw('Bob', 'Bob has 10/25 hearts'), true)
  await rcon('lifesteal give Bob 13'); await sleep(800)
  await rcon('lifesteal event capboost stop'); await sleep(500)
  check('hearts above the usual cap are kept', hearts('Bob'), 23)
  await rcon('lifesteal set Bob 10')

  // ---- Heart Frenzy (on Normal, so the zombie stays)
  await rcon('difficulty normal'); await rcon('lifesteal config heart-frenzy.fragment-chance 1')
  await rcon('lifesteal event frenzy start 5'); await sleep(500)
  await rcon('summon minecraft:zombie 12 -60 12 {NoAI:1b,Tags:["frenzy"]}'); await sleep(500)
  for (let i = 0; i < 10; i++) { if ((await rcon('damage @e[tag=frenzy,limit=1] 100 minecraft:player_attack by Alice')).includes('Applied')) break; await sleep(300) }
  await sleep(1000)
  const frenzyDrop = await rcon('data get entity @e[type=minecraft:item,x=12,y=-60,z=12,distance=..4,nbt={Item:{id:"minecraft:pink_dye"}},limit=1] Item.id')
  check('a Heart Fragment drops in a frenzy', frenzyDrop.includes('pink_dye'), true)
  await rcon('lifesteal event frenzy stop'); await rcon('lifesteal config heart-frenzy.fragment-chance 0.1')
  await rcon('kill @e[type=minecraft:item]'); await rcon('difficulty peaceful')

  // ---- King of the Hill at spawn, held by Alice alone
  await rcon('tp Bob 60 -60 60'); await rcon('tp Alice 0 -60 0'); await sleep(1000)
  write("DELETE FROM server_state WHERE key = 'nothing'")
  await rcon('setworldspawn 0 -60 0')
  await rcon('lifesteal event koth start 1'); await sleep(500)
  check('King of the Hill is announced', saw('Bob', 'King of the Hill at'), true)
  await sleep(64000)
  check('the one holding the hill wins', saw('Bob', 'Alice is King of the Hill'), true)
  check('and the prize', hearts('Alice'), 12)
  await rcon('lifesteal set Alice 10'); await rcon('tp Bob 0 -60 0')

  // ---- Golden crate
  await rcon('lifesteal config airdrops.golden-chance 1')
  await rcon('lifesteal event airdrop'); await sleep(800)
  check('a golden crate is announced', saw('Bob', 'A golden heart crate will land'), true)
  await rcon('lifesteal config airdrops.golden-chance 0.1')

  // ---- Revival: the scaled rift cost and the charm
  await rcon('lifesteal config revival.cooldown-minutes 0')
  await rcon('lifesteal set Bob 0'); await sleep(800); await rcon('lifesteal revive Bob'); await sleep(800)
  await rcon('lifesteal set Bob 0'); await sleep(1500)
  await say('Alice', '/rift Bob')
  // 6 hearts, plus one for every earlier elimination this season (earlier runs may have left some).
  const eliminations = JSON.parse(sql(`SELECT COUNT(*) FROM heart_ledger WHERE player_name='Bob' AND hearts_before > 0 AND hearts_after <= 0`))[0][0]
  check('each earlier elimination costs one more heart', saw('Alice', `Opening a rift for Bob costs ${6 + eliminations - 1} of your hearts`), true)
  await rcon('clear Alice'); await rcon('lifesteal item Alice charm 1'); await sleep(800)
  bots.Alice.setQuickBarSlot(0); await sleep(300)
  let w = nextWindow(); bots.Alice.activateItem();
  const opened = await Promise.race([w.then(() => true), sleep(5000).then(() => false)])
  check('the charm opens the menu', opened, true)
  if (opened) {
    await bots.Alice.clickWindow(0, 0, 0); await sleep(1000)
    check('one click asks to be sure', hearts('Bob'), 0)
    await bots.Alice.clickWindow(0, 0, 0); await sleep(2500)
    check('the second click brings Bob back', hearts('Bob'), 3)
    check('and the charm is spent', await count('Alice', 'minecraft:heart_of_the_sea'), 0)
  }
  await rcon('lifesteal config revival.cooldown-minutes 10')
  await logOut('Bob'); await enter('Bob'); await rcon('lifesteal set Bob 10'); await sleep(1500)

  // ---- Giving hearts, with a tax
  await rcon('lifesteal config trading.tax-percent 50')
  await say('Alice', '/giveheart Bob 2')
  check('a gift asks first, with the tax', saw('Alice', '1 are lost to tax, so they get 1'), true)
  await say('Alice', '/giveheart confirm', 2500)
  check('the gift arrives, less the tax', [hearts('Alice'), hearts('Bob')], [8, 11])
  check('Bob is told', saw('Bob', 'Alice gave you 1 heart(s)'), true)
  await rcon('lifesteal config trading.tax-percent 0')

  // ---- Milestones
  await rcon('advancement revoke Alice only minecraft:story/enter_the_nether'); await rcon('clear Alice')
  await rcon('advancement grant Alice only minecraft:story/enter_the_nether'); await sleep(1500)
  check('an advancement pays a fragment', saw('Alice', 'earned you 1 Heart Fragment(s)'), true)
  check('into the inventory', await count('Alice', 'minecraft:pink_dye'), 1)

  // ---- The shop without an economy
  await rcon('lifesteal config heart-shop.enabled on')
  await say('Bob', '/heartshop')
  check('the shop says it needs an economy', saw('Bob', 'needs Vault and an economy plugin'), true)
  await rcon('lifesteal config heart-shop.enabled off')

  await rcon('deop Alice'); await rcon('difficulty normal')
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
