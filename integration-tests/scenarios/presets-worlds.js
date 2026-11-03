const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
const fs = require('fs')
const write = q => execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, q])
const cfgFile = path.join(SERVER, 'plugins', 'LifestealPlus', 'config.yml')
const cfg = () => fs.readFileSync(cfgFile, 'utf8')
const block = (name, next) => cfg().split(`\n${name}:`)[1].split(`\n${next}:`)[0]
const changedLines = (a, b) => {
  const x = a.split('\n'), y = b.split('\n')
  if (x.length !== y.length) return `line count ${x.length} -> ${y.length}`
  return x.map((l, i) => l === y[i] ? null : `${i + 1}: ${l.trim()} -> ${y[i].trim()}`).filter(Boolean)
}
const ledgerRows = name => JSON.parse(sql(`SELECT COUNT(*) FROM heart_ledger WHERE player_name='${name}'`))[0][0]
async function pvpKill(victim, killer) {
  for (let i = 0; i < 20; i++) {
    const out = await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`)
    if (out.includes('Applied')) return
    await sleep(500)
  }
  throw new Error(`couldn't kill ${victim}`)
}
let win = null
const nextWindow = () => new Promise(r => bots.Alice.once('windowOpen', w => { win = w; r(w) }))
const itemText = slot => JSON.stringify(win.slots[slot] || 'empty')
const click = async (slot, button = 0, mode = 0) => { await bots.Alice.clickWindow(slot, button, mode); await sleep(1200) }

async function main() {
  await rcon('difficulty peaceful')
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties')
  write(`UPDATE players SET shield_ms = 0, streak = 0`)
  await rcon('op Alice'); await rcon('deop Bob')
  await sleep(1500)
  // The first save rewrites [3, 5, 10] as a block list; take the baseline after it.
  await rcon('lifesteal config hearts.max 20')
  const before = cfg()

  // ---- Excluded worlds: the test overworld is "world".
  const r1 = await rcon('lifesteal config worlds.excluded world')
  check('a world can be excluded from the console', r1.includes('worlds.excluded is now [world]'), true)
  check('saved as a list', /\n  excluded:\n  - world/.test(cfg()) || /excluded: \[world\]/.test(cfg()), true)
  let rows = ledgerRows('Bob')
  await pvpKill('Bob', 'Alice'); await sleep(2500)
  check('a PvP kill there takes no heart', [hearts('Bob'), hearts('Alice')], [10, 10])
  check('and writes nothing to the ledger', ledgerRows('Bob'), rows)
  await rcon('kill Bob'); await sleep(2500)
  check('nor does any other death', hearts('Bob'), 10)
  const wanted = await rcon('lifesteal mostwanted')
  console.log('     most wanted reply:', wanted.trim())

  await rcon('lifesteal config worlds.excluded none')
  check('switched back off', /excluded: \[\]/.test(cfg()), true)
  await sleep(1500)
  await pvpKill('Bob', 'Alice'); await sleep(2500)
  check('elsewhere kills take hearts as usual', [hearts('Bob'), hearts('Alice')], [9, 11])
  await rcon('lifesteal set Bob 10'); await rcon('lifesteal set Alice 10'); await sleep(1500)

  // ---- Presets from the console.
  const list = await rcon('lifesteal preset')
  check('the list marks the preset in use', /standard \(in use\)/.test(list), true)
  const preview = await rcon('lifesteal preset hardcore')
  check('a preview lists the changes', preview.includes('The hardcore preset would change'), true)
  check('with old and new values', preview.includes('hearts.max: 20 -> 15'), true)
  check('a preview changes nothing', changedLines(before, cfg()), [])
  const applied = await rcon('lifesteal preset hardcore confirm')
  check('confirm applies it', /Applied the hardcore preset \(\d+ setting/.test(applied), true)
  check('hearts.max is 15', /\n  max: 15\n/.test(block('hearts', 'worlds')), true)
  check('last stand is off', /enabled: false/.test(block('last-stand', 'most-wanted')), true)
  check('comments are kept', cfg().split('\n').filter(l => l.trim().startsWith('#')).length,
    before.split('\n').filter(l => l.trim().startsWith('#')).length)
  await say('Bob', '/hearts')
  check('it takes effect at once', saw('Bob', 'Bob has 10/15 hearts'), true)
  const again = await rcon('lifesteal preset hardcore confirm')
  check('applying it twice says so', again.includes('already match the hardcore preset'), true)
  const unknown = await rcon('lifesteal preset extreme')
  check('an unknown preset is named', unknown.includes("There's no preset called extreme"), true)
  await rcon('lifesteal preset standard confirm')
  check('standard puts the file back exactly', changedLines(before, cfg()), [])

  // ---- Presets from the menu: two clicks to apply.
  let w = nextWindow(); bots.Alice.chat('/lifesteal settings'); await w
  const size = win.inventoryStart
  console.log('     menu size', size, 'presets button:', itemText(size - 4).slice(0, 120))
  check('the first page has a presets button', itemText(size - 4).includes('Presets'), true)
  w = nextWindow(); await click(size - 4); await w
  check('it opens the presets page', JSON.stringify(win.title).includes('Presets'), true)
  check('casual is first on the presets page', itemText(10).includes('Casual'), true)
  check('standard shows as in use', itemText(13).includes('in use'), true)
  await click(10)
  check('one click changes nothing yet', changedLines(before, cfg()), [])
  check('it asks for a second click', itemText(10).includes('Click again to apply'), true)
  await click(10)
  check('the second click applies casual', /natural-death-loss: 0/.test(block('hearts', 'worlds')), true)
  check('and casual now shows as in use', itemText(10).includes('in use'), true)
  check('confirmed in chat', saw('Alice', 'Applied the casual preset'), true)
  await rcon('lifesteal preset standard confirm')
  bots.Alice.closeWindow(win)

  check('config.yml matches the start again', changedLines(before, cfg()), [])
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
