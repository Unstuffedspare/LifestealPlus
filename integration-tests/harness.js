// Smoke-test scenario for LifestealPlus. Drives two offline-mode bots and the server
// console (RCON), then checks the plugin's SQLite database after each step.
const mineflayer = require('mineflayer')
const net = require('net')
const { execFileSync } = require('child_process')
const path = require('path')

// The test server's folder (LIFESTEAL_SERVER), and how to reach it.
const SERVER = process.env.LIFESTEAL_SERVER || path.join(__dirname, '..', 'server')
const RCON_PORT = Number(process.env.LIFESTEAL_RCON_PORT || 25598)
const RCON_PASSWORD = process.env.LIFESTEAL_RCON_PASSWORD || 'lsp-local-test'
const GAME_PORT = Number(process.env.LIFESTEAL_PORT || 25599)
const DB = path.join(SERVER, 'plugins', 'LifestealPlus', 'lifesteal.db')
const sleep = ms => new Promise(r => setTimeout(r, ms))
let failures = 0

// On Folia the console can't run vanilla commands that touch entities or blocks (they belong
// to a region), so with LIFESTEAL_FOLIA=1 those go through an op bot at spawn instead, and
// their reply is read from its chat. /lifesteal and everything else still goes over RCON.
const FOLIA = process.env.LIFESTEAL_FOLIA === '1'
const REGION_COMMANDS = /^(damage|kill|execute|data|effect|give|clear|item|tp|teleport|summon|gamemode|advancement|setblock|attribute|recipe|papi|rg) /
async function rcon(cmd) {
  const runner = FOLIA && REGION_COMMANDS.test(cmd) && typeof bots !== 'undefined'
    && Object.keys(bots).map(n => bots[n]).find(b => b && b.entity) || null
  if (!runner) return rawRcon(cmd)
  if (!runner.__opped) { await rawRcon(`op ${runner.username}`); runner.__opped = true; await sleep(300) }
  const lines = []
  const listen = m => lines.push(m)
  runner.on('messagestr', listen)
  runner.chat('/' + cmd)
  await sleep(700)
  runner.removeListener('messagestr', listen)
  return lines.join('\n')
}

function rawRcon(cmd, port = RCON_PORT) {
  return new Promise((resolve, reject) => {
    const s = net.connect(port, '127.0.0.1')
    const pkt = (id, type, body) => {
      const b = Buffer.from(body, 'utf8')
      const buf = Buffer.alloc(14 + b.length)
      buf.writeInt32LE(10 + b.length, 0); buf.writeInt32LE(id, 4); buf.writeInt32LE(type, 8)
      b.copy(buf, 12); return buf
    }
    let data = Buffer.alloc(0), authed = false
    s.on('data', d => {
      data = Buffer.concat([data, d])
      while (data.length >= 4 && data.length >= data.readInt32LE(0) + 4) {
        const len = data.readInt32LE(0), id = data.readInt32LE(4)
        const body = data.slice(12, 4 + len - 2).toString('utf8')
        data = data.slice(4 + len)
        if (!authed) { authed = true; if (id === -1) return reject(new Error('rcon auth')); s.write(pkt(2, 2, cmd)) }
        else { s.end(); resolve(body.replace(/§./g, '')) }
      }
    })
    s.on('error', reject)
    s.write(pkt(1, 3, RCON_PASSWORD))
  })
}

// With LIFESTEAL_MYSQL=<mariadb or mysql client>|<user>|<password>|<host>|<port>|<database>, the
// checks read and write that database (the plugin running with database.type: mysql) instead.
const MYSQL = process.env.LIFESTEAL_MYSQL ? process.env.LIFESTEAL_MYSQL.split('|') : null
function mysql(query) {
  const [client, user, password, host, port, database] = MYSQL
  // The same column names the plugin quotes for MySQL.
  const q = query.replace(/(?<![`\w.])(key|rank|until|value)(?![`\w(])/g, '`$1`')
  return execFileSync(client, [`-u${user}`, `-p${password}`, `-h${host}`, `-P${port}`, '-N', '-B', database, '-e', q]).toString()
}

function sql(query) {
  if (!MYSQL) {
    return execFileSync('python', ['-c',
      'import sqlite3,sys,json; c=sqlite3.connect(sys.argv[1]); print(json.dumps(c.execute(sys.argv[2]).fetchall()))',
      DB, query]).toString().trim()
  }
  const rows = mysql(query).split(/\r?\n/).filter(line => line.length > 0)
  return JSON.stringify(rows.map(line => line.split('\t').map(cell =>
    cell === 'NULL' ? null : /^-?\d+(\.\d+)?$/.test(cell) ? Number(cell) : cell)))
}

/** Changes the plugin's database directly (setting up a scenario). */
function dbWrite(query) {
  if (MYSQL) return mysql(query)
  return execFileSync('python', ['-c', 'import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()', DB, query])
}
const hearts = name => JSON.parse(sql(`SELECT hearts FROM players WHERE name='${name}'`))[0]?.[0]
const lastLedger = name => JSON.parse(sql(
  `SELECT cause, hearts_before, hearts_after, other_name, note, event_id FROM heart_ledger WHERE player_name='${name}' ORDER BY id DESC LIMIT 1`))[0]

function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}: got ${JSON.stringify(actual)}${ok ? '' : ` expected ${JSON.stringify(expected)}`}`)
}

async function gamemode(name) {
  const out = await rcon(`data get entity ${name} playerGameType`)
  return Number((out.match(/data: (\d+)/) || [])[1])
}
async function maxHealth(name) {
  const out = await rcon(`attribute ${name} minecraft:max_health base get`)
  return Number((out.match(/is ([\d.]+)/) || [])[1])
}

function join(name) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: GAME_PORT, username: name, version: '26.1', auth: 'offline', physicsEnabled: false })
    // The bot library's 26.1 movement packets don't survive ViaBackwards; the bots never need to move.
    const write = bot._client.write.bind(bot._client)
    bot._client.write = (name, params) => /^(position|position_look|look|flying)$/.test(name) ? undefined : write(name, params)
    bot.once('spawn', () => resolve(bot))
    bot.once('kicked', r => reject(new Error(`${name} kicked: ${JSON.stringify(r)}`)))
    bot.once('error', reject)
    bot.on('death', () => setTimeout(() => { try { bot.respawn() } catch {} }, 300))
  })
}
const leave = async bot => { bot.quit(); await sleep(1500) }

const chat = { Alice: [], Bob: [], Cara: [] }
const bots = {}
// Listen from the very start: some messages arrive the moment a player joins.
async function enter(n, port = GAME_PORT) {
  if (!chat[n]) chat[n] = []
  bots[n] = await new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: n, version: '26.1', auth: 'offline', physicsEnabled: false })
    const write = bot._client.write.bind(bot._client)
    bot._client.write = (name, params) => /^(position|position_look|look|flying)$/.test(name) ? undefined : write(name, params)
    bot.on('messagestr', m => chat[n].push(m))
    bot.on('death', () => setTimeout(() => { try { bot.respawn() } catch {} }, 300))
    bot.once('spawn', () => resolve(bot))
    bot.once('kicked', r => reject(new Error(`${n} kicked: ${JSON.stringify(r)}`)))
  })
  await sleep(800)
}
async function logOut(n) { bots[n].quit(); await sleep(1200) }
const saw = (n, text) => chat[n].some(m => m.includes(text))
const bodies = async () => Number(((await rcon('execute if entity @e[type=minecraft:mannequin]')).match(/count: (\d+)/i) || [0, 0])[1])
const count = async (name, item) => Number(((await rcon(`minecraft:clear ${name} ${item} 0`)).match(/Found (\d+)/) || [0, 0])[1])
const hit = (victim, attacker) => rcon(`damage ${victim} 1 minecraft:player_attack by ${attacker}`)

