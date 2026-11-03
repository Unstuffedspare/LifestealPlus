const health = async name => Number(((await rcon(`data get entity ${name} Health`)).match(/([\d.]+)f/) || [0, 0])[1])
async function useHeld(n, slot) { bots[n].setQuickBarSlot(slot); await sleep(300); bots[n].activateItem(); await sleep(1200); bots[n].deactivateItem() }
// A target can be briefly immune (just hit, or not finished loading); retry until the server applies it.
async function pvpKill(victim, killer) {
  for (let i = 0; i < 8; i++) {
    if (/Applied/.test(await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`))) break
    await sleep(700)
  }
  await sleep(1500)
}
const border = async () => Number(((await rcon('worldborder get')).match(/([\d.]+) block/) || [0, 0])[1])

async function main() {
  for (const n of ['Alice', 'Bob', 'Cara']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  await rcon('op Alice'); await rcon('difficulty normal'); await rcon('gamerule spawn_monsters false')
  check('schema upgraded to v6', sql('PRAGMA user_version'), '[[6]]')

  // Heart items
  await rcon('lifesteal item Alice heart 2'); await sleep(500)
  check('Alice holds 2 Hearts', await count('Alice', 'minecraft:red_dye'), 2)
  await useHeld('Alice', 0)
  check('using a Heart adds one', hearts('Alice'), 11)
  check('and uses up the item', await count('Alice', 'minecraft:red_dye'), 1)
  check('ledger cause', lastLedger('Alice')[0], 'HEART_ITEM')
  await rcon('lifesteal set Alice 20'); await sleep(500)
  await useHeld('Alice', 0)
  check('at the cap the Heart comes back', [hearts('Alice'), await count('Alice', 'minecraft:red_dye')], [20, 1])
  check('Alice is told', saw('Alice', 'already at the most hearts'), true)
  await rcon('clear Alice'); await rcon('lifesteal item Alice fragment 4'); await sleep(500)
  await useHeld('Alice', 0)
  check('4 fragments fuse into a Heart', [await count('Alice', 'minecraft:pink_dye'), await count('Alice', 'minecraft:red_dye')], [0, 1])
  await rcon('lifesteal set Alice 10'); await rcon('clear Alice')

  // Blood Moon
  await rcon('lifesteal event bloodmoon start 2'); await sleep(1500)
  check('Blood Moon announced', saw('Bob', 'A Blood Moon rises'), true)
  check('it is midnight', /at 18\d\d\d tick/.test(await rcon('time query day')), true)
  check('and time stands still', /false/.test(await rcon('gamerule advance_time')), true)
  await pvpKill('Bob', 'Alice')
  check('kills take double hearts', [hearts('Bob'), hearts('Alice')], [8, 12])
  await rcon('execute at Alice run summon minecraft:zombie ~2 ~ ~ {NoAI:1b}'); await sleep(300)
  await rcon('damage @e[type=minecraft:zombie,limit=1] 1000 minecraft:player_attack by Alice'); await sleep(800)
  check('monsters drop Heart Fragments', /passed/i.test(await rcon('execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:pink_dye"}}]')), true)
  await rcon('kill @e[type=minecraft:item]')
  await rcon('lifesteal event bloodmoon stop'); await sleep(1000)
  check('it sets', saw('Bob', 'The Blood Moon sets'), true)
  check('time moves again', /true/.test(await rcon('gamerule advance_time')), true)
  await pvpKill('Bob', 'Cara')
  check('kills are back to one heart', [hearts('Bob'), hearts('Cara')], [7, 11])

  // Airdrops
  bots.Alice.chat('/lifesteal event airdrop here'); await sleep(1500)
  check('drop announced', saw('Bob', 'A heart crate will land'), true)
  await sleep(10000)
  const landed = chat.Bob.find(m => m.includes('has landed')) || ''
  const at = (landed.match(/x (-?\d+), y (-?\d+), z (-?\d+)/) || []).slice(1).map(Number)
  check('crate landed', at.length, 3)
  check('crate is a chest', at.length === 3 && /passed/i.test(await rcon(`execute if block ${at[0]} ${at[1]} ${at[2]} minecraft:chest`)), true)
  // `data get` truncates long replies, so read the crate one item at a time.
  const ids = []
  for (let i = 0; i < 6 && at.length === 3; i++) {
    const m = (await rcon(`data get block ${at[0]} ${at[1]} ${at[2]} Items[${i}].id`)).match(/"minecraft:([a-z_]+)"/)
    if (m) ids.push(m[1])
  }
  check('it holds a Heart and fragments', ids.includes('red_dye') && ids.includes('pink_dye'), true)
  // Opening it can't be tested here: the bot library can't open containers through ViaBackwards.
  await sleep(13000)
  check('unemptied crate crumbles on time', saw('Bob', 'crumbles away'), true)
  check('and the chest is gone', at.length === 3 && /passed/i.test(await rcon(`execute if block ${at[0]} ${at[1]} ${at[2]} minecraft:air`)), true)

  // Finale: only Alice, Bob and Cara may hold hearts, so "last one standing" means something.
  require('child_process').execFileSync('python', ['-c',
    "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(\"UPDATE players SET hearts=0 WHERE name NOT IN ('Alice','Bob','Cara')\"); c.commit()", DB])
  const before = await border()
  await rcon('lifesteal finale start 1 20'); await sleep(3000)
  check('finale announced', saw('Bob', 'The Finale has begun'), true)
  const during = await border()
  check('border is shrinking', during < before && during > 20, true)
  await rcon('lifesteal set Cara 0'); await sleep(3000)
  bots.Cara.chat('/purgatory start'); await sleep(1500)
  check('Purgatory is sealed', saw('Cara', 'Purgatory is sealed for the finale'), true)
  await rcon('lifesteal set Bob 0'); await sleep(2000)
  check('last one standing wins', saw('Alice', 'Alice is the last one standing and wins the season'), true)
  await rcon('lifesteal finale stop'); await sleep(1000)
  check('stopping restores the border', Math.round(await border()), Math.round(before))

  await rcon('lifesteal revive Bob 10'); await rcon('lifesteal revive Cara 10'); await rcon('deop Alice')
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'Heart change failed' in l or 'command failed' in l or 'generated an exception' in l))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  for (const b of Object.values(bots)) b.quit()
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
