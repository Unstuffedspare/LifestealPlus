package dev.lifestealplus.events;

import dev.lifestealplus.EventSettings;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.NewsItem;
import dev.lifestealplus.items.HeartItems;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.GameRules;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Every so often (about once a week by default, at a random time) the overworld falls into
 * a long night: PvP kills move extra hearts and hostile mobs can drop Heart Fragments. The
 * next rising, and the end of one in progress, are saved so a restart doesn't skip or reroll them.
 */
public final class BloodMoon implements Listener {

    private static final String NEXT = "blood_moon.next";
    private static final String ENDS = "blood_moon.ends";
    private static final long MIDNIGHT = 18000;
    private static final long DAWN = 23000;

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartItems items;
    private Instant next;
    private Instant ends;
    private Object timing;
    private final java.util.Set<Integer> warned = new java.util.HashSet<>();
    private Duration length;
    private BossBar bar;

    public BloodMoon(LifestealPlugin plugin, HeartService hearts, HeartItems items) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.items = items;
        // Load saved timing, then check twice a minute.
        hearts.state(ENDS).thenCombine(hearts.state(NEXT), (end, nxt) -> new Saved(
                end.map(v -> Instant.ofEpochMilli(Long.parseLong(v))).orElse(null),
                nxt.map(v -> Instant.ofEpochMilli(Long.parseLong(v))).orElse(null)))
                .whenComplete((saved, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) plugin.getLogger().log(Level.SEVERE, "Couldn't load Blood Moon timing", error);
                    Instant savedEnd = saved == null ? null : saved.ends();
                    next = saved == null ? null : saved.next();
                    timing = timing();
                    if (savedEnd != null && savedEnd.isAfter(Instant.now())) {
                        start(Duration.between(Instant.now(), savedEnd));
                    } else if (next == null || !settings().schedule().isEmpty()) {
                        // A schedule is worked out afresh, in case it changed while the server was off.
                        scheduleNext();
                    }
                    plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
                }));
    }

    private record Saved(Instant ends, Instant next) {
    }

    private EventSettings.BloodMoon settings() {
        return plugin.settings().events().bloodMoon();
    }

    public boolean isActive() {
        return ends != null;
    }

    /** At the next scheduled time, or somewhere between half and one-and-a-half times the average gap from now. */
    private void scheduleNext() {
        warned.clear();
        timing = timing();
        if (!settings().enabled()) {
            next = null;
            hearts.clearState(NEXT);
            return;
        }
        if (!settings().schedule().isEmpty()) {
            next = settings().schedule().next(Instant.now());
        } else {
            double factor = 0.5 + ThreadLocalRandom.current().nextDouble();
            next = Instant.now().plusMillis((long) (settings().every().toMillis() * factor));
        }
        hearts.putState(NEXT, String.valueOf(next.toEpochMilli()));
    }

    /** What decides when the next one comes; a change to it in the settings picks a new time. */
    private Object timing() {
        return List.of(settings().every(), settings().schedule());
    }

    /** After a settings change: a new schedule (or a switch off or on) moves the next rising at once. */
    public void settingsChanged() {
        if (!isActive() && !timing().equals(timing)) scheduleNext();
    }

    /** Says "a Blood Moon rises in 10 minutes", once for the closest warning time passed. */
    private void warn(Instant now) {
        long left = Duration.between(now, next).toMinutes() + 1;
        Integer due = null;
        for (int minutes : settings().warnMinutes()) {
            if (minutes >= left && warned.add(minutes) && (due == null || minutes < due)) due = minutes;
        }
        if (due != null) Msg.broadcast("blood-moon.warning", Msg.t("time", Msg.duration(Duration.between(now, next))));
    }

    private void tick() {
        Instant now = Instant.now();
        if (isActive()) {
            if (!now.isBefore(ends)) {
                end();
            } else if (bar != null) {
                bar.progress((float) Math.max(0, Math.min(1,
                        (double) Duration.between(now, ends).toMillis() / Math.max(1, length.toMillis()))));
                // Keep it night even if someone sleeps or sets the time.
                World world = overworld();
                if (world.getTime() < 13000) world.setTime(MIDNIGHT);
            }
            return;
        }
        if (next == null) return;
        if (now.isBefore(next)) {
            warn(now);
            return;
        }
        if (plugin.getServer().getOnlinePlayers().size() >= settings().minPlayers()) {
            start(settings().length());
        } else {
            // Not enough people to enjoy it; look again in ten minutes.
            next = now.plus(Duration.ofMinutes(10));
            hearts.putState(NEXT, String.valueOf(next.toEpochMilli()));
        }
    }

    /** Starts one now (also how admins trigger it). Does nothing if one is already up. */
    public boolean start(Duration lasting) {
        if (isActive()) return false;
        length = lasting;
        ends = Instant.now().plus(lasting);
        next = null;
        hearts.clearState(NEXT);
        hearts.putState(ENDS, String.valueOf(ends.toEpochMilli()));
        hearts.setKillMultiplier("blood-moon", settings().killMultiplier());

        World world = overworld();
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setTime(MIDNIGHT);
        bar = BossBar.bossBar(Msg.get("blood-moon.bar"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        int multiplier = settings().killMultiplier();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.showBossBar(bar);
            p.showTitle(Title.title(Msg.get("blood-moon.title"), Msg.get("blood-moon.subtitle", Msg.t("multiplier", multiplier))));
            p.playSound(p, Sound.ENTITY_WITHER_AMBIENT, 0.7f, 0.5f);
        }
        Msg.broadcast("blood-moon.rises", Msg.t("time", Msg.duration(lasting)), Msg.t("multiplier", multiplier));
        hearts.addNews(NewsItem.BLOOD_MOON, null, null, multiplier);
        return true;
    }

    /** Ends the one in progress (also how admins stop it early). */
    public boolean end() {
        if (!isActive()) return false;
        ends = null;
        hearts.clearState(ENDS);
        hearts.setKillMultiplier("blood-moon", 1);
        World world = overworld();
        world.setGameRule(GameRules.ADVANCE_TIME, true);
        world.setTime(DAWN);
        if (bar != null) plugin.getServer().getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        bar = null;
        Msg.broadcast("blood-moon.sets");
        scheduleNext();
        return true;
    }

    public Instant nextRising() {
        return next;
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        if (bar != null) event.getPlayer().showBossBar(bar);
    }

    @EventHandler
    void onMobDeath(EntityDeathEvent event) {
        if (!isActive() || !(event.getEntity() instanceof Monster mob)) return;
        if (mob.getKiller() == null || mob.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (!mob.getWorld().equals(overworld())) return;
        if (ThreadLocalRandom.current().nextDouble() < settings().fragmentChance()) event.getDrops().add(items.fragment(1));
    }

    /** On disable: put the sky back but keep the saved end time, so a restart picks the night back up. */
    public void shutdown() {
        if (!isActive()) return;
        hearts.setKillMultiplier("blood-moon", 1);
        overworld().setGameRule(GameRules.ADVANCE_TIME, true);
        if (bar != null) plugin.getServer().getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
    }

    private World overworld() {
        return plugin.getServer().getWorlds().getFirst();
    }
}
