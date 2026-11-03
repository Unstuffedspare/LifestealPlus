package dev.lifestealplus.combat;

import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.Zones;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Who's in a fight right now. A PvP hit (melee or projectile) tags both players; the
 * tag is what decides whether logging out leaves a body behind.
 */
public final class CombatTagger implements Listener {

    private final LifestealPlugin plugin;
    private final HeartTracker tracker;
    /** When each tagged player's tag runs out, in epoch millis. */
    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();

    public CombatTagger(LifestealPlugin plugin, HeartTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        plugin.tasks().timer(this::showCountdowns, 20, 20);
    }

    public boolean isTagged(Player player) {
        Long until = taggedUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public void untag(Player player) {
        taggedUntil.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (!plugin.zones().allows(victim.getLocation(), Zones.Rule.COMBAT_TAG)) return;
        if (!fighting(attacker) || !fighting(victim)) return;
        long tagMillis = plugin.settings().combat().tag().toMillis();
        if (tagMillis <= 0) return;
        long until = System.currentTimeMillis() + tagMillis;
        taggedUntil.put(attacker.getUniqueId(), until);
        taggedUntil.put(victim.getUniqueId(), until);
    }

    @EventHandler
    void onDeath(PlayerDeathEvent event) {
        untag(event.getPlayer());
    }

    /** Players in creative or spectator, and eliminated players, are never in combat. */
    private boolean fighting(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !tracker.isEliminated(player);
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    private void showCountdowns() {
        long now = System.currentTimeMillis();
        taggedUntil.forEach((id, until) -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                taggedUntil.remove(id);
                return;
            }
            long seconds = (until - now + 999) / 1000;
            if (seconds > 0) {
                Msg.actionBar(player, "combat.countdown", Msg.t("seconds", seconds));
            } else {
                taggedUntil.remove(id);
                Msg.actionBar(player, "combat.over");
            }
        });
    }
}
