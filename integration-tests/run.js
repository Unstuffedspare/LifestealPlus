// Runs one scenario against the test server: node run.js <scenario> [arguments], or node run.js --list.
// Each scenario is the shared harness (bots, RCON, database checks) followed by its own steps.
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')

const scenarios = path.join(__dirname, 'scenarios')
const name = process.argv[2]
const available = fs.readdirSync(scenarios).filter(f => f.endsWith('.js')).map(f => f.slice(0, -3)).sort()

if (!name || name === '--list') {
  console.log('Scenarios:\n  ' + available.join('\n  '))
  process.exit(name ? 0 : 1)
}
if (!available.includes(name)) {
  console.error(`No scenario called ${name}. node run.js --list shows them.`)
  process.exit(1)
}

const build = path.join(__dirname, '.build')
fs.mkdirSync(build, { recursive: true })
const file = path.join(build, name + '.js')
fs.writeFileSync(file, fs.readFileSync(path.join(__dirname, 'harness.js'), 'utf8') + '\n'
  + fs.readFileSync(path.join(scenarios, name + '.js'), 'utf8'))
const result = spawnSync(process.execPath, [file, ...process.argv.slice(3)], { stdio: 'inherit' })
process.exit(result.status ?? 1)
