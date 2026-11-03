package dev.lifestealplus;

import dev.lifestealplus.combat.NewbieShield;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.core.PlayerStats;
import dev.lifestealplus.core.PlayerStreak;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The values behind %lifestealplus_...% placeholders. Scoreboards ask many times a second, so
 * nothing here waits on the database: hearts come from the live cache, and the rest (kills,
 * streaks, bounties, the leaderboard) is refreshed every 30 seconds and when a player joins.
 *
 * <p>hearts, max_hearts, eliminated, kills, deaths, streak, best_streak, bounty, shield, season,
 * top_&lt;1-10&gt;_name, top_&lt;1-10&gt;_hearts.
 */
public final class PlaceholderData implements Listener {

    /** The slower-changing numbers for one player. */
    private record Snapshot(int kills, int deaths, int streak, int bestStreak, int bounty) {
    }

    private static final int TOP = 10;

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final NewbieShield shield;
    private final PersonalCaps caps;
    private final Map<UUID, Snapshot> players = new ConcurrentHashMap<>();
    private volatile List<PlayerHearts> top = List.of();
    private volatile String season = "Season 1";

    PlaceholderData(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, NewbieShield shield, PersonalCaps caps) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.shield = shield;
        this.caps = caps;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, 40, 600);
    }

    private void refreshAll() {
        plugin.getServer().getOnlinePlayers().forEach(this::refresh);
        hearts.topHearts(TOP).thenAccept(list -> top = List.copyOf(list));
        hearts.state("season.name").thenAccept(name -> season = name.orElse("Season 1"));
    }

    private void refresh(Player player) {
        UUID id = player.getUniqueId();
        var stats = hearts.stats(player.getName(), null);
        var streak = hearts.streak(id);
        var bounty = hearts.bountyOn(id);
        CompletableFuture.allOf(stats, streak, bounty).thenRun(() -> {
            PlayerStats s = stats.join().orElse(null);
            PlayerStreak k = streak.join().orElse(null);
            Snapshot snapshot = new Snapshot(s == null ? 0 : s.kills(), s == null ? 0 : s.deaths(),
                    k == null ? 0 : k.current(), k == null ? 0 : k.best(), bounty.join());
            if (player.isOnline()) players.put(id, snapshot);
        });
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }

    /** The placeholder's text, or null when it isn't one of ours (PlaceholderAPI then shows it as typed). */
    public String value(OfflinePlayer player, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.equals("season")) return season;
        if (key.startsWith("top_")) return topValue(key);
        if (player == null) return "";
        UUID id = player.getUniqueId();
        Snapshot snap = players.get(id);
        Player online = player.getPlayer();
        return switch (key) {
            case "hearts" -> {
                Integer now = tracker.cachedHearts(id);
                yield now == null ? "" : String.valueOf(Math.max(0, now));
            }
            case "max_hearts" -> String.valueOf(caps.maxFor(id, hearts.rules()));
            case "eliminated" -> online == null ? "" : String.valueOf(tracker.isEliminated(online));
            case "kills" -> snap == null ? "0" : String.valueOf(snap.kills());
            case "deaths" -> snap == null ? "0" : String.valueOf(snap.deaths());
            case "streak" -> snap == null ? "0" : String.valueOf(snap.streak());
            case "best_streak" -> snap == null ? "0" : String.valueOf(snap.bestStreak());
            case "bounty" -> snap == null ? "0" : String.valueOf(snap.bounty());
            case "shield" -> online == null || !shield.isShielded(online) ? "" : Msg.duration(shield.left(online));
            default -> null;
        };
    }

    /** top_1_name, top_3_hearts and so on: the live leaderboard. */
    private String topValue(String key) {
        String[] parts = key.split("_");
        if (parts.length != 3) return null;
        int place;
        try {
            place = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (place < 1 || place > TOP) return null;
        List<PlayerHearts> board = top;
        if (place > board.size()) return "";
        PlayerHearts entry = board.get(place - 1);
        return switch (parts[2]) {
            case "name" -> entry.name();
            case "hearts" -> String.valueOf(entry.hearts());
            default -> null;
        };
    }
}
