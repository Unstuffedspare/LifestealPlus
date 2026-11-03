const say = async (n, text, wait = 1300) => { bots[n].chat(text); await sleep(wait) }
const http = require('http')
const write = dbWrite
const health = async n => Number(((await rcon(`data get entity ${n} Health`)).match(/([\d.]+)f\s*$/) || [0, -1])[1])
async function hurt(cmd) {
  for (let i = 0; i < 20; i++) { const out = await rcon(cmd); if (!out.includes('invulnerable')) return out; await sleep(500) }
}

// A stand-in for Discord: records every post.
const posts = []
const fake = http.createServer((req, res) => {
  let body = ''
  req.on('data', d => body += d)
  req.on('end', () => { posts.push({ path: req.url, body: JSON.parse(body) }); res.writeHead(204); res.end() })
}).listen(8099)

async function main() {
  await rcon('difficulty peaceful')
  for (const n of ['Alice', 'Bob']) {
    await enter(n)
    await rcon(`gamemode survival ${n}`); await rcon(`effect clear ${n}`); await rcon(`lifesteal set ${n} 10`); await rcon(`clear ${n}`)
  }
  write(`UPDATE players SET shield_ms = 0`); write('DELETE FROM bounties')
  await sleep(1500)

  // ---- Totems
  await rcon('item replace entity Bob weapon.offhand with minecraft:totem_of_undying')
  await hurt('damage Bob 1000 minecraft:generic'); await sleep(2500)
  check('a totem saves Bob by default', hearts('Bob'), 10)
  await rcon('lifesteal config combat-items.totems off')
  await rcon('effect clear Bob'); await rcon('item replace entity Bob weapon.offhand with minecraft:totem_of_undying'); await sleep(1500)
  await hurt('damage Bob 1000 minecraft:generic'); await sleep(2500)
  check('switched off, the totem doesn\'t', hearts('Bob'), 9)
  check('and Bob is told why', saw('Bob', 'Totems of Undying are switched off'), true)
  await rcon('lifesteal config combat-items.totems on')
  await rcon('lifesteal set Bob 10'); await rcon('kill @e[type=minecraft:item]'); await sleep(1500)

  // ---- End crystals (Peaceful scales explosion damage to nothing, so these run on Normal)
  await rcon('difficulty normal')
  await rcon('summon minecraft:end_crystal 5 -60 5 {ShowBottom:0b}')
  await rcon('effect give Bob minecraft:instant_health 1 10 true'); await sleep(1200)
  let h = await health('Bob')
  await hurt('damage Bob 6 minecraft:explosion by @e[type=minecraft:end_crystal,limit=1]'); await sleep(800)
  check('a crystal explosion hurts by default', (await health('Bob')) < h, true)
  await rcon('lifesteal config combat-items.end-crystals off')
  await rcon('effect give Bob minecraft:instant_health 1 10 true'); await sleep(1500)
  h = await health('Bob')
  await hurt('damage Bob 6 minecraft:explosion by @e[type=minecraft:end_crystal,limit=1]'); await sleep(800)
  check('switched off, it doesn\'t', await health('Bob'), h)
  await rcon('lifesteal config combat-items.end-crystals on')
  await rcon('kill @e[type=minecraft:end_crystal]')

  // ---- Beds and anchors ("Intentional Game Design")
  await rcon('lifesteal config combat-items.bed-and-anchor-explosions off')
  await rcon('effect give Bob minecraft:instant_health 1 10 true'); await sleep(1500)
  h = await health('Bob')
  await hurt('damage Bob 6 minecraft:bad_respawn_point'); await sleep(800)
  check('switched off, a bed or anchor blast doesn\'t hurt', await health('Bob'), h)
  await rcon('lifesteal config combat-items.bed-and-anchor-explosions on')
  await hurt('damage Bob 6 minecraft:bad_respawn_point'); await sleep(800)
  check('switched on, it does', (await health('Bob')) < h, true)
  await rcon('effect give Bob minecraft:instant_health 1 10 true')

  // ---- Discord
  const r = await rcon('lifesteal config discord.webhook-url http://127.0.0.1:8099/hook')
  check('the webhook link is accepted', r.includes('discord.webhook-url is now'), true)
  const refused = await rcon('lifesteal config discord.webhook-url discord.com/oops')
  check('a link without https:// is refused', refused.includes('Not changed'), true)
  await say('Bob', '/bounty place Alice 1', 9000)
  check('news reaches Discord within seconds', posts.length, 1)
  const post = posts[0]?.body || {}
  console.log('     posted:', JSON.stringify(post))
  check('as plain text about the bounty', /Alice/.test(post.content || '') && !/<\w+>/.test(post.content || ''), true)
  check('with mentions switched off', JSON.stringify(post.allowed_mentions), '{"parse":[]}')
  await rcon('lifesteal config discord.webhook-url none')
  await rcon('lifesteal bounty clear Alice'); await sleep(500)
  await say('Bob', '/bounty place Alice 1', 8000)
  check('switched off, nothing more is posted', posts.length, 1)
  await rcon('lifesteal bounty clear Alice')
  await rcon('lifesteal config discord.webhook-url http://127.0.0.1:8099/hook'); await sleep(8000)
  check('switching back on doesn\'t post the backlog', posts.length, 1)
  await rcon('lifesteal config discord.webhook-url none')

  // ---- Heart caps: nobody here has a cap permission, so joining clears any saved cap.
  write(`UPDATE players SET max_hearts = 25 WHERE name = 'Bob'`)
  await logOut('Bob'); await enter('Bob'); await sleep(1500)
  check('joining without a cap permission clears a saved cap', JSON.parse(sql(`SELECT max_hearts FROM players WHERE name='Bob'`))[0][0], null)

  await rcon('difficulty normal')
  const errors = require('child_process').execFileSync('python', ['-c',
    "import sys; print(sum(1 for l in open(sys.argv[1], encoding='utf8', errors='replace') if 'Could not pass event' in l or 'ommand failed' in l or 'missing message' in l or 'LifestealPlus' in l and ('SEVERE' in l or 'ERROR' in l)))",
    path.join(SERVER, 'server.out')]).toString().trim()
  check('no plugin errors logged', errors, '0')
  for (const b of Object.values(bots)) { try { b.quit() } catch {} }
  fake.close(); await sleep(500)
  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch(e => { console.error('SCENARIO ERROR', e); process.exit(2) })
