// /lifesteal on its own: the commands each player can use, and admin tools only for admins.
const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }

async function main() {
  for (const n of ['Alice', 'Bob']) await enter(n)
  await rcon('op Alice'); await rcon('deop Bob'); await sleep(1000)

  await say('Bob', '/lifesteal')
  check('anyone can see the help', saw('Bob', 'LifestealPlus'), true)
  check('with the player commands', saw('Bob', '/hearts [player]') && saw('Bob', '/stats [player] [season]'), true)
  check('but not the admin tools', saw('Bob', 'Admin') || saw('Bob', '/lifesteal settings'), false)
  check('and not what\'s switched off', saw('Bob', '/heartshop'), false)

  await say('Alice', '/lifesteal help')
  check('admins see their tools too', saw('Alice', '/lifesteal settings') && saw('Alice', '/lifesteal dashboard'), true)

  await say('Bob', '/lifesteal give Bob 5')
  check('the help doesn\'t open up the admin commands', saw('Bob', 'now has'), false)

  await rcon('deop Alice')
  for (const b of Object.values(bots)) { try { b.quit() } catch {} }
  await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
