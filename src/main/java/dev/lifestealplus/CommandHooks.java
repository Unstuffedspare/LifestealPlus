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
final class CommandHooks implements Listener {

    static final Set<String> HOOKS = Set.of("elimination", "revival", "kill", "streak", "streak-ended",
            "bounty-claimed", "season-reset");

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
            } else if (key.equals("streak")) {
                var section = loaded.getConfigurationSection(key);
                if (section == null) {
                    if (!(loaded.get(key) instanceof Map<?, ?> map && map.isEmpty())) {
                        warnings.add("hooks.yml: streak should list streak numbers, each with its commands; ignoring it.");
                    }
                    continue;
                }
                for (String n : section.getKeys(false)) {
                    if (!n.matches("\\d{1,4}")) warnings.add("hooks.yml: streak." + n + " should be a streak number like 5; ignoring it.");
                    else if (!section.isList(n)) warnings.add("hooks.yml: streak." + n + " should be a list of commands; ignoring it.");
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
            fills.put("killer", change.otherName() == null ? "nobody" : change.otherName());
            fills.put("count", count);
            fills.put("ban-length", plugin.settings().bans().lengthText(count));
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
        fills.put("player", change.name());
        fills.put("uuid", change.player());
        fills.put("cause", change.cause().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        return fills;
    }

    /** Everything a kill can set off. */
    void kill(KillOutcome outcome) {
        Map<String, Object> fills = new LinkedHashMap<>();
        fills.put("killer", outcome.killer().name());
        fills.put("victim", outcome.victim().name());
        fills.put("killer-uuid", outcome.killer().player());
        fills.put("victim-uuid", outcome.victim().player());
        fills.put("killer-hearts", outcome.killer().after());
        fills.put("victim-hearts", Math.max(0, outcome.victim().after()));
        boolean tookHeart = !outcome.repeat() && !outcome.shielded() && outcome.victim().delta() < 0;
        if (tookHeart) run("kill", fills);
        if (tookHeart && outcome.killerStreak() > 0) {
            run("streak." + outcome.killerStreak(), Map.of("player", outcome.killer().name(),
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

    void seasonEnded(String old, String next, String winner) {
        run("season-reset", Map.of("old", old, "season", next, "winner", winner == null ? "nobody" : winner));
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
