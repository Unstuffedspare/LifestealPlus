// Two servers sharing one MySQL/MariaDB database, as in a network. Run with LIFESTEAL_MYSQL set,
// both servers on database.type: mysql (different server-names), and the second server's ports in
// LIFESTEAL_SECOND_PORT and LIFESTEAL_SECOND_RCON_PORT.
const SECOND_PORT = Number(process.env.LIFESTEAL_SECOND_PORT || 25799)
const SECOND_RCON = Number(process.env.LIFESTEAL_SECOND_RCON_PORT || 25798)
const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const write = dbWrite
const maxHealthOn = async (rconPort, name) =>
  Number(((await rawRcon(`attribute ${name} minecraft:max_health base get`, rconPort)).match(/is ([\d.]+)/) || [0, -1])[1])

async function main() {
  // Alice and Bob start on the first server, Cara on the second.
  for (const n of ['Alice', 'Bob']) { await enter(n); await rcon(`gamemode survival ${n}`) }
  await enter('Cara', SECOND_PORT); await rawRcon('gamemode survival Cara', SECOND_RCON)
  for (const n of ['Alice', 'Bob', 'Cara']) await rcon(`lifesteal set ${n} 10`)
  write('UPDATE players SET shield_ms = 0, streak = 0'); write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`)
  write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties')
  await rcon('difficulty peaceful'); await rawRcon('difficulty peaceful', SECOND_RCON)
  await sleep(1500)

  // A kill on the first server...
  for (let i = 0; i < 20; i++) { if ((await rcon('damage Bob 1000 minecraft:player_attack by Alice')).includes('Applied')) break; await sleep(500) }
  await sleep(2500)
  check('a kill on one server moves a heart', [hearts('Alice'), hearts('Bob')], [11, 9])

  // ...goes with Bob to the second.
  await logOut('Bob'); await enter('Bob', SECOND_PORT); await sleep(1500)
  await say('Bob', '/hearts')
  check('Bob arrives on the other server with his hearts', saw('Bob', 'Bob has 9/20 hearts'), true)
  check('and his health matches', await maxHealthOn(SECOND_RCON, 'Bob'), 18)

  // A change made on the first server reaches him on the second within a minute.
  await rcon('lifesteal give Bob 3'); await sleep(1000)
  check('the database has it at once', hearts('Bob'), 12)
  let synced = false
  for (let i = 0; i < 40 && !synced; i++) { synced = await maxHealthOn(SECOND_RCON, 'Bob') === 24; if (!synced) await sleep(2000) }
  check('and his health on the other server follows within a minute', synced, true)

  // Players on different servers still deal with each other.
  await say('Alice', '/bounty place Cara 2')
  check('a bounty can be put on someone on another server', JSON.parse(sql(
    "SELECT COALESCE(SUM(b.hearts),0) FROM bounties b JOIN players p ON p.uuid=b.target WHERE p.name='Cara' AND b.closed_at IS NULL"))[0][0], 2)
  await say('Cara', '/stats Alice')
  check('and stats are the same everywhere', chat.Cara.some(m => /Kills 1 · Deaths 0/.test(m)), true)

  // World events stay on their own server.
  await rcon('lifesteal event bloodmoon start 2'); await sleep(1500)
  check('a Blood Moon is kept under its own server', JSON.parse(sql(
    "SELECT COUNT(*) FROM server_state WHERE key = 'server.survival.blood_moon.ends'"))[0][0], 1)
  const other = await rawRcon('lifesteal event status', SECOND_RCON)
  console.log('     lobby status:', other.replace(/\n/g, ' | ').slice(0, 120))
  check('and doesn\'t rise on the other one', /Blood Moon: up now/.test(other), false)
  await rcon('lifesteal event bloodmoon stop')

  // Both servers write to one ledger, and an admin's action is recorded whichever server it came from.
  // Alice: 10, +1 for the kill, -2 for the bounty on Cara, and now -1 from the other server.
  await rawRcon('lifesteal take Alice 1', SECOND_RCON); await sleep(1000)
  check('hearts changed on either server land in one ledger', hearts('Alice'), 8)
  check('and the audit log says which', JSON.parse(sql(
    "SELECT COUNT(*) FROM audit WHERE detail = '/lifesteal take Alice 1'"))[0][0] >= 1, true)

  // Both servers change Alice at the same moment, over and over. Nothing may be lost, and the
  // ledger has to tell one unbroken story.
  await rcon('lifesteal set Alice 10'); await sleep(1000)
  const firstRow = JSON.parse(sql("SELECT COALESCE(MAX(id), 0) FROM heart_ledger WHERE player_name = 'Alice'"))[0][0]
  const hammer = (send, first, second) => (async () => {
    for (let i = 0; i < 25; i++) { await send(`lifesteal ${first} Alice 1`); await send(`lifesteal ${second} Alice 1`) }
  })()
  await Promise.all([hammer(cmd => rawRcon(cmd), 'give', 'take'), hammer(cmd => rawRcon(cmd, SECOND_RCON), 'take', 'give')])
  await sleep(2000)
  check('both servers at once: no heart lost or doubled', hearts('Alice'), 10)
  const rows = JSON.parse(sql(`SELECT hearts_before, hearts_after FROM heart_ledger WHERE player_name = 'Alice' AND id > ${firstRow} ORDER BY id`))
  check('every change is in the ledger', rows.length, 100)
  check('each row follows on from the one before', rows.every((r, i) => i === 0 || r[0] === rows[i - 1][1]), true)
  check('and every event has its own id', JSON.parse(sql(
    `SELECT COUNT(*) - COUNT(DISTINCT event_id) FROM heart_ledger WHERE player_name = 'Alice' AND id > ${firstRow}`))[0][0], 0)

  await rcon('difficulty normal'); await rawRcon('difficulty normal', SECOND_RCON)
  for (const n of ['Alice', 'Bob', 'Cara']) await rcon(`lifesteal set ${n} 10`)
  write('DELETE FROM bounties')
  const errors = log => require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'ommand failed' in l or 'LifestealPlus' in l and ('SEVERE' in l or 'ERROR' in l)))",
    log]).toString().trim()
  check('no plugin errors on the first server', errors(path.join(SERVER, 'server.out')), '0')
  if (process.env.LIFESTEAL_SECOND_SERVER) {
    check('or the second', errors(path.join(process.env.LIFESTEAL_SECOND_SERVER, 'server.out')), '0')
  }
  for (const b of Object.values(bots)) { try { b.quit() } catch {} }
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
