package dev.lifestealplus;

import dev.lifestealplus.combat.CombatTagger;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.VengeanceMark;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * While you hold a vengeance mark, the bar above your hotbar points toward your target with a
 * rounded distance. Stays quiet while you're in combat, so the combat countdown shows instead.
 */
final class VengeanceTracker {

    private static final String[] ARROWS = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final CombatTagger tagger;
    private final Map<UUID, List<VengeanceMark>> byAvenger = new ConcurrentHashMap<>();

    VengeanceTracker(LifestealPlugin plugin, HeartService hearts, CombatTagger tagger) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tagger = tagger;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 20, 100);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::show, 20, 20);
    }

    private boolean on() {
        return plugin.settings().extras().vengeanceTracker();
    }

    private void refresh() {
        if (!on()) {
            byAvenger.clear();
            return;
        }
        hearts.liveMarks().whenComplete((marks, error) -> {
            if (error != null) return;
            Map<UUID, List<VengeanceMark>> fresh = new ConcurrentHashMap<>();
            for (VengeanceMark mark : marks) fresh.computeIfAbsent(mark.avenger(), k -> new ArrayList<>()).add(mark);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                byAvenger.clear();
                byAvenger.putAll(fresh);
            });
        });
    }

    private void show() {
        if (!on()) return;
        Instant now = Instant.now();
        int roundTo = plugin.settings().extras().trackerRoundTo();
        byAvenger.forEach((avengerId, marks) -> {
            Player avenger = plugin.getServer().getPlayer(avengerId);
            if (avenger == null || tagger.isTagged(avenger)) return;
            VengeanceMark mark = marks.stream().filter(m -> m.expires().isAfter(now)).findFirst().orElse(null);
            if (mark == null) return;
            String left = Msg.duration(Duration.between(now, mark.expires()));
            Player target = plugin.getServer().getPlayer(mark.marked());
            if (target == null || !target.getWorld().equals(avenger.getWorld())) {
                Msg.actionBar(avenger, "vengeance.tracker-far", Msg.t("target", mark.markedName()), Msg.t("time", left));
                return;
            }
            Location from = avenger.getLocation(), to = target.getLocation();
            long distance = Math.max(roundTo, Math.round(from.distance(to) / roundTo) * roundTo);
            Msg.actionBar(avenger, "vengeance.tracker", Msg.t("target", mark.markedName()), Msg.t("distance", distance),
                    Msg.t("arrow", arrow(from, to)), Msg.t("time", left));
        });
    }

    /** Which way to turn: the target's direction relative to where the player is facing. */
    private static String arrow(Location from, Location to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double toward = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = ((toward - from.getYaw()) % 360 + 540) % 360 - 180;
        int index = (int) Math.floorMod(Math.round(relative / 45), 8);
        return ARROWS[index];
    }
}
