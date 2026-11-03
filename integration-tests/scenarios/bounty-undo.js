const say = async (n, text, wait = 1500) => { bots[n].chat(text); await sleep(wait) }
const write = q => execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, q])
const openOn = name => JSON.parse(sql(`SELECT COALESCE(SUM(b.hearts),0) FROM bounties b JOIN players p ON p.uuid=b.target WHERE p.name='${name}' AND b.closed_at IS NULL`))[0][0]

async function main() {
  await rcon('difficulty peaceful')
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`)
  }
  write('UPDATE players SET shield_ms = 0'); write('DELETE FROM bounties')
  await sleep(1500)

  await say('Alice', '/bounty place Bob 3')
  check('the bounty is placed', [hearts('Alice'), openOn('Bob')], [7, 3])
  await say('Alice', '/bounty undo Bob')
  check('undo refunds it in full', [hearts('Alice'), openOn('Bob')], [10, 0])
  check('Alice is told', saw('Alice', 'You took back 3 heart(s) of bounty. You have 10 hearts.'), true)
  check('everyone sees it go, placer unnamed', saw('Bob', 'The bounty on Bob was taken back.') && !chat.Bob.some(m => m.includes('Alice took')), true)

  await say('Alice', '/bounty place Bob 2')
  let hitReply = ''
  for (let i = 0; i < 20 && !hitReply.includes('Applied'); i++) { hitReply = await rcon('damage Bob 1 minecraft:player_attack by Alice'); if (!hitReply.includes('Applied')) await sleep(500) }
  console.log('     hit:', hitReply.trim()); await sleep(500)
  await say('Alice', '/bounty undo Bob')
  check('not while the target is in a fight', saw('Alice', 'Bob is in a fight right now, so the bounty stays'), true)
  check('so it stays', openOn('Bob'), 2)
  await sleep(16000)
  await say('Alice', '/bounty undo Bob')
  check('once the fight is over, it can be taken back', [hearts('Alice'), openOn('Bob')], [10, 0])

  await rcon('lifesteal config bounties.undo-minutes 0')
  await say('Alice', '/bounty place Bob 1')
  await say('Alice', '/bounty undo Bob')
  check('switched off, nothing comes back', saw('Alice', "Bounties can't be taken back on this server"), true)
  await rcon('lifesteal config bounties.undo-minutes 5')
  await rcon('lifesteal bounty clear Bob'); await sleep(800)
  await say('Bob', '/bounty undo Alice')
  check('without a bounty, the reason is given', saw('Bob', "You don't have a bounty on Alice"), true)

  await rcon('difficulty normal')
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
