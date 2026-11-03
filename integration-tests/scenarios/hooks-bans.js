// Command hooks (hooks.yml) and ban mode (eliminations.ban). Writes its own hooks.yml and puts
// an empty one back at the end.
const fs = require('fs')
const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const write = dbWrite
const HOOKS = path.join(SERVER, 'plugins', 'LifestealPlus', 'hooks.yml')
const banned = name => JSON.parse(fs.readFileSync(path.join(SERVER, 'banned-players.json'), 'utf8')).find(b => b.name === name)
const kicks = {}
async function kill(victim, killer) {
  for (let i = 0; i < 20; i++) {
    if ((await rcon(`damage ${victim} 1000 minecraft:player_attack by ${killer}`)).includes('Applied')) break
    await sleep(500)
  }
  await sleep(3000)
}
async function enterWatching(n) {
  await enter(n)
  bots[n].on('kicked', reason => { kicks[n] = JSON.stringify(reason) })
}
// What a refused join said, or null if it got in.
async function tryEnter(n) {
  try { await enterWatching(n); return null } catch (e) { return e.message }
}

// Bots never send movement, so after changing worlds the server keeps them invulnerable; a fresh login clears it.
async function rejoin(n) { await logOut(n); await enterWatching(n); await rcon(`gamemode survival ${n}`) }
const eliminations = name => JSON.parse(sql(
  `SELECT COUNT(*) FROM heart_ledger WHERE player_name='${name}' AND hearts_before > 0 AND hearts_after <= 0`))[0][0]

async function main() {
  fs.writeFileSync(HOOKS, [
    'elimination: ["say HOOK elimination {player} by {killer} ({cause}) count {count} ban {ban-length}"]',
    'revival: ["say HOOK revival {player} {hearts} ({cause})"]',
    'kill: ["say HOOK kill {killer} took from {victim}: {killer-hearts}/{victim-hearts}"]',
    'streak:',
    '  1: ["/say HOOK streak {player} {streak}"]',
    'streak-ended: []',
    'bounty-claimed: ["say HOOK bounty {killer} collected {hearts} on {victim}"]',
    'season-reset: ["say HOOK season {old} -> {season}, winner {winner}"]',
    ''].join('\n'))
  await rcon('pardon Bob')
  for (const n of ['Alice', 'Bob', 'Cara']) { await enterWatching(n); await rcon(`gamemode survival ${n}`) }
  await rcon('op Alice')
  for (const [n, h] of [['Alice', 10], ['Bob', 2], ['Cara', 10]]) await rcon(`lifesteal set ${n} ${h}`)
  write('UPDATE players SET shield_ms = 0, streak = 0'); write('DELETE FROM bounties')
  await rcon('lifesteal config eliminations.ban.enabled false')
  // Bob is killed again and again; the ledger has to keep his eliminations, so no clearing it.
  await rcon('lifesteal config repeat-kills.enabled false')
  const reload = await rcon('lifesteal reload')
  check('a good hooks.yml reloads without warnings', /looks fine/.test(reload), true)

  // A kill sets off the kill, streak and bounty hooks.
  await say('Cara', '/bounty place Bob 1')
  await kill('Bob', 'Alice')
  check('the kill hook ran with its fill-ins', saw('Cara', 'HOOK kill Alice took from Bob: 11/1'), true)
  check('the streak hook for a streak of 1', saw('Cara', 'HOOK streak Alice 1'), true)
  check('the bounty hook', saw('Cara', 'HOOK bounty Alice collected 1 on Bob'), true)

  // Purgatory as usual while ban mode is off; the elimination and revival hooks.
  const before = eliminations('Bob')
  await kill('Bob', 'Alice')
  const third = ['1d', '3d', '7d'][Math.min(before, 2)]
  check('the elimination hook', saw('Cara', `HOOK elimination Bob by Alice (pvp-death) count ${before + 1} ban ${third}`), true)
  check('ban mode off: Bob isn\'t banned', banned('Bob'), undefined)
  await rcon('lifesteal revive Bob'); await sleep(2000)
  check('the revival hook', saw('Cara', 'HOOK revival Bob 3 (revive)'), true)
  await rejoin('Bob')

  // Ban mode: banned, for the second elimination's length, and kicked.
  // Lengths for this season so far: the next one is the last, so it's the one that repeats.
  const lengths = Array.from({ length: eliminations('Bob') }, () => '5m').concat(['2h'])
  await rcon(`lifesteal config eliminations.ban.lengths [${lengths.join(', ')}]`)
  await rcon('lifesteal config eliminations.ban.enabled true')
  await rcon('lifesteal set Bob 1'); await sleep(1000)
  await kill('Bob', 'Alice'); await sleep(2000)
  const ban = banned('Bob')
  check('ban mode bans the eliminated player', ban && ban.source, 'LifestealPlus')
  const hours = ban ? (new Date(ban.expires.replace(/ ([+-]\d\d)(\d\d)$/, '$1:$2')) - Date.now()) / 3600000 : 0
  check('for this elimination\'s length (2h)', hours > 1.9 && hours <= 2.01, true)
  check('and kicks them with the reason', /banned for 2 hours/.test(kicks.Bob || ''), true)
  check('the elimination hook gives the ban length', saw('Cara', `count ${lengths.length} ban 2h`), true)
  check('they can\'t come back yet', /kicked/.test(await tryEnter('Bob') || ''), true)

  // Brought back by an admin: the ban is lifted and they're back in the game.
  await rcon('lifesteal revive Bob'); await sleep(1500)
  check('a revive lifts the plugin\'s ban', banned('Bob'), undefined)
  delete kicks.Bob
  check('so they can join', await tryEnter('Bob'), null)
  await sleep(2500)
  check('back with revive hearts', hearts('Bob'), 3)
  check('in survival', await gamemode('Bob'), 0)
  check('and told the ban is over', saw('Bob', 'Your ban is over'), true)

  // The ban running out (here: lifted with vanilla /pardon) brings them back on their next join.
  await rcon('lifesteal set Bob 1'); await sleep(1000)
  await kill('Bob', 'Alice'); await sleep(2000)
  check('the last length repeats', !!banned('Bob'), true)
  await rcon('pardon Bob')
  check('once the ban is gone they can join', await tryEnter('Bob'), null)
  await sleep(3000)
  check('and come back with revive hearts', hearts('Bob'), 3)
  check('recorded as the ban being over', [lastLedger('Bob')[0], lastLedger('Bob')[4]], ['REVIVE', 'ban over'])

  // A new season lifts the plugin's bans, never anyone else's.
  await rcon('lifesteal set Bob 1'); await sleep(1000)
  await kill('Bob', 'Alice'); await sleep(2000)
  await rcon('ban Cara not from lifesteal'); await sleep(1000)
  await say('Alice', `/lifesteal season reset Hooks ${Date.now() % 100000}`); await say('Alice', '/lifesteal season confirm', 6000)
  check('a new season lifts elimination bans', banned('Bob'), undefined)
  check('but not other bans', !!banned('Cara'), true)
  await rcon('pardon Cara')
  check('the season hook', saw('Alice', 'HOOK season') && saw('Alice', '-> Hooks '), true)

  // A broken hooks.yml is reported and the old hooks keep working.
  fs.writeFileSync(HOOKS, 'kill: [say {killer}]\non-kill: []\n')
  const broken = await rcon('lifesteal reload')
  check('a hooks.yml that won\'t parse is reported', /hooks.yml couldn't be read/.test(broken), true)
  fs.writeFileSync(HOOKS, 'on-kill: []\nkill: say hi\n')
  const typo = await rcon('lifesteal reload')
  check('a misspelt hook is named', /"on-kill", which isn't a hook/.test(typo) && /kill should be a list/.test(typo), true)

  fs.writeFileSync(HOOKS, fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'hooks.yml')))
  await rcon('lifesteal config eliminations.ban.enabled false')
  await rcon('lifesteal config eliminations.ban.lengths [1d, 3d, 7d]')
  await rcon('lifesteal config repeat-kills.enabled true')
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
