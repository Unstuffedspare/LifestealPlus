# LifestealPlus

A complete lifesteal plugin for Paper. Kill a player and take a heart; lose your last heart and you're out. Around that core it adds the things that keep a lifesteal server alive: a full record of every heart that moves, protection against farming and combat logging, a way back for eliminated players, and events that pull everyone together.

Everything is switchable and every message is editable. A typo in the config never breaks the plugin.

## Features

| Feature | In short | Config section |
|---|---|---|
| Heart ledger | Every heart change is recorded: who, why, before and after. Settles "I lost a heart to a bug" in seconds. | always on |
| Repeat-kill protection | Killing the same player again soon after moves no hearts. Stops farming and spawn-camping. | `repeat-kills` |
| Vengeance | Get killed, and you can take your heart back (plus a bonus) by killing your killer within the time limit. An optional tracker points the way. | `vengeance` |
| Bounties | Stake your own hearts on someone's head. Whoever kills them collects. | `bounties` |
| Kill streaks | Kills in a row are announced, and ending a long streak takes a bonus heart from the streaker. | `streaks` |
| Newbie shield | New players can't be hurt by others, or lose hearts, for their first few hours of playtime (and no more than a few days after joining). | `newbie-shield` |
| Combat logging | Logging out mid-fight leaves your body behind to be killed. | `combat-log` |
| Last Stand | Strength and Speed when you're down to your last heart. | `last-stand` |
| Most Wanted | The player with the most hearts has their rough location announced now and then. | `most-wanted` |
| Rising heart cap | The max hearts can rise week by week through a season. | `hearts.max-by-week` |
| Purgatory | Eliminated players go to their own world and can win their way back. (What happens there is left for players to find out.) | `purgatory` |
| Rifts | Pay hearts to pull an eliminated friend straight back. | `rifts` |
| Revival votes | Players chip in hearts together to bring someone back. | `revival-votes` |
| Blood Pacts | Two players share kill rewards, and each other's deaths. | `blood-pacts` |
| Ghosts | Eliminated players can roam as spectators and whisper to the living once an hour. | `ghosts` |
| Heart items | Hearts you can hold: from `/withdraw`, airdrops, Blood Moons and crafting, and dropped by players who die to mobs. They fade if hoarded. | `heart-items` |
| Blood Moon | A long random night where kills take extra hearts. | `blood-moon` |
| Airdrops | Heart crates land at announced coordinates; now and then a golden one with twice the loot. | `airdrops` |
| Double Hearts, Heart Frenzy, Cap Boost | Short server-wide events: kills take extra hearts; monsters drop Heart Fragments; the cap goes up. Started by an admin or on a schedule. | `double-hearts`, `heart-frenzy`, `cap-boost` |
| King of the Hill | Hold a marked hill alone for longest to win hearts. | `king-of-the-hill` |
| Milestone rewards | Heart Fragments for advancements and for every few hours played, so there's a way to earn hearts without fighting. | `rewards` |
| Revival Charm | A craftable item that brings an eliminated player back. Comebacks get dearer with each elimination, and can wait out a cooldown. | `revival-charm`, `revival` |
| Heart trading and shop | `/giveheart` hands hearts to a friend (with an optional tax); `/heartshop` sells heart items for money through Vault. | `trading`, `heart-shop` |
| Finale | An admin-started season ending: the border closes, last one standing wins. | `finale` |
| Alt detection | Flags kills between accounts that log in from the same address. It only alerts admins, never blocks. See [Privacy](#privacy). | `alt-detection` |
| While you were away | Players coming back get a short summary of what they missed; `/news` shows the latest. | `recap` |
| Tab list | Hearts (and pacts) next to names in the tab list. | `tab-list` |
| Seasons | Archive a season's standings and start fresh; past seasons stay viewable. | `seasons` |

## Installing

1. You need **Paper 26.3** and **Java 25**.
2. Put `LifestealPlus-<version>.jar` in your server's `plugins` folder and start the server.
3. The first start creates `plugins/LifestealPlus/` with `config.yml`, `messages.yml` and the database, and builds the Purgatory world.

Paper won't run from a folder whose path contains `+` or `!`, so keep the server folder's path free of those.

The plugin is the whole lifesteal system, so don't run another lifesteal plugin alongside it.

### Coming from LifeStealZ

Players keep their hearts when you switch.

1. Stop the server, take the LifeStealZ jar out of `plugins` (leave its `plugins/LifeStealZ` folder where it is), add this plugin and start the server.
2. Run `/lifesteal import lifestealz`. It reads LifeStealZ's `userData.db` and shows what it will do: how many players, who's eliminated, and anyone over your `hearts.max`. Nothing changes yet.
   - If LifeStealZ stored its data in MySQL, run `/lifestealz data export lifestealz` on the old setup first, then `/lifesteal import lifestealz lifestealz` here to read that CSV instead.
3. Run `/lifesteal import confirm` within 60 seconds. LifeStealZ bans eliminated players, so use `/lifesteal import confirm unban` if you want them to be able to join and reach Purgatory.

How hearts carry over:

- LifeStealZ counts half hearts; this plugin doesn't, so 7½ hearts becomes 7.
- Anyone at or below LifeStealZ's `minHearts` arrives eliminated.
- Anyone above your `hearts.max` arrives at the cap.
- Players already on this server have their hearts replaced. Every change is in their `/lifesteal ledger`.

LifeStealZ's heart items turn into this plugin's Hearts when a player joins or opens a chest holding one (`lifestealz-import.convert-items`). Its revive items have no equivalent here and are left alone.

## Configuring

**The easy way: `/lifesteal settings` in game.** It opens a menu with every feature.

- **Shift-click a feature** to switch it on or off. **Click it** to see its settings.
- **On/off settings:** click to flip them.
- **Numbers:** left-click to go up, right-click to go down, or shift-click and type the exact value in chat.
- **Lists and text:** click and type the value in chat.

Each setting shows the explanation from `config.yml`. Changes are saved to `config.yml` and take effect straight away. A value the plugin can't use is refused, with the reason, so nothing breaks.

**Presets.** The star button on the menu's first page offers three ready-made mixes. Each one shows exactly what it would change; click it twice to apply.

| Preset | What it's like |
| --- | --- |
| Casual | Only other players cost you hearts, newcomers are protected for longer, and coming back from elimination is easier. |
| Standard | The defaults the plugin ships with. |
| Hardcore | Fewer hearts, short protection, longer combat tags, no crafted Hearts, and a hard road back from elimination. |

A preset only touches the settings presets are about (heart amounts, protection, the way back from elimination and so on), and always gives the same result, whatever was set before. Anything else you've changed stays as it is. From the console: `/lifesteal preset` lists them, `/lifesteal preset hardcore` shows what it would change, and `/lifesteal preset hardcore confirm` applies it.

From the console (or chat), `/lifesteal config <setting> <value>` does the same, for example `/lifesteal config newbie-shield.enabled off`. Press Tab to pick the setting's name, and leave the value out to see what it's set to now.

**Or edit the file.** All settings are in `plugins/LifestealPlus/config.yml`, and every one is explained in the file itself.

- **Every feature has an `enabled: true/false` switch** in its own section. Turn off anything you don't want.
- **Apply file changes with `/lifesteal reload`.** Only `purgatory.enabled`, `purgatory.world` and `database.file` need a restart, and they're marked `(restart)` in the file.
- **Mistakes are safe.** If a value doesn't make sense (a word where a number goes, a number out of range), the plugin uses the default instead. It tells you exactly which setting it skipped, both in the server log and in the reply to `/lifesteal reload`.
- **`/lifesteal features`** lists every feature as on or off, using the same names as `config.yml`.
- **Updating keeps your settings.** New settings from a newer version are added to your existing files automatically, with their explanations, and anything you've changed is left alone.

Some things worth deciding before a season starts:

- `hearts.starting`, `hearts.max` and `hearts.per-kill` set the basic economy.
- `hearts.natural-death-loss` (1): hearts lost dying to anything other than a player. Set it to 0 if only PvP should cost hearts.
- `hearts.natural-death-drops` (on): those hearts drop as glowing Heart items where the player died. They can't burn or despawn, so the player can go back for them, if nobody else gets there first. Off, and the hearts are simply gone.
- `heart-items.recipe` (on): Hearts can be crafted from diamond blocks in the corners, gold blocks on the sides and a netherite ingot in the middle. The three ingredients can be changed (`corners`, `sides`, `middle`), and the recipe appears in everyone's recipe book.
- `worlds.excluded`, e.g. `[creative, events]`: worlds where nothing lifesteal happens. Dying there costs no hearts, kills take none, and combat tags, newbie shields and Most Wanted leave players alone. Use it for a creative, event or minigame world.
- `newbie-shield.hours`: on a fresh season every player is new, so everyone starts shielded for these hours. That works as an opening grace period. Turn it off if you want PvP straight away.
- `newbie-shield.expires-after-days` (3): the shield also ends this many days after a player first joins, however little they've played, so nobody keeps it forever.
- `hearts.max-by-week`, e.g. `[15, 20, 25]`, makes the cap rise through the season. Leave it empty for a fixed cap.
- `bounties.undo-minutes` (5): how long after placing a bounty you can take it back with `/bounty undo`, refunded in full. Not while the target is in a fight, and everyone sees the bounty shrink. 0 turns undo off.
- `blood-moon.schedule` and `airdrops.schedule`, e.g. `[saturday 20:00]` or `[daily 18:00, sunday 15:00]`: fixed times instead of random ones, so everyone can plan around them. `blood-moon.warn-minutes` warns everyone beforehand, and `schedules.time-zone` sets the clock (for example `Europe/London`).
- `revival.cooldown-minutes` (10) and `revival.extra-cost-per-elimination` (1): a fresh elimination can't be undone by a rift, pledge or Revival Charm straight away, and each earlier elimination this season makes the next comeback dearer.
- `rewards.advancements`: the advancements that pay Heart Fragments, and how many. Add any advancement by its id; set one to 0 to give nothing.
- `trading.tax-percent` and `heart-items.withdraw.tax-percent` (both 0): a share of every gift or withdrawal that's lost, to slow hearts moving around.
- `heart-shop`: off until you switch it on. Needs Vault and an economy plugin. `daily-limit` (2) stops money buying a season.
- `eliminations`: everyone sees a title and hears a sound when someone loses their last heart. The chat message differs for a kill, a death to the world and an admin, and each is in `messages.yml`.
- `combat-items`: switch off totems (`totems`), crystal PvP (`end-crystals`; crystals still break blocks and respawn the dragon) or bed and respawn-anchor bombing (`bed-and-anchor-explosions`).
- Personal caps: give a player the permission `lifestealplus.max-hearts.<n>` (for example `lifestealplus.max-hearts.30`, with a permissions plugin such as LuckPerms) and their cap is n instead of `hearts.max`. The highest one counts, it's picked up within a minute, and losing it never takes hearts away.
- `discord.webhook-url`: paste a Discord channel's webhook link (channel settings > Integrations > Webhooks) and the server's news (eliminations, comebacks, long streaks, bounties, events) is posted there within seconds. `none` turns it off.
- `ghosts.limit-chat` stops eliminated players talking freely to the living, so their hourly whisper matters. Turn it off for a more relaxed server.

### Messages

Every message players see is in `plugins/LifestealPlus/messages.yml`.

- **Change the wording or colours** using [MiniMessage](https://docs.advntr.dev/minimessage/format.html) tags like `<red>`, `<bold>` or `<gradient:red:gold>`.
- **Keep the words in angle brackets.** Words like `<player>` and `<hearts>`, listed in each message's comment, are filled in by the plugin.
- **Silence a message** by setting it to `""`.
- **Apply changes with `/lifesteal reload`.**

## Commands

### Players

| Command | What it does | Permission |
|---|---|---|
| `/hearts [player]` | Show your hearts, or someone else's | `lifestealplus.hearts`, `lifestealplus.hearts.others` |
| `/leaderboard [season]` | Most hearts this season, or a past season's final standings | `lifestealplus.leaderboard` |
| `/seasons` | Past seasons and who won them | `lifestealplus.leaderboard` |
| `/shield` | How long your newbie shield lasts | `lifestealplus.shield` |
| `/streak [player]` | Kills in a row right now, and the best this season | `lifestealplus.streak` |
| `/news` | The latest eliminations, comebacks, streaks, bounties and events | `lifestealplus.news` |
| `/stats [player] [season]` | Kills, deaths, hearts won and lost, bounties and comebacks, this season or a past one | `lifestealplus.stats` |
| `/vengeance` | Who you can take vengeance on, and who can on you | `lifestealplus.vengeance` |
| `/bounty [list]`, `/bounty check <player>`, `/bounty place <player> <hearts>`, `/bounty undo <player>` | See, place and take back bounties | `lifestealplus.bounty` |
| `/withdraw [hearts]` | Turn your hearts into Heart items | `lifestealplus.withdraw` |
| `/giveheart <player> <hearts>`, `/giveheart confirm` | Give some of your hearts to another player | `lifestealplus.giveheart` |
| `/heartshop`, `/heartshop buy heart\|fragment\|charm [amount]` | Buy heart items with money (needs Vault and `heart-shop.enabled`) | `lifestealplus.heartshop` |
| `/rift <player>`, then `/rift confirm` | Pay hearts to bring an eliminated player back | `lifestealplus.rift` |
| `/pledge <player> <hearts>`, `/pledge cancel <player>`, `/pledge list` | Chip in toward reviving someone | `lifestealplus.pledge` |
| `/pact <player>`, `/pact accept <player>`, `/pact break`, `/pact list` | Blood Pacts | `lifestealplus.pact` |
| `/purgatory`, `/purgatory start`, `/purgatory join` | While eliminated: your progress, and trials | `lifestealplus.purgatory` |
| `/ghost` | While eliminated: roam as a ghost, or go back | `lifestealplus.ghost` |
| `/whisper <player> <message>` | While eliminated: reach one living player, once an hour | `lifestealplus.whisper` |

Every player permission is allowed by default. Take one away with your permissions plugin to block that command for a group.

### Admins (`/lifesteal`, or `/lsp`; permission `lifestealplus.admin`, ops by default)

| Command | What it does |
|---|---|
| `settings` | Menu for changing any setting in game |
| `config <setting> [value]` | See or change one setting (works from the console) |
| `preset [name] [confirm]` | List the presets, see what one would change, or apply it |
| `features` | Every feature, on or off |
| `reload` | Re-read `config.yml` and `messages.yml`, and report any settings that couldn't be used |
| `give / take / set <player> <hearts>` | Change hearts (works on offline players) |
| `revive <player> [hearts]` | Bring an eliminated player back |
| `ledger <player> [page]` | Every heart change for a player, newest first |
| `bounty clear <player>` | Remove the bounties on someone and refund whoever placed them |
| `shield <player> <minutes>` | Give a newbie shield, or remove it with `0`. A given shield gets a fresh day limit |
| `alts <player>` | Accounts that share an address with this one |
| `flags` | Recent kills between accounts that share an address |
| `season`, `season reset <next name>`, `season confirm` | See the season; end it (archives standings, then wipes hearts) |
| `event status` | What's running, and when the next Blood Moon, airdrop and scheduled events are due |
| `event doublehearts\|frenzy\|capboost start [minutes]`, `... stop` | Run or end Double Hearts, a Heart Frenzy or a Cap Boost |
| `event koth start [minutes]`, `event koth stop` | King of the Hill where you stand (at spawn from the console) |
| `event bloodmoon start [minutes]`, `event bloodmoon stop` | Run or end a Blood Moon now |
| `event airdrop`, `event airdrop here` | Drop a heart crate now (at a random spot, or next to you) |
| `finale start <minutes> [final size]`, `finale stop` | Start or call off the season finale |
| `item <player> heart\|fragment\|charm [amount]` | Hand out heart items and Revival Charms |
| `mostwanted` | Announce the Most Wanted now |
| `dashboard` | The server at a glance: the season, hearts in play and where this week's came from, upcoming events, latest eliminations |
| `import lifestealz [file]`, `import confirm [unban]` | Bring players' hearts over from LifeStealZ (see above) |

A season reset doesn't touch the world or inventories, so start the new season on a fresh map if you want one.

## Works with

All optional: LifestealPlus runs fine without any of them.

- **[PlaceholderAPI](https://modrinth.com/plugin/placeholderapi):** `%lifestealplus_hearts%`, `%lifestealplus_max_hearts%`, `%lifestealplus_kills%`, `%lifestealplus_streak%`, `%lifestealplus_bounty%`, `%lifestealplus_eliminated%`, leaderboard placeholders (`%lifestealplus_top_1_name%`) and more, for scoreboards, TAB and holograms. The full list is in [docs/API.md](docs/API.md#placeholders).
- **[WorldGuard](https://enginehub.org/worldguard):** region flags `lifesteal`, `lifesteal-natural-loss` and `lifesteal-combat-tag`. For example, `/rg flag spawn lifesteal deny` makes spawn a place where nothing lifesteal happens. See [docs/API.md](docs/API.md#worldguard-flags).
- **LuckPerms** (or any permissions plugin): every command has its own permission, and `lifestealplus.max-hearts.<n>` gives a rank its own heart cap.
- **Other plugins:** a developer API with heart-change events. See [docs/API.md](docs/API.md).

## Privacy

Alt detection is the only feature that records anything about players' connections.

- **What's stored:** for each account, a salted SHA-256 hash of each address it logged in from, with when it was first and last seen. The address itself is never written down.
- **What that protects against:** the hashes keep addresses out of sight in the database and in admin commands. They aren't a lock: the salt is kept in the same database, so anyone with the database file could work out an address by trying every possible one.
- **How long:** an address is forgotten `alt-detection.keep-days` (90) days after it was last used.
- **False matches:** family members, housemates and players on the same school or public network share an address. That's why a match only alerts admins and never blocks or punishes anyone.
- **Turning it off:** set `alt-detection.enabled` to false and nothing more is recorded. Existing records are forgotten as they reach the keep-days limit.

The update check (`updates.check`) fetches the public list of releases from GitHub when the server starts and twice a day. It sends nothing about your server.

## Building from source

```
./gradlew build
```

The plugin jar ends up in `build/libs/`. The build uses Java 25 and downloads it automatically if it's missing.

## Licence

MIT. See [LICENSE](LICENSE).
