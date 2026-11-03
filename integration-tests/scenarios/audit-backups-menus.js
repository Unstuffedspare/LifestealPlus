const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const fs = require('fs')
const write = dbWrite
const DATA = path.join(SERVER, 'plugins', 'LifestealPlus')
const files = dir => fs.existsSync(path.join(DATA, dir)) ? fs.readdirSync(path.join(DATA, dir)) : []
let win = null
const nextWindow = () => new Promise(r => bots.Alice.once('windowOpen', w => { win = w; r(w) }))
const itemText = slot => JSON.stringify(win.slots[slot] || 'empty')
const phase = process.argv[2] || 'before'

async function before() {
  for (const n of ['Alice', 'Bob']) { await enter(n); await rcon(`gamemode survival ${n}`) }
  await rcon('op Alice'); await sleep(1500)
  for (const f of files('backups')) fs.unlinkSync(path.join(DATA, 'backups', f))

  // ---- Audit log
  await rcon('lifesteal set Bob 9'); await rcon('lifesteal config hearts.max 25'); await rcon('lifesteal config hearts.max 20')
  await rcon('lifesteal ledger Bob')
  await sleep(1000)
  const audit = JSON.parse(sql("SELECT actor, action, detail FROM audit ORDER BY id DESC LIMIT 3"))
  console.log('     audit:', JSON.stringify(audit))
  check('an RCON command is recorded', audit.some(r => r[0] === 'Rcon' && r[2] === '/lifesteal set Bob 9'), true)
  check('a setting change is recorded with old and new', audit.some(r => r[1] === 'setting' && r[2] === 'hearts.max: 25 -> 20'), true)
  check('looking at the ledger isn\'t', JSON.parse(sql("SELECT COUNT(*) FROM audit WHERE detail LIKE '%ledger%'"))[0][0], 0)
  await say('Alice', '/lifesteal audit', 2000)
  check('/lifesteal audit shows it', chat.Alice.some(m => m.includes('Admin actions')) && chat.Alice.some(m => m.includes('hearts.max: 25 -> 20')), true)

  // ---- Backups, one player
  await rcon('lifesteal set Bob 7'); await sleep(800)
  await say('Alice', '/lifesteal backup now', 2500)
  const made = files('backups').find(f => f.endsWith('-manual.db'))
  check('a backup is made', made !== undefined, true)
  await rcon('lifesteal set Bob 2'); await sleep(800)
  await say('Alice', `/lifesteal backup restore ${made} Bob`, 2500)
  check('one player is put back', hearts('Bob'), 7)
  check('and their ledger says so', JSON.parse(sql("SELECT cause, note FROM heart_ledger WHERE player_name='Bob' ORDER BY id DESC LIMIT 1"))[0][0], 'RESTORE')

  // ---- Exports
  await say('Alice', '/lifesteal export ledger Bob', 2000)
  await say('Alice', '/lifesteal export audit', 2000)
  check('the ledger exports', files('exports').some(f => f.startsWith('ledger-Bob-') && f.endsWith('.csv')), true)
  check('the audit exports', files('exports').some(f => f.startsWith('audit-')), true)

  // ---- Season reset backs up first
  await say('Alice', '/lifesteal season reset Phase4Test'); await say('Alice', '/lifesteal season confirm', 4000)
  check('a season reset makes a backup first', files('backups').some(f => f.endsWith('-before-season-reset.db')), true)

  // ---- Presets page with all eight, then a filler from menus.yml
  let w = nextWindow(); bots.Alice.chat('/lifesteal settings'); await w
  const size = win.inventoryStart
  w = nextWindow(); await bots.Alice.clickWindow(size - 4, 0, 0); await w; await sleep(800)
  check('the presets page has four rows', win.inventoryStart, 36)
  const names = [10, 11, 12, 13, 14, 15, 16, 19].map(s => itemText(s))
  check('all eight presets are on it', ['Casual', 'Smp', 'Team', 'Standard', 'Competitive', 'Hardcore', 'Ultra', 'Anarchy']
    .every((n, i) => names[i].includes(n)), true)
  bots.Alice.closeWindow(win)
  const menus = path.join(DATA, 'menus.yml')
  fs.writeFileSync(menus, fs.readFileSync(menus, 'utf8').replace(/settings-menu:([\s\S]*?)filler: none/, 'settings-menu:$1filler: gray_stained_glass_pane'))
  await rcon('lifesteal reload'); await sleep(500)
  w = nextWindow(); bots.Alice.chat('/lifesteal settings'); await w
  check('a filler set in menus.yml fills the empty slots', itemText(win.inventoryStart - 1).includes('gray_stained_glass_pane') || win.slots[win.inventoryStart - 1]?.name === 'gray_stained_glass_pane', true)
  bots.Alice.closeWindow(win)
  fs.writeFileSync(menus, fs.readFileSync(menus, 'utf8').replace('filler: gray_stained_glass_pane', 'filler: none'))

  // ---- Stage a full restore of the manual backup (Bob had 7), for after the restart
  await say('Alice', `/lifesteal backup restore ${made}`, 1500)
  check('a full restore waits for a restart', saw('Alice', 'Restart the server to restore everything'), true)
  check('the marker is written', fs.existsSync(path.join(DATA, 'restore-on-start.txt')), true)
  await rcon('deop Alice')
  for (const b of Object.values(bots)) { try { b.quit() } catch {} }
}

async function after() {
  check('after the restart, the whole database is the backup\'s', hearts('Bob'), 7)
  check('the season is back too', JSON.parse(sql("SELECT value FROM server_state WHERE key='season.name'"))[0][0] !== 'Phase4Test', true)
  check('the replaced database was kept', files('backups').some(f => f.startsWith('before-restore-')), true)
  check('and the restore happened once', fs.existsSync(path.join(DATA, 'restore-on-start.txt')), false)
}

async function main() {
  if (phase === 'before') await before(); else await after()
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'ommand failed' in l or 'missing message' in l or 'LifestealPlus' in l and ('SEVERE' in l or 'ERROR' in l)))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
