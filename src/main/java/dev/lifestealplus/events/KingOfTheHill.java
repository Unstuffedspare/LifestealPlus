package dev.lifestealplus.events;

import dev.lifestealplus.EventSettings;
import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.NewsItem;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * King of the Hill, started by an admin at a spot. Every second exactly one player stands on
 * the hill, they score a second; two or more and nobody does. When time runs out, whoever held
 * it longest wins hearts. The hill is marked with a ring of particles.
 */
public final class KingOfTheHill implements Listener {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private Location center;
    private Instant ends;
    private Duration length;
    private BossBar bar;
    private final Map<UUID, Integer> seconds = new HashMap<>();

    public KingOfTheHill(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    private EventSettings.Koth settings() {
        return plugin.settings().events().koth();
    }

    public boolean isActive() {
        return center != null;
    }

    public Instant ends() {
        return ends;
    }

    /** Starts one centred on {@code where}; false if one is already running. */
    public boolean start(Location where, Duration lasting) {
        if (isActive()) return false;
        center = where.toBlockLocation().add(0.5, 0, 0.5);
        ends = Instant.now().plus(lasting);
        length = lasting;
        seconds.clear();
        bar = BossBar.bossBar(Msg.get("koth.bar-empty"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        plugin.getServer().getOnlinePlayers().forEach(p -> {
            p.showBossBar(bar);
            p.playSound(p, Sound.EVENT_RAID_HORN, 0.6f, 1.3f);
        });
        Msg.broadcast("koth.started", Msg.t("x", center.getBlockX()), Msg.t("y", center.getBlockY()),
                Msg.t("z", center.getBlockZ()), Msg.t("time", Msg.duration(lasting)), Msg.t("hearts", settings().prizeHearts()));
        return true;
    }

    /** Calls it off with no winner; false if none was running. */
    public boolean stop() {
        if (!isActive()) return false;
        clear();
        Msg.broadcast("koth.called-off");
        return true;
    }

    private void clear() {
        if (bar != null) plugin.getServer().getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        bar = null;
        center = null;
        ends = null;
        seconds.clear();
    }

    private void tick() {
        if (!isActive()) return;
        if (!Instant.now().isBefore(ends)) {
            finish();
            return;
        }
        int radius = settings().radius();
        List<Player> holding = center.getWorld().getPlayers().stream().filter(p -> onHill(p, radius)).toList();
        if (holding.size() == 1) seconds.merge(holding.getFirst().getUniqueId(), 1, Integer::sum);
        showRing(radius);

        Map.Entry<UUID, Integer> leader = leader();
        String leaderName = leader == null ? null : plugin.getServer().getOfflinePlayer(leader.getKey()).getName();
        bar.name(holding.size() > 1 ? Msg.get("koth.bar-contested")
                : leaderName == null ? Msg.get("koth.bar-empty")
                : Msg.get("koth.bar", Msg.t("player", leaderName), Msg.t("seconds", leader.getValue())));
        bar.progress((float) Math.max(0, Math.min(1, (double) Duration.between(Instant.now(), ends).toMillis()
                / Math.max(1, length.toMillis()))));
    }

    private boolean onHill(Player player, int radius) {
        GameMode mode = player.getGameMode();
        if ((mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) || player.isDead() || tracker.isEliminated(player)) return false;
        Location at = player.getLocation();
        double dx = at.getX() - center.getX(), dz = at.getZ() - center.getZ();
        return dx * dx + dz * dz <= radius * radius && Math.abs(at.getY() - center.getY()) <= radius;
    }

    private void showRing(int radius) {
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            center.getWorld().spawnParticle(Particle.END_ROD,
                    center.clone().add(Math.cos(angle) * radius, 0.3, Math.sin(angle) * radius), 1, 0, 0, 0, 0);
        }
    }

    private Map.Entry<UUID, Integer> leader() {
        return seconds.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
    }

    private void finish() {
        Map.Entry<UUID, Integer> winner = leader();
        int prize = settings().prizeHearts();
        clear();
        if (winner == null) {
            Msg.broadcast("koth.no-winner");
            return;
        }
        String name = plugin.getServer().getOfflinePlayer(winner.getKey()).getName();
        Msg.broadcast("koth.won", Msg.t("player", name), Msg.t("seconds", winner.getValue()), Msg.t("hearts", prize));
        if (prize > 0) {
            tracker.track(hearts.apply(List.of(HeartRequest.add(winner.getKey(), prize, HeartCause.EVENT_PRIZE, "King of the Hill"))));
        }
        hearts.addNews(NewsItem.KOTH_WON, name, null, prize);
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        if (bar != null) event.getPlayer().showBossBar(bar);
    }

    public void shutdown() {
        if (isActive()) clear();
    }
}
