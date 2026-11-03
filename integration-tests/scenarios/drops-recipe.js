const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
const fs = require('fs')
const write = dbWrite
const near = async (x, y, z, r = 3) => Number(((await rcon(`execute if entity @e[type=minecraft:item,x=${x},y=${y},z=${z},distance=..${r}]`)).match(/count: (\d+)/i) || [0, 0])[1])
const itemData = (x, y, z, r, path) => rcon(`data get entity @e[type=minecraft:item,x=${x},y=${y},z=${z},distance=..${r},limit=1] ${path}`)

async function main() {
  await rcon('difficulty peaceful')
  await rcon('kill @e[type=minecraft:item]')
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`); await rcon(`clear ${n}`)
  }
  write(`UPDATE players SET shield_ms = 0`); write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties')
  await sleep(1500)

  // ---- A heart lost to a non-player death drops where the player died.
  await rcon('tp Bob 30 -60 30'); await sleep(800)
  await rcon('kill Bob'); await sleep(2500)
  check('Bob lost a heart', hearts('Bob'), 9)
  check('a Heart item lies where he died', await near(30, -60, 30), 1)
  check('it is a plugin Heart', (await itemData(30, -60, 30, 3, 'Item.components."minecraft:custom_data"')).includes('HEART'), true)
  check('it glows', (await itemData(30, -60, 30, 3, 'Glowing')).includes('1b'), true)
  check('it can\'t be hurt', (await itemData(30, -60, 30, 3, 'Invulnerable')).includes('1b'), true)
  check('Bob is told where', saw('Bob', 'dropped where you died, at 30 -60 30'), true)

  await rcon('setblock 30 -60 30 minecraft:lava'); await sleep(4000)
  check('lava doesn\'t burn it', await near(30, -60, 30, 4), 1)
  await rcon('setblock 30 -60 30 minecraft:air')

  // (A bot moved by /tp never confirms the move, so it can't be used to test picking it up.)
  await rcon('kill @e[type=minecraft:item]')

  // ---- Switched off, the heart is simply lost.
  await rcon('lifesteal config hearts.natural-death-drops off')
  await rcon('tp Bob 20 -60 20'); await sleep(800)
  await rcon('kill Bob'); await sleep(2500)
  check('switched off, the heart is still lost', hearts('Bob'), 8)
  check('but nothing drops', await near(20, -60, 20), 0)
  await rcon('lifesteal config hearts.natural-death-drops on')

  // ---- PvP deaths don't drop hearts: the killer takes them.
  await rcon('tp Bob 0 -60 0'); await sleep(800)
  await rcon('lifesteal set Bob 10'); await rcon('kill @e[type=minecraft:item]'); await sleep(800)
  for (let i = 0; i < 20; i++) { if ((await rcon('damage Bob 1000 minecraft:player_attack by Alice')).includes('Applied')) break; await sleep(500) }
  await sleep(2500)
  check('a PvP death moves the heart to the killer', [hearts('Bob'), hearts('Alice')], [9, 11])
  check('and drops no Heart item', await near(0, -60, 0, 6), 0)

  // ---- The recipe: registered, switchable, and it makes a fresh Heart.
  check('the recipe is in the recipe book', (await rcon('recipe take Alice lifestealplus:heart')).includes('Took 1'), true)
  const bad = await rcon('lifesteal config heart-items.recipe.middle banana')
  check('a made-up item is refused', bad.includes("isn't an item name like diamond_block"), true)
  await rcon('lifesteal config heart-items.recipe.enabled off'); await sleep(500)
  const gone = await rcon('recipe take Alice lifestealplus:heart')
  console.log('     after switching off:', gone.trim())
  check('switched off, the recipe is gone', /Unknown recipe|No recipe|Couldn't|isn't/i.test(gone) || gone.includes('Took 0'), true)
  await rcon('lifesteal config heart-items.recipe.enabled on'); await sleep(500)
  check('and back on', /Unlocked|No new recipes/.test(await rcon('recipe give Bob lifestealplus:heart')), true)

  // A crafter does the crafting the bots can't: corners, sides, middle.
  await rcon('setblock 40 -60 40 minecraft:crafter[orientation=up_north]')
  const slots = { 0: 'diamond_block', 2: 'diamond_block', 6: 'diamond_block', 8: 'diamond_block',
    1: 'gold_block', 3: 'gold_block', 5: 'gold_block', 7: 'gold_block', 4: 'netherite_ingot' }
  for (const [slot, item] of Object.entries(slots)) await rcon(`item replace block 40 -60 40 container.${slot} with minecraft:${item}`)
  const before = Date.now()
  await rcon('setblock 41 -60 40 minecraft:redstone_block'); await sleep(2500)
  check('the crafter makes a Heart', await near(40, -59, 40, 3), 1)
  const crafted = await itemData(40, -59, 40, 3, 'Item.components."minecraft:custom_data"')
  console.log('     crafted:', crafted.slice(-160))
  check('a real plugin Heart', crafted.includes('HEART'), true)
  const minted = Number((crafted.match(/heart_minted": (\d+)L/) || [0, 0])[1])
  check('starting to fade from when it was crafted', minted >= before - 5000 && minted <= Date.now() + 5000, true)
  await rcon('setblock 41 -60 40 minecraft:air'); await rcon('setblock 40 -60 40 minecraft:air'); await rcon('kill @e[type=minecraft:item]')

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
