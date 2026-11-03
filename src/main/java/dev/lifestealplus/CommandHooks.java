package dev.lifestealplus;

import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.KillOutcome;
import dev.lifestealplus.event.PlayerEliminatedEvent;
import dev.lifestealplus.event.PlayerRevivedEvent;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * hooks.yml: console commands run when things happen (an elimination, a revival, a kill, a
 * streak, a bounty, a new season), with fill-ins like {player}. Kept out of config.yml so the
 * settings menu can't be used to run console commands.
 */
public final class CommandHooks implements Listener {

    static final Set<String> HOOKS = Set.of("elimination", "revival", "kill", "streak", "streak-ended",
            "bounty-claimed", "season-reset", "event-start", "event-end", "airdrop-landed", "airdrop-opened",
            "koth-won", "finale-won");
    /** The server events event-start and event-end list commands for, by name. */
    public static final Set<String> EVENTS = Set.of("blood-moon", "double-hearts", "heart-frenzy", "cap-boost",
            "king-of-the-hill", "finale");

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private volatile YamlConfiguration hooks = new YamlConfiguration();

    CommandHooks(LifestealPlugin plugin, HeartService hearts) {
        this.plugin = plugin;
        this.hearts = hearts;
        reload();
    }

    /** Re-reads hooks.yml; returns what's wrong with it (a misspelt hook, a hook that isn't a list). */
    List<String> reload() {
        File file = new File(plugin.getDataFolder(), "hooks.yml");
        if (!file.exists()) plugin.saveResource("hooks.yml", false);
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            // Keep the hooks already running rather than dropping them all over one typo.
            String warning = "hooks.yml couldn't be read, so the hooks are unchanged (put quotes around a command that"
                    + " starts with { or has : in it): " + e.getMessage().lines().findFirst().orElse("");
            plugin.getLogger().warning(warning);
            return List.of(warning);
        }
        List<String> warnings = problems(loaded);
        warnings.forEach(w -> plugin.getLogger().warning(w));
        hooks = loaded;
        return warnings;
    }

    static List<String> problems(YamlConfiguration loaded) {
        List<String> warnings = new java.util.ArrayList<>();
        for (String key : loaded.getKeys(false)) {
            if (!HOOKS.contains(key)) {
                warnings.add("hooks.yml has \"" + key + "\", which isn't a hook; the hooks are " + String.join(", ", HOOKS.stream().sorted().toList()) + ".");
            } else if (key.equals("streak") || key.startsWith("event-")) {
                boolean streak = key.equals("streak");
                var section = loaded.getConfigurationSection(key);
                if (section == null) {
                    if (!(loaded.get(key) instanceof Map<?, ?> map && map.isEmpty())) {
                        warnings.add("hooks.yml: " + key + (streak ? " should list streak numbers" : " should list events")
                                + ", each with its commands; ignoring it.");
                    }
                    continue;
                }
                for (String n : section.getKeys(false)) {
                    if (streak && !n.matches("\\d{1,4}")) {
                        warnings.add("hooks.yml: streak." + n + " should be a streak number like 5; ignoring it.");
                    } else if (!streak && !EVENTS.contains(n)) {
                        warnings.add("hooks.yml: " + key + "." + n + " isn't an event; the events are "
                                + String.join(", ", EVENTS.stream().sorted().toList()) + ".");
                    } else if (!section.isList(n)) {
                        warnings.add("hooks.yml: " + key + "." + n + " should be a list of commands; ignoring it.");
                    }
                }
            } else if (!loaded.isList(key)) {
                warnings.add("hooks.yml: " + key + " should be a list of commands, like [\"say hi {player}\"]; ignoring it.");
            }
        }
        return warnings;
    }

    @EventHandler
    void onEliminated(PlayerEliminatedEvent event) {
        HeartChange change = event.change();
        if (hooks.getStringList("elimination").isEmpty()) return;
        hearts.eliminations(change.player()).thenAccept(count -> {
            Map<String, Object> fills = player(change);
            fills.put("killer", change.otherName() == null ? "nobody" : name(change.otherName()));
            fills.put("count", count);
            fills.put("ban-length", plugin.settings().bans().lengthText(count));
            fills.put("ban-time", plugin.settings().bans().lengthOrNothing(count));
            run("elimination", fills);
        });
    }

    @EventHandler
    void onRevived(PlayerRevivedEvent event) {
        Map<String, Object> fills = player(event.change());
        fills.put("hearts", event.change().after());
        run("revival", fills);
    }

    private static Map<String, Object> player(HeartChange change) {
        Map<String, Object> fills = new LinkedHashMap<>();
        fills.put("player", name(change.name()));
        fills.put("uuid", change.player());
        fills.put("cause", change.cause().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        return fills;
    }

    /** Everything a kill can set off. */
    void kill(KillOutcome outcome) {
        Map<String, Object> fills = new LinkedHashMap<>();
        fills.put("killer", name(outcome.killer().name()));
        fills.put("victim", name(outcome.victim().name()));
        fills.put("killer-uuid", outcome.killer().player());
        fills.put("victim-uuid", outcome.victim().player());
        fills.put("killer-hearts", outcome.killer().after());
        fills.put("victim-hearts", Math.max(0, outcome.victim().after()));
        boolean tookHeart = !outcome.repeat() && !outcome.shielded() && outcome.victim().delta() < 0;
        if (tookHeart) run("kill", fills);
        if (tookHeart && outcome.killerStreak() > 0) {
            run("streak." + outcome.killerStreak(), Map.of("player", name(outcome.killer().name()),
                    "uuid", outcome.killer().player(), "streak", outcome.killerStreak()));
        }
        if (outcome.endedStreak() > 0) {
            Map<String, Object> ended = new LinkedHashMap<>(fills);
            ended.put("streak", outcome.endedStreak());
            run("streak-ended", ended);
        }
        if (outcome.bounty() != null && outcome.bounty().change().delta() > 0) {
            Map<String, Object> bounty = new LinkedHashMap<>(fills);
            bounty.put("hearts", outcome.bounty().change().delta());
            run("bounty-claimed", bounty);
        }
    }

    /** A server event began: one of {@link #EVENTS}. */
    public void eventStarted(String event, java.time.Duration length) {
        run("event-start." + event, Map.of("event", event, "minutes", Math.max(1, (length.toSeconds() + 59) / 60)));
    }

    /** It ended, on time or stopped early. */
    public void eventEnded(String event) {
        run("event-end." + event, Map.of("event", event));
    }

    public void airdropLanded(org.bukkit.Location at, boolean golden) {
        run("airdrop-landed", place(at, golden));
    }

    public void airdropOpened(org.bukkit.entity.Player player, org.bukkit.Location at, boolean golden) {
        Map<String, Object> fills = place(at, golden);
        fills.put("player", name(player.getName()));
        fills.put("uuid", player.getUniqueId());
        run("airdrop-opened", fills);
    }

    private static Map<String, Object> place(org.bukkit.Location at, boolean golden) {
        Map<String, Object> fills = new LinkedHashMap<>();
        fills.put("world", at.getWorld() == null ? "" : at.getWorld().getName());
        fills.put("x", at.getBlockX());
        fills.put("y", at.getBlockY());
        fills.put("z", at.getBlockZ());
        fills.put("golden", golden);
        return fills;
    }

    public void kothWon(String player, java.util.UUID uuid, int seconds, int hearts) {
        run("koth-won", Map.of("player", name(player), "uuid", uuid, "seconds", seconds, "hearts", hearts));
    }

    public void finaleWon(String player, java.util.UUID uuid) {
        run("finale-won", Map.of("player", name(player), "uuid", uuid));
    }

    void seasonEnded(String old, String next, String winner) {
        run("season-reset", Map.of("old", old, "season", next, "winner", winner == null ? "nobody" : name(winner)));
    }

    /** Runs a hook's commands from the console, on the server's own thread; one failing doesn't stop the rest. */
    private void run(String hook, Map<String, ?> fills) {
        List<String> commands = hooks.getStringList(hook);
        if (commands.isEmpty()) return;
        plugin.tasks().global().execute(() -> {
            for (String command : commands) {
                String line = fill(command, fills);
                try {
                    if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line)) {
                        plugin.getLogger().warning("hooks.yml " + hook + ": no such command: " + line);
                    }
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "hooks.yml " + hook + ": \"" + line + "\" failed", e);
                }
            }
        });
    }

    /**
     * A player's name made safe to put in a command. Paper only lets through names of letters,
     * digits and _, but a proxy or a server with name checks off can pass others (Bedrock
     * players' names often start with a dot), so anything else is dropped.
     */
    static String name(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "");
    }

    /** The command with its fill-ins swapped in, and without a leading slash. */
    static String fill(String command, Map<String, ?> fills) {
        String line = command.strip();
        if (line.startsWith("/")) line = line.substring(1);
        for (Map.Entry<String, ?> fill : fills.entrySet()) {
            line = line.replace("{" + fill.getKey() + "}", String.valueOf(fill.getValue()));
        }
        return line;
    }
}
