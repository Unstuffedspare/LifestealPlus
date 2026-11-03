package dev.lifestealplus.events;

import dev.lifestealplus.EventSettings;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.Zones;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.items.HeartItems;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Sound;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Short server-wide events, started by an admin or at scheduled times:
 * <ul>
 *   <li>Double Hearts: every kill takes more hearts.</li>
 *   <li>Heart Frenzy: monsters killed by players often drop Heart Fragments, anywhere.</li>
 *   <li>Cap Boost: the heart cap goes up; hearts gained above the normal cap are kept after.</li>
 * </ul>
 * Each shows a boss bar while it runs. When one ends is saved, so a restart picks it back up.
 */
public final class TimedEvents implements Listener {

    public enum Kind {
        DOUBLE_HEARTS("double-hearts", BossBar.Color.RED),
        FRENZY("heart-frenzy", BossBar.Color.PINK),
        CAP_BOOST("cap-boost", BossBar.Color.YELLOW);

        /** Its config section and messages section. */
        public final String key;
        private final BossBar.Color color;

        Kind(String key, BossBar.Color color) {
            this.key = key;
            this.color = color;
        }
    }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartItems items;
    private final Map<Kind, Instant> ends = new EnumMap<>(Kind.class);
    private final Map<Kind, Duration> lengths = new EnumMap<>(Kind.class);
    private final Map<Kind, BossBar> bars = new EnumMap<>(Kind.class);
    /** The next scheduled start of each event that has a schedule. */
    private final Map<Kind, Instant> next = new EnumMap<>(Kind.class);

    public TimedEvents(LifestealPlugin plugin, HeartService hearts, HeartItems items) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.items = items;
        for (Kind kind : Kind.values()) {
            hearts.state(stateKey(kind)).whenComplete((saved, error) -> plugin.tasks().now(() -> {
                if (error != null) plugin.getLogger().log(Level.SEVERE, "Couldn't load when " + kind.key + " ends", error);
                Instant end = saved == null ? null : saved.map(v -> Instant.ofEpochMilli(Long.parseLong(v))).orElse(null);
                if (end != null && end.isAfter(Instant.now())) start(kind, Duration.between(Instant.now(), end), false);
            }));
        }
        settingsChanged();
        plugin.tasks().timer(this::tick, 20, 20);
    }

    private String stateKey(Kind kind) {
        return plugin.settings().database().scope() + "event." + kind.key + ".ends";
    }

    public EventSettings.TimedEvent settings(Kind kind) {
        EventSettings events = plugin.settings().events();
        return switch (kind) {
            case DOUBLE_HEARTS -> events.doubleHearts();
            case FRENZY -> events.frenzy();
            case CAP_BOOST -> events.capBoost();
        };
    }

    public boolean isActive(Kind kind) {
        return ends.containsKey(kind);
    }

    /** When it ends if running, else when it next starts on its schedule; null if neither. */
    public Instant when(Kind kind) {
        return ends.containsKey(kind) ? ends.get(kind) : next.get(kind);
    }

    /** Starts one now; false if it's already running. */
    public boolean start(Kind kind, Duration length) {
        return start(kind, length, true);
    }

    private boolean start(Kind kind, Duration length, boolean announce) {
        if (isActive(kind)) return false;
        Instant end = Instant.now().plus(length);
        ends.put(kind, end);
        lengths.put(kind, length);
        hearts.putState(stateKey(kind), String.valueOf(end.toEpochMilli()));
        apply(kind, true);
        BossBar bar = BossBar.bossBar(Msg.get(kind.key + ".bar", Msg.t("n", settings(kind).amount())), 1f, kind.color,
                BossBar.Overlay.PROGRESS);
        bars.put(kind, bar);
        plugin.getServer().getOnlinePlayers().forEach(p -> p.showBossBar(bar));
        if (announce) {
            Msg.broadcast(kind.key + ".started", Msg.t("time", Msg.duration(length)), Msg.t("n", settings(kind).amount()));
            plugin.getServer().getOnlinePlayers().forEach(p -> p.playSound(p, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f));
            plugin.hooks().eventStarted(kind.key, length);
        }
        return true;
    }

    /** Ends one early (or on time); false if it wasn't running. */
    public boolean stop(Kind kind) {
        if (!isActive(kind)) return false;
        ends.remove(kind);
        lengths.remove(kind);
        hearts.clearState(stateKey(kind));
        apply(kind, false);
        BossBar bar = bars.remove(kind);
        if (bar != null) plugin.getServer().getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        Msg.broadcast(kind.key + ".ended");
        plugin.hooks().eventEnded(kind.key);
        return true;
    }

    private void apply(Kind kind, boolean on) {
        int amount = settings(kind).amount();
        switch (kind) {
            case DOUBLE_HEARTS -> hearts.setKillMultiplier(kind.key, on ? amount : 1);
            case CAP_BOOST -> hearts.setCapBonus(kind.key, on ? amount : 0);
            case FRENZY -> { }
        }
    }

    private void tick() {
        Instant now = Instant.now();
        for (Kind kind : Kind.values()) {
            if (isActive(kind)) {
                if (!now.isBefore(ends.get(kind))) {
                    stop(kind);
                } else {
                    double left = Duration.between(now, ends.get(kind)).toMillis();
                    bars.get(kind).progress((float) Math.max(0, Math.min(1, left / Math.max(1, lengths.get(kind).toMillis()))));
                }
                continue;
            }
            Instant due = next.get(kind);
            if (due != null && !now.isBefore(due)) {
                start(kind, settings(kind).length());
                next.put(kind, settings(kind).schedule().next(now));
            }
        }
    }

    /** After a settings change: new schedules, and a running event takes on its new strength. */
    public void settingsChanged() {
        Instant now = Instant.now();
        for (Kind kind : Kind.values()) {
            EventSettings.TimedEvent settings = settings(kind);
            if (settings.schedule().isEmpty()) next.remove(kind);
            else next.put(kind, settings.schedule().next(now));
            if (isActive(kind)) apply(kind, true);
        }
    }

    @EventHandler
    void onMobDeath(EntityDeathEvent event) {
        if (!isActive(Kind.FRENZY) || !(event.getEntity() instanceof Monster mob) || mob.getKiller() == null) return;
        if (!plugin.zones().allows(mob.getLocation(), Zones.Rule.LIFESTEAL)) return;
        if (ThreadLocalRandom.current().nextDouble() < settings(Kind.FRENZY).chance()) event.getDrops().add(items.fragment(1));
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        bars.values().forEach(player::showBossBar);
    }

    /** On disable: undo the effects but keep the saved end times, so a restart picks them back up. */
    public void shutdown() {
        for (Kind kind : Kind.values()) {
            if (!isActive(kind)) continue;
            apply(kind, false);
            BossBar bar = bars.get(kind);
            if (bar != null) plugin.getServer().getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        }
    }
}
