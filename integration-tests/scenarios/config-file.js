const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
const fs = require('fs')
const folder = path.join(SERVER, 'plugins', 'LifestealPlus')
const cfgFile = path.join(folder, 'config.yml'), msgFile = path.join(folder, 'messages.yml')
const edit = (file, from, to) => {
  const text = fs.readFileSync(file, 'utf8')
  if (!text.includes(from)) throw new Error(`${path.basename(file)} has no "${from}"`)
  fs.writeFileSync(file, text.replace(from, to))
}
const tabName = (viewer, who) => {
  const p = bots[viewer].players[who]
  return p && p.displayName ? p.displayName.toString() : ''
}

async function main() {
  for (const n of ['Alice', 'Bob', 'Cara']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  await rcon('op Alice')
  await sleep(3000)

  check('messages.yml was created', fs.existsSync(msgFile), true)

  await say('Alice', '/lifesteal features')
  check('/lifesteal features lists features by config name', saw('Alice', 'LifestealPlus features') && chat.Alice.some(m => /on\s+repeat-kills/.test(m)), true)

  // A typo and a switched-off feature, then reload.
  const originalCfg = fs.readFileSync(cfgFile, 'utf8')
  edit(cfgFile, '  max: 20\n', '  max: twenty\n')
  edit(cfgFile, 'bounties:\n  # /bounty place <player> <hearts>: stake your own hearts on someone\'s head.\n  # Whoever kills them collects the lot.\n  enabled: true',
                'bounties:\n  # /bounty place <player> <hearts>: stake your own hearts on someone\'s head.\n  # Whoever kills them collects the lot.\n  enabled: false')
  await say('Alice', '/lifesteal reload', 2000)
  check('reload says a setting was skipped', saw('Alice', 'reloaded, but 1 setting(s)'), true)
  check('and names it', saw('Alice', 'hearts.max should be a whole number'), true)
  check('the server keeps running with the default', hearts('Alice'), 10)
  await say('Bob', '/bounty place Cara 1')
  check('a switched-off feature says so', saw('Bob', "That's switched off on this server"), true)
  check('and nothing was taken', hearts('Bob'), 10)
  await say('Alice', '/lifesteal features')
  check('/lifesteal features shows it off', chat.Alice.some(m => /off\s+bounties/.test(m)), true)
  fs.writeFileSync(cfgFile, originalCfg)

  // Rewording and silencing messages.
  const originalMsgs = fs.readFileSync(msgFile, 'utf8')
  edit(msgFile, 'show: "<red>❤</red> <player> has <hearts>/<max> hearts."', 'show: "<gold>Custom: <player> sits on <hearts> hearts"')
  edit(msgFile, 'eliminated: "<dark_red><bold><player></bold> has lost their last heart and is eliminated."', 'eliminated: ""')
  await say('Alice', '/lifesteal reload', 2000)
  check('a clean reload says so', saw('Alice', 'Everything in config.yml and messages.yml looks fine'), true)
  await say('Bob', '/hearts')
  check('a reworded message is used', saw('Bob', 'Custom: Bob sits on 10 hearts'), true)
  await rcon('lifesteal set Cara 0'); await sleep(2500)
  check('a blanked message is not sent', chat.Bob.some(m => m.includes('has lost their last heart')), false)
  await rcon('lifesteal revive Cara 10'); await sleep(1500)
  fs.writeFileSync(msgFile, originalMsgs)
  await say('Alice', '/lifesteal reload', 1500)

  // Tab list
  await sleep(2500)
  check('tab list shows hearts', tabName('Cara', 'Bob').includes('10❤'), true)
  await say('Alice', '/pact Bob'); await say('Bob', '/pact accept Alice')
  console.log('     waiting for the pact to reach the tab list...')
  await sleep(32000)
  // The bot library mangles emoji, so look for the partner's name next to Alice's.
  check('tab list shows the pact', /Alice.*Bob/.test(tabName('Cara', 'Alice')), true)
  await say('Cara', '/pact list')
  check('/pact list shows it', saw('Cara', 'Blood Pacts') && chat.Cara.some(m => /Alice.*Bob/.test(m)), true)
  await say('Alice', '/pact break')

  // Seasons
  await say('Cara', '/seasons')
  check('/seasons shows the current season', saw('Cara', 'Current season:'), true)

  // Messages file upgrade: a message missing from an old messages.yml comes back.
  edit(msgFile, '  usage: "<red>Usage: /hearts <player>"\n', '')
  await say('Alice', '/lifesteal reload', 1500)
  check('a missing message is restored on reload', fs.readFileSync(msgFile, 'utf8').includes('Usage: /hearts <player>'), true)

  await rcon('deop Alice')
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'ommand failed' in l or 'missing message' in l))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  for (const b of Object.values(bots)) b.quit()
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
