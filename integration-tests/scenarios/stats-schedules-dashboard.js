const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const write = dbWrite
async function pvpKill(victim, killer) {
  for (let i = 0; i < 20; i++) { if ((await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`)).includes('Applied')) return; await sleep(500) }
  throw new Error(`couldn't kill ${victim}`)
}
const titles = []

async function main() {
  await rcon('difficulty peaceful')
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  bots.Alice.on('title', (text) => titles.push(JSON.stringify(text)))
  write('UPDATE players SET shield_ms = 0'); write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks'); write('DELETE FROM bounties')
  await rcon('op Alice')
  await sleep(1500)

  // ---- /stats
  await pvpKill('Bob', 'Alice'); await sleep(2500)
  await say('Bob', '/stats Alice')
  check('/stats shows the season record', chat.Bob.some(m => /Alice in /.test(m)), true)
  check('with the kill counted', chat.Bob.some(m => /Kills 1 · Deaths 0/.test(m)), true)
  check('and the heart won', chat.Bob.some(m => /Hearts won 1 · lost 0/.test(m)), true)
  await say('Bob', '/stats Nobody')
  check('an unknown player is named', saw('Bob', "There's no player called Nobody"), true)

  // ---- Elimination messages, title and sound
  write(`DELETE FROM heart_ledger WHERE cause LIKE 'PVP%'`); write('DELETE FROM vengeance_marks')
  await rcon('lifesteal set Bob 1'); await sleep(1000)
  await pvpKill('Bob', 'Alice'); await sleep(3000)
  check('a kill elimination names the killer', saw('Alice', 'Bob lost their last heart to Alice and is eliminated'), true)
  console.log('     titles seen:', titles.slice(-2))
  check('everyone gets a title', titles.some(t => t.includes('Bob')), true)
  await rcon('lifesteal revive Bob'); await sleep(2000)
  await logOut('Bob'); await enter('Bob'); await sleep(1500)

  // ---- Dashboard
  await say('Alice', '/lifesteal dashboard', 2500)
  check('the dashboard opens', saw('Alice', 'LifestealPlus dashboard'), true)
  check('with the economy', chat.Alice.some(m => /Hearts in play \d+/.test(m)), true)
  check('this week\'s sources', chat.Alice.some(m => /Last 7 days: gained .*pvp kill/.test(m)), true)
  check('and the latest eliminations', chat.Alice.some(m => /Latest eliminations: Bob/.test(m)), true)
  console.log('     ' + chat.Alice.filter(m => /dashboard|Hearts in play|Last 7|Next Blood|Latest|week /.test(m)).join('\n     '))

  // ---- A scheduled Blood Moon, with its warning
  await rcon('lifesteal config blood-moon.min-players-online 1')
  await rcon('lifesteal config blood-moon.warn-minutes 1')
  const when = new Date(Date.now() + 120000)
  const hhmm = `${String(when.getHours()).padStart(2, '0')}:${String(when.getMinutes()).padStart(2, '0')}`
  const set = await rcon(`lifesteal config blood-moon.schedule daily ${hhmm}`)
  check('a schedule can be set', set.includes(`blood-moon.schedule is now [daily ${hhmm}]`), true)
  const status = await rcon('lifesteal event status')
  console.log('     status:', status.replace(/\n/g, ' | ').slice(0, 200))
  const deadline = when.getTime() - when.getSeconds() * 1000 - when.getMilliseconds() + 15000
  while (Date.now() < deadline && !saw('Alice', 'Blood Moon rises in')) await sleep(1000)
  check('everyone is warned beforehand', saw('Alice', 'A Blood Moon rises in'), true)
  while (Date.now() < deadline && !chat.Alice.some(m => /Blood Moon has risen|Blood Moon/.test(m) && !/rises in/.test(m))) await sleep(1000)
  const rose = chat.Alice.filter(m => /Blood Moon/.test(m) && !/rises in/.test(m))
  console.log('     rose:', rose.slice(-1))
  check('and it rises on time', rose.length > 0, true)
  await rcon('lifesteal event bloodmoon stop')
  await rcon('lifesteal config blood-moon.schedule none')
  await rcon('lifesteal config blood-moon.min-players-online 3')
  await rcon('lifesteal config blood-moon.warn-minutes 10')

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
