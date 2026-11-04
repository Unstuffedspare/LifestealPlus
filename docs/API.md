# LifestealPlus for developers

Other plugins can read and change hearts, react when they change, and ask whether lifesteal applies at a place. Everything goes through the same rules and the same heart ledger as the rest of the plugin, so a quest plugin handing out a heart is recorded just like a kill.

## Setting up

LifestealPlus isn't on a Maven repository yet, so compile against the jar:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly(files("libs/LifestealPlus-0.1.0.jar"))
}
```

In your `plugin.yml`, load after LifestealPlus:

```yaml
softdepend: [LifestealPlus]   # or depend, if your plugin can't work without it
```

## Getting the API

```java
import dev.lifestealplus.api.LifestealAPI;

LifestealAPI lifesteal = LifestealAPI.get();   // null if LifestealPlus isn't installed
```

Call it in `onEnable` or later, after LifestealPlus has enabled.

## Reading hearts

```java
// Online players: instant, from memory.
lifesteal.onlineHearts(player.getUniqueId())
        .ifPresent(hearts -> player.sendMessage("You have " + hearts + " hearts"));

// Anyone, online or not: from the database, so it's a CompletableFuture.
lifesteal.hearts(uuid).thenAccept(hearts -> {
    if (hearts.isEmpty()) return;              // never joined
    boolean eliminated = hearts.getAsInt() == 0;
});

int cap = lifesteal.maxHearts(uuid);           // their own cap (from a permission) or the server's
```

`hearts()` and `stats()` complete on the plugin's database thread, not the main thread. Before touching the world or a player, hop back:

```java
lifesteal.hearts(uuid).thenAccept(hearts ->
        Bukkit.getScheduler().runTask(myPlugin, () -> { /* main thread */ }));
```

## Changing hearts

```java
// Reward a quest: +1 heart, up to the cap. The reason shows in /lifesteal ledger.
lifesteal.addHearts(uuid, 1, "MyQuests: dragon slain");

// Take one away.
lifesteal.addHearts(uuid, -1, "MyQuests: failed the trial");

// Set exactly. 0 eliminates; more than 0 brings an eliminated player back.
lifesteal.setHearts(uuid, 5, "MyEvents: tournament prize");
```

Each returns a `CompletableFuture<HeartChange>` with the hearts `before()` and `after()`. The same rules apply as everywhere else:

- Gains stop at the player's cap.
- `addHearts` never brings back an eliminated player; `setHearts` does.
- Online players' health, the tab list and placeholders update straight away.

These futures complete on the server's main thread (on Folia, the global region thread), once the change has reached online players. Don't wait on them with `join()` or `get()` from the main thread: that blocks the thread they need.

## Events

All fire on the main thread after the change is saved, so they can't be cancelled. The player may be offline.

| Event | When |
| --- | --- |
| `dev.lifestealplus.event.HeartsChangedEvent` | Any change at all |
| `dev.lifestealplus.event.PlayerEliminatedEvent` | Someone reaches 0 hearts |
| `dev.lifestealplus.event.PlayerRevivedEvent` | Someone comes back from 0 |

```java
@EventHandler
public void onHearts(HeartsChangedEvent event) {
    HeartChange change = event.change();
    if (change.cause() == HeartCause.PVP_KILL && change.after() >= 20) {
        // someone just reached 20 hearts by killing
    }
}
```

`HeartChange` has `player()`, `name()`, `cause()`, `before()`, `after()`, `delta()`, `otherName()` (the killer or victim, when there is one), `eliminatedNow()` and `revivedNow()`. `HeartCause` says why, for example `PVP_KILL`, `PVP_DEATH`, `DEATH`, `HEART_ITEM`, `BOUNTY_CLAIM`, `REVIVE`, `ADMIN_SET`, `PLUGIN_ADD` and `PLUGIN_SET`.

## Stats and places

```java
lifesteal.stats("Alice").thenAccept(stats -> stats.ifPresent(s ->
        System.out.println(s.kills() + " kills, " + s.deaths() + " deaths this season")));

boolean counts = lifesteal.lifestealAt(location);   // false in excluded worlds and WorldGuard regions flagged off
```

## Placeholders

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed, these work anywhere it's supported:

| Placeholder | Value |
| --- | --- |
| `%lifestealplus_hearts%` | Hearts right now |
| `%lifestealplus_max_hearts%` | Their cap |
| `%lifestealplus_eliminated%` | `true` or `false` |
| `%lifestealplus_kills%`, `%lifestealplus_deaths%` | This season |
| `%lifestealplus_streak%`, `%lifestealplus_best_streak%` | Kill streaks |
| `%lifestealplus_bounty%` | Hearts of bounty on them |
| `%lifestealplus_shield%` | Newbie shield time left (empty when there's none) |
| `%lifestealplus_season%` | The season's name |
| `%lifestealplus_top_1_name%` ... `%lifestealplus_top_10_name%` | The leaderboard, by hearts |
| `%lifestealplus_top_1_hearts%` ... `%lifestealplus_top_10_hearts%` | Their hearts |

Hearts update instantly. Kills, streaks, bounties and the leaderboard refresh every 30 seconds.

## WorldGuard flags

With [WorldGuard](https://enginehub.org/worldguard) installed, three region flags are added. All default to allow.

| Flag | Set to deny and... |
| --- | --- |
| `lifesteal` | nothing lifesteal happens: no hearts move, no combat tags, no newbie shields, no Most Wanted |
| `lifesteal-natural-loss` | dying to anything but a player costs no hearts (a parkour course, a mob arena) |
| `lifesteal-combat-tag` | hitting someone doesn't put either of you in combat (a market, a spawn hub) |

```
/rg flag spawn lifesteal deny
```
