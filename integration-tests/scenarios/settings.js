const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
const fs = require('fs')
// sql() above only reads; writes need a commit.
const write = dbWrite
const cfgFile = path.join(SERVER, 'plugins', 'LifestealPlus', 'config.yml')
const changedLines = (a, b) => {
  const x = a.split('\n'), y = b.split('\n')
  if (x.length !== y.length) return `line count ${x.length} -> ${y.length}`
  return x.map((l, i) => l === y[i] ? null : `${i + 1}: ${l.trim()} -> ${y[i].trim()}`).filter(Boolean)
}

async function main() {
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  await rcon('op Alice'); await rcon('deop Bob')
  await sleep(1500)

  // Changing a setting from chat keeps every comment and touches only that line.
  const before = fs.readFileSync(cfgFile, 'utf8')
  await say('Alice', '/lifesteal config newbie-shield.enabled off', 2000)
  check('switching a feature off is confirmed', saw('Alice', 'newbie-shield.enabled is now off'), true)
  const after = fs.readFileSync(cfgFile, 'utf8')
  const changed = changedLines(before, after)
  check('only that line changed in config.yml', changed.length === 1 && changed[0].endsWith(': enabled: true -> enabled: false'), true)
  await say('Alice', '/lifesteal features')
  check('and it is off straight away', chat.Alice.some(m => /off\s+newbie-shield/.test(m)), true)

  // Values the plugin couldn't use are refused, and the file is left alone.
  await say('Alice', '/lifesteal config hearts.max 1.5')
  check('a half number is refused with the reason', saw('Alice', 'Not changed: hearts.max should be a whole number'), true)
  await say('Alice', '/lifesteal config hearts.max twenty')
  check('a word is refused', saw('Alice', 'hearts.max needs a number'), true)
  await say('Alice', '/lifesteal config hearts.max 5')
  check('a cap below the starting hearts is refused', saw('Alice', 'hearts.starting (10) is above hearts.max (5)'), true)
  await say('Alice', '/lifesteal config hats.max 3')
  check('an unknown setting is named', saw('Alice', "There's no setting called hats.max"), true)
  check('refusals leave the file alone', changedLines(after, fs.readFileSync(cfgFile, 'utf8')), [])

  // Seeing a value, and a change that applies at once.
  await say('Alice', '/lifesteal config hearts.max')
  check('a setting can be looked up', saw('Alice', 'hearts.max is 20'), true)
  check('with its explanation', saw('Alice', 'Most hearts anyone can have'), true)
  await say('Alice', '/lifesteal config hearts.max 25')
  await say('Bob', '/hearts')
  check('a new cap applies without a reload', saw('Bob', 'Bob has 10/25 hearts'), true)
  await say('Alice', '/lifesteal config purgatory.world other_world')
  check('restart-only settings say so', saw('Alice', 'Restart the server for it to take effect'), true)
  await say('Alice', '/lifesteal config purgatory.world lifesteal_purgatory')
  await say('Alice', '/lifesteal config hearts.max-by-week 15, 20 25')
  check('lists can be typed loosely', saw('Alice', 'hearts.max-by-week is now [15, 20, 25]'), true)
  await say('Alice', '/lifesteal config hearts.max-by-week none')
  await say('Alice', '/lifesteal config hearts.max 20')

  // Non-admins can't.
  await say('Bob', '/lifesteal config hearts.max 99')
  check('non-admins can\'t change settings', fs.readFileSync(cfgFile, 'utf8').includes('max: 99'), false)

  // Console
  const consoleReply = await rcon('lifesteal config newbie-shield.enabled on')
  check('the console can change settings', consoleReply.includes('newbie-shield.enabled is now on'), true)

  // Newbie shield day limit: 3 hours of shield, but first joined 4 days ago.
  write(`UPDATE players SET shield_ms = 10800000, shield_since = NULL, first_seen = ${Date.now() - 4 * 86400000} WHERE name = 'Bob'`)
  await logOut('Bob'); chat.Bob = []; await enter('Bob'); await sleep(1500)
  check('an old shield isn\'t given back on join', saw('Bob', 'new-player shield for another'), false)
  await say('Bob', '/shield')
  check('/shield says there is none', saw('Bob', "You don't have a new-player shield"), true)
  write(`UPDATE players SET first_seen = ${Date.now() - 1 * 86400000} WHERE name = 'Bob'`)
  await logOut('Bob'); chat.Bob = []; await enter('Bob'); await sleep(1500)
  check('a shield inside the limit still works', saw('Bob', 'new-player shield for another 3 hours'), true)
  await say('Alice', '/lifesteal config newbie-shield.expires-after-days 0.5', 2500)
  await say('Bob', '/shield')
  check('lowering the limit ends it at once', saw('Bob', 'Your new-player shield has worn off'), true)
  await say('Bob', '/shield')
  check('and /shield agrees', saw('Bob', "You don't have a new-player shield"), true)
  await say('Alice', '/lifesteal config newbie-shield.expires-after-days 3')
  write(`UPDATE players SET shield_ms = 0 WHERE name = 'Bob'`)

  // The menu: the bot library can't always see windows through ViaBackwards, so this is a best effort.
  let opened = null
  bots.Alice.once('windowOpen', w => { opened = w })
  await say('Alice', '/lifesteal settings', 3000)
  console.log(opened ? `     menu opened: ${opened.slots.filter(Boolean).length} items` : '     (menu window not visible to the bot)')

  check('config.yml matches the start again', changedLines(before, fs.readFileSync(cfgFile, 'utf8')), [])
  await rcon('deop Alice')
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
