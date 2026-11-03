package dev.lifestealplus;

import dev.lifestealplus.PluginSettings.MostWantedSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Every so often, tells the whole server roughly where the online player with the most
 * hearts is. A built-in counterweight to a runaway leader: being on top makes you a target.
 */
final class MostWanted {

    private final LifestealPlugin plugin;
    private final HeartTracker tracker;
    private BukkitTask task;

    MostWanted(LifestealPlugin plugin, HeartTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /** (Re)starts the timer from the current settings. Called at startup and after a reload. */
    void schedule() {
        if (task != null) task.cancel();
        task = null;
        long ticks = plugin.settings().mostWanted().interval().toSeconds() * 20;
        if (ticks > 0) task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::announce, ticks, ticks);
    }

    /** Announces the current leader. Returns their name, or null if nobody qualified (too few online, or all eliminated). */
    String announce() {
        MostWantedSettings settings = plugin.settings().mostWanted();
        if (plugin.getServer().getOnlinePlayers().size() < settings.minPlayers()) return null;

        int best = 0;
        List<Player> leaders = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Integer hearts = tracker.cachedHearts(player.getUniqueId());
            if (hearts == null || hearts <= 0 || !plugin.zones().allows(player.getLocation(), Zones.Rule.LIFESTEAL)) continue;
            if (hearts > best) {
                best = hearts;
                leaders.clear();
            }
            if (hearts == best) leaders.add(player);
        }
        if (leaders.isEmpty()) return null;
        Player wanted = leaders.get(ThreadLocalRandom.current().nextInt(leaders.size()));

        Location at = wanted.getLocation();
        Msg.broadcast("most-wanted.announcement",
                Msg.t("player", wanted.getName()),
                Msg.t("hearts", best),
                Msg.t("x", round(at.getX(), settings.precision())),
                Msg.t("z", round(at.getZ(), settings.precision())),
                Msg.c("dimension", Msg.get(dimension(at))));
        plugin.getServer().getOnlinePlayers().forEach(p -> p.playSound(p, Sound.EVENT_RAID_HORN, 0.4f, 1.2f));
        Msg.send(wanted, "most-wanted.you");
        return wanted.getName();
    }

    private static long round(double coordinate, int precision) {
        return Math.round(coordinate / precision) * precision;
    }

    private static String dimension(Location at) {
        return switch (at.getWorld().getEnvironment()) {
            case NETHER -> "dimension.nether";
            case THE_END -> "dimension.end";
            default -> "dimension.overworld";
        };
    }
}
