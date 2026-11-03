// Command hooks for server events: event-start and event-end for each event, airdrop-landed,
// King of the Hill and finale hooks. Writes its own hooks.yml and puts the shipped one back.
const fs = require('fs')
const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const HOOKS = path.join(SERVER, 'plugins', 'LifestealPlus', 'hooks.yml')
const hook = text => saw('Bob', 'HOOK ' + text)

async function main() {
  const events = ['blood-moon', 'double-hearts', 'heart-frenzy', 'cap-boost', 'king-of-the-hill', 'finale']
  fs.writeFileSync(HOOKS, [
    'event-start:', ...events.map(e => `  ${e}: ["say HOOK start {event} {minutes}"]`),
    'event-end:', ...events.map(e => `  ${e}: ["say HOOK end {event}"]`),
    'airdrop-landed: ["say HOOK landed {world} {x} {y} {z} {golden}"]',
    'airdrop-opened: ["say HOOK opened {player} {x} {y} {z}"]',
    'koth-won: ["say HOOK koth {player} held {seconds}s for {hearts}"]',
    'finale-won: ["say HOOK finale {player}"]', ''].join('\n'))
  for (const n of ['Alice', 'Bob', 'Cara']) { await enter(n); await rcon(`gamemode survival ${n}`); await rcon(`lifesteal set ${n} 10`) }
  await rcon('op Alice')
  const reload = await rcon('lifesteal reload')
  check('the event hooks load without warnings', /looks fine/.test(reload), true)

  // Each event's start and end, from the admin commands.
  for (const [command, event] of [['event bloodmoon', 'blood-moon'], ['event doublehearts', 'double-hearts'],
    ['event frenzy', 'heart-frenzy'], ['event capboost', 'cap-boost']]) {
    await rcon(`lifesteal ${command} start 2`); await sleep(1000)
    await rcon(`lifesteal ${command} stop`); await sleep(1000)
    check(`${event}: start and end hooks`, [hook(`start ${event} 2`), hook(`end ${event}`)], [true, true])
  }

  // An airdrop next to Alice.
  // The crate crumbles soon, so it doesn't block the next run's.
  await rcon('lifesteal config airdrops.despawn-minutes 0.5')
  await rcon('tp Alice 0 -60 0'); await rcon('tp Bob 0 -60 8'); await sleep(1000)
  await say('Alice', '/lifesteal event airdrop here', 13000)
  const landed = chat.Bob.find(m => m.includes('HOOK landed'))
  check('the airdrop-landed hook', /HOOK landed world -?\d+ -?\d+ -?\d+ (true|false)/.test(landed || ''), true)
  // airdrop-opened isn't checked: the bots can't open containers through ViaBackwards.

  // King of the Hill at spawn, held by Alice alone.
  await rcon('setworldspawn 0 -60 0'); await rcon('tp Alice 0 -60 0'); await rcon('tp Bob 60 -60 60'); await rcon('tp Cara -60 -60 -60'); await sleep(1000)
  await rcon('lifesteal event koth start 1'); await sleep(1000)
  check('king-of-the-hill start hook', hook('start king-of-the-hill 1'), true)
  await sleep(63000)
  check('the koth-won hook', chat.Bob.some(m => /HOOK koth Alice held \d+s for \d+/.test(m)), true)
  check('and its end hook', hook('end king-of-the-hill'), true)

  // The finale, won by the last one standing.
  dbWrite("UPDATE players SET hearts = 0 WHERE name NOT IN ('Alice', 'Bob', 'Cara')")
  await rcon('lifesteal finale start 1 20'); await sleep(2000)
  check('finale start hook', hook('start finale 1'), true)
  await rcon('lifesteal set Cara 0'); await sleep(1500)
  await rcon('lifesteal set Bob 0'); await sleep(2500)
  check('the finale-won hook', saw('Alice', 'HOOK finale Alice'), true)
  await rcon('lifesteal finale stop'); await sleep(1000)
  check('and its end hook', saw('Alice', 'HOOK end finale'), true)

  await rcon('lifesteal revive Bob 10'); await rcon('lifesteal revive Cara 10'); await rcon('lifesteal set Alice 10')
  fs.writeFileSync(HOOKS, fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'hooks.yml')))
  await rcon('lifesteal config airdrops.despawn-minutes 20')
  await rcon('lifesteal reload'); await rcon('deop Alice')
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
