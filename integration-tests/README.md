# Integration tests

The unit tests (`./gradlew test`) check the heart rules and the database. These check the rest: two bot players join a real Paper server running the plugin, and each scenario plays something out (kills, events, menus, commands, restarts) and checks the result in chat, on the server and in the database.

## What you need

- **Node.js 20 or later** and **Python 3** (the scenarios read the plugin's database with Python's `sqlite3`).
- **A test server**: Paper 26.3 with these plugins:
  - LifestealPlus (`build/libs/LifestealPlus-<version>.jar`)
  - [ViaVersion](https://modrinth.com/plugin/viaversion) and [ViaBackwards](https://modrinth.com/plugin/viabackwards): the bot library speaks an older protocol.
  - For `placeholderapi-worldguard` only: [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi), [WorldGuard](https://modrinth.com/plugin/worldguard) and [WorldEdit](https://modrinth.com/plugin/worldedit).
- **Server settings** in `server.properties`:

  ```properties
  online-mode=false
  enable-rcon=true
  rcon.port=25598
  rcon.password=lsp-local-test
  server-port=25599
  level-type=minecraft\:flat
  ```

  Then run `whitelist off` in the console once. A flat world keeps the bots off slopes and away from caves.

Paper refuses to run from a folder whose path contains `+` or `!`, so keep the server outside this repository's folder.

## Running

```bash
npm install
```

```bash
LIFESTEAL_SERVER=/path/to/test-server node run.js stats-schedules-dashboard
```

`node run.js --list` lists the scenarios. Each prints a PASS or FAIL line per check and ends with `ALL PASSED` or the number that failed. It exits with 0 only when everything passed.

Other settings, if yours differ: `LIFESTEAL_RCON_PORT`, `LIFESTEAL_RCON_PASSWORD`, `LIFESTEAL_PORT`.

## Notes

- **Start each scenario on a fresh config.** Scenarios change settings and put most of them back, but not always. Copy the shipped `config.yml` into the server's `plugins/LifestealPlus/` first (`unzip -p LifestealPlus-<version>.jar config.yml > plugins/LifestealPlus/config.yml`).
- **`audit-backups-menus`** restores a backup across a restart. Run it with no argument, restart the server, then run it again with `after`.
- **`lifestealz-import`** needs a fake LifeStealZ install first: `python scenarios/lifestealz-import-setup.py <server folder>`.
- **Explosion damage** is checked on Normal difficulty: Peaceful turns it off.
- **`ranks`** needs [LuckPerms](https://modrinth.com/plugin/luckperms) on the test server.
- **Folia:** run a Folia 26.3 server instead, set `LIFESTEAL_FOLIA=1`, and run `folia`. On Folia the console can't run vanilla commands that touch entities, so the harness sends those through an op bot. Folia also doesn't let players use `/data`, so the other scenarios' health and dimension checks don't work there; `folia` checks the same things through the database and `execute if entity` instead.
- **Bots can't fight for real** through ViaBackwards, so kills use `/damage <victim> 1000 minecraft:player_attack by <killer>`. That's the same kill as far as the plugin can tell.
