package dev.lifestealplus.combat;

import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * New players' protection in the world: other players' hits don't land on them, and
 * attacking anyone ends it. The database is the source of truth (it's also what stops
 * heart changes); this keeps a copy for online players and wears it down with playtime,
 * a minute at a time.
 */
public final class NewbieShield implements Listener {

    private static final Duration TICK = Duration.ofMinutes(1);

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    /** Shield left for online players who have one. */
    private final Map<UUID, Duration> left = new ConcurrentHashMap<>();
    /** When each attacker was last told about a shield, so held-down attacks don't spam chat. */
    private final Map<UUID, Long> lastWarned = new ConcurrentHashMap<>();

    public NewbieShield(LifestealPlugin plugin, HeartService hearts) {
        this.plugin = plugin;
        this.hearts = hearts;
        long ticks = TICK.toSeconds() * 20;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::wearDown, ticks, ticks);
    }

    /** False for everyone while newbie-shield is switched off; their remaining time is kept for later. */
    public boolean isShielded(Player player) {
        return plugin.settings().extras().shieldsEnabled() && left.containsKey(player.getUniqueId());
    }

    public Duration left(Player player) {
        return left.getOrDefault(player.getUniqueId(), Duration.ZERO);
    }

    /** Admin change for someone online: keep the copy in step with the database. */
    public void changed(UUID player, Duration now) {
        if (now.isZero()) left.remove(player);
        else if (plugin.getServer().getPlayer(player) != null) left.put(player, now);
    }

    /**
     * After a settings change: re-reads every online player's shield, so switching shields
     * off or on, or shortening the day limit, shows straight away rather than at the next tick.
     */
    public void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            hearts.shieldLeft(id).whenComplete((shield, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Couldn't read the newbie shield for " + player.getName(), error);
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (!shield.isZero()) {
                        left.put(id, shield);
                    } else if (left.remove(id) != null && plugin.settings().extras().shieldsEnabled()) {
                        Msg.send(player, "shield.worn-off");
                    }
                });
            });
        }
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        hearts.shieldLeft(player.getUniqueId()).whenComplete((shield, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't read the newbie shield for " + player.getName(), error);
                return;
            }
            if (shield.isZero() || !plugin.settings().extras().shieldsEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                left.put(player.getUniqueId(), shield);
                Msg.send(player, "shield.on-join", Msg.t("time", describe(shield)));
            });
        });
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        left.remove(event.getPlayer().getUniqueId());
        lastWarned.remove(event.getPlayer().getUniqueId());
    }

    /** Runs before combat tagging, so a blocked hit never counts as a fight. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        // Nothing is at stake in a world without lifesteal, so the shield neither blocks nor breaks there.
        if (!plugin.settings().lifestealIn(victim.getWorld())) return;

        if (isShielded(victim)) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            Long last = lastWarned.get(attacker.getUniqueId());
            if (last == null || now - last > 3000) {
                lastWarned.put(attacker.getUniqueId(), now);
                Msg.send(attacker, "shield.target-protected", Msg.t("victim", victim.getName()), Msg.t("time", describe(left(victim))));
            }
            return;
        }
        if (isShielded(attacker)) {
            left.remove(attacker.getUniqueId());
            hearts.breakShield(attacker.getUniqueId()).exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "Couldn't break the newbie shield for " + attacker.getName(), error);
                return false;
            });
            Msg.send(attacker, "shield.broken", Msg.t("victim", victim.getName()));
        }
    }

    private void wearDown() {
        if (!plugin.settings().extras().shieldsEnabled()) return;
        for (UUID id : left.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                left.remove(id);
                continue;
            }
            if (!plugin.settings().lifestealIn(player.getWorld())) continue;
            hearts.spendShield(id, TICK).whenComplete((remaining, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Couldn't update the newbie shield for " + player.getName(), error);
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    // A break or an admin change may have landed in the meantime; only an existing entry gets updated.
                    if (remaining.isZero()) {
                        if (left.remove(id) != null && player.isOnline()) {
                            Msg.send(player, "shield.worn-off");
                        }
                    } else {
                        left.computeIfPresent(id, (k, v) -> remaining);
                    }
                });
            });
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    public static String describe(Duration d) {
        return Msg.duration(d);
    }
}
