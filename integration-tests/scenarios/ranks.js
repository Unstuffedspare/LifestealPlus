// Rank perks and admin permission groups, given out with LuckPerms (install it on the test server).
const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const write = dbWrite
const lp = cmd => rcon('lp ' + cmd)
// LuckPerms only knows players by name once they've joined with it installed, so use their (offline-mode) UUIDs.
function uuid(name) {
  const h = require('crypto').createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

async function main() {
  // Eve is brand new: no record at all, so her next join is a first join.
  write("DELETE FROM heart_ledger WHERE player_name = 'Eve'"); write("DELETE FROM players WHERE name = 'Eve'")
  await lp(`user ${uuid('Eve')} permission set lifestealplus.starting-hearts.15 true`)
  await lp(`user ${uuid('Eve')} permission set lifestealplus.max-hearts.25 true`)
  await lp(`user ${uuid('Bob')} permission set lifestealplus.admin.events true`)
  await lp(`user ${uuid('Bob')} permission unset lifestealplus.admin`)
  await rcon('deop Bob'); await sleep(1000)

  await enter('Eve'); await sleep(3000)
  check('a rank\'s starting hearts apply on the first join', hearts('Eve'), 15)
  check('recorded as her first join', JSON.parse(sql("SELECT note FROM heart_ledger WHERE player_name='Eve' ORDER BY id DESC LIMIT 1"))[0][0], 'rank starting hearts')
  check('her own cap is saved', JSON.parse(sql("SELECT max_hearts FROM players WHERE name='Eve'"))[0][0], 25)
  await rcon('lifesteal give Eve 20'); await sleep(1000)
  check('and she can go past the server cap to hers', hearts('Eve'), 25)
  await say('Eve', '/hearts')
  check('/hearts shows her cap', saw('Eve', 'Eve has 25/25 hearts'), true)

  // Losing the rank keeps her hearts, and coming back doesn't reapply the starting hearts.
  await lp(`user ${uuid('Eve')} permission unset lifestealplus.max-hearts.25`); await sleep(500)
  await logOut('Eve'); await enter('Eve'); await sleep(2000)
  check('without the rank, no saved cap', JSON.parse(sql("SELECT max_hearts FROM players WHERE name='Eve'"))[0][0], null)
  check('and she keeps her hearts', hearts('Eve'), 25)

  // An admin group: Bob can run events but nothing else under /lifesteal.
  await enter('Bob'); await sleep(1500)
  await say('Bob', '/lifesteal event doublehearts start 1')
  check('the events permission runs events', saw('Bob', 'Double Hearts!'), true)
  await rcon('lifesteal event doublehearts stop')
  const before = hearts('Bob')
  await say('Bob', '/lifesteal give Bob 5')
  check('but can\'t change hearts', hearts('Bob'), before)

  await lp(`user ${uuid('Eve')} permission unset lifestealplus.starting-hearts.15`)
  await lp(`user ${uuid('Bob')} permission unset lifestealplus.admin.events`)
  write("DELETE FROM heart_ledger WHERE player_name = 'Eve'"); write("DELETE FROM players WHERE name = 'Eve'")
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
