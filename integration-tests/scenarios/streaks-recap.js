const fs = require('fs')
// sql() above only reads; writes need a commit.
const write = q => execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, q])
const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
async function pvpKill(victim, killer) {
  for (let i = 0; i < 8; i++) {
    const out = await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`)
    if (/Applied/.test(out)) break
    await sleep(700)
  }
  await sleep(1500)
}
const streakRow = n => JSON.parse(sql(`SELECT streak, best_streak FROM players WHERE name='${n}'`))[0]
const anyone = text => Object.values(chat).some(lines => lines.some(m => m.includes(text)))

async function main() {
  await rcon('lifesteal set Dora 5'); await sleep(1000)
  write("UPDATE players SET streak = 0, best_streak = 0, last_left = NULL")
  write("DELETE FROM news")
  for (const n of ['Alice', 'Bob', 'Cara']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  // The same pair trading kills: no repeat-kill window or vengeance, and a shutdown from 3 kills.
  for (const c of ['repeat-kills.enabled off', 'vengeance.enabled off', 'streaks.shutdown-at 3']) await rcon(`lifesteal config ${c}`)
  await sleep(1000)

  for (let i = 0; i < 3; i++) await pvpKill('Bob', 'Alice')
  check('three kills make a streak', streakRow('Alice'), [3, 3])
  check('everyone hears about it', saw('Cara', 'Alice is on a 3-kill streak'), true)
  check('two kills were not announced', anyone('is on a 2-kill streak'), false)

  await pvpKill('Alice', 'Cara')
  check('ending it is announced with the bonus', saw('Bob', "Cara ended Alice's 3-kill streak and took 1 bonus heart"), true)
  check('the shutdown took an extra heart', [hearts('Alice'), hearts('Cara')], [11, 12])
  check('the streak is over but remembered', streakRow('Alice'), [0, 3])
  await say('Alice', '/streak')
  check('/streak shows current and best', saw('Alice', 'has 0 kill(s) in a row right now. Best this season: 3'), true)
  await say('Bob', '/streak Cara')
  check('/streak works for someone else', saw('Bob', 'Cara has 1 kill(s) in a row'), true)

  await rcon('kill Cara'); await sleep(2000)
  check('dying any other way ends a streak', streakRow('Cara'), [0, 1])

  // Bob goes away for two hours (well, we say he did) while things happen.
  await logOut('Bob')
  await rcon('lifesteal set Dora 0'); await sleep(1000); await rcon('lifesteal revive Dora 5'); await sleep(1000)
  write(`UPDATE players SET last_left = ${Date.now() - 2 * 3600 * 1000} WHERE name = 'Bob'`)
  chat.Bob = []
  await enter('Bob'); await sleep(4500)
  check('the recap arrives on join', chat.Bob.some(m => /While you were away \(2h? ?\d*m?( hours)?\)/.test(m)), true)
  check('with the streak', saw('Bob', 'Alice reached a 3-kill streak'), true)
  check('who ended it', saw('Bob', "Cara ended Alice's 3-kill streak"), true)
  check('an elimination', saw('Bob', 'Dora was eliminated'), true)
  check('and a comeback, last', chat.Bob.filter(m => m.startsWith('- ')).pop(), '- Dora came back with 5 hearts.')

  await rcon('lifesteal config recap.lines 2')
  await logOut('Bob')
  write(`UPDATE players SET last_left = ${Date.now() - 2 * 3600 * 1000} WHERE name = 'Bob'`)
  chat.Bob = []
  await enter('Bob'); await sleep(4500)
  check('a long recap points to /news', saw('Bob', '...and 2 more. /news shows the latest'), true)
  await logOut('Bob'); chat.Bob = []
  await enter('Bob'); await sleep(4500)
  check('a quick rejoin gets no recap', saw('Bob', 'While you were away'), false)

  await say('Bob', '/news')
  check('/news lists the latest', saw('Bob', 'Latest news:') && chat.Bob.some(m => / ago Dora came back with 5 hearts/.test(m)), true)

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
