package dev.lifestealplus;

import dev.lifestealplus.combat.CombatBodies;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PlayerHearts;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

final class PlayerListener implements Listener {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final KillHandler kills;
    private final CombatBodies bodies;

    PlayerListener(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, KillHandler kills, CombatBodies bodies) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.kills = kills;
        this.bodies = bodies;
    }

    /**
     * Makes sure the player has a heart record before they're in the world. If the
     * database can't answer, keep them out rather than let them play untracked.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            hearts.touch(event.getUniqueId(), event.getName()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't load hearts for " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Msg.get("login.data-unavailable"));
        }
    }

    /**
     * Reads hearts fresh rather than reusing the pre-login value, in case an admin changed
     * them in between. A combat-logger whose body was killed dies first, so an elimination
     * that follows never stashes items that already dropped from the body.
     */
    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        bodies.rejoined(player);
        hearts.takeBodyDeath(player.getUniqueId())
                .thenCombine(hearts.find(player.getUniqueId()), JoinState::new)
                .whenCompleteAsync((state, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Couldn't load hearts for " + player.getName(), error);
                        return;
                    }
                    if (!player.isOnline()) return;
                    if (state.bodyDied()) bodies.finishBodyDeath(player);
                    state.hearts().ifPresent(h -> tracker.joined(player, h));
                }, tracker.mainThread());
    }

    private record JoinState(boolean bodyDied, Optional<PlayerHearts> hearts) {
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        tracker.left(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Integer current = tracker.cachedHearts(victim.getUniqueId());
        if (current != null && current <= 0) return;

        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            kills.pvpKill(victim.getUniqueId(), killer.getUniqueId());
        } else {
            kills.naturalDeath(victim.getUniqueId(), event.getDamageSource().getDamageType().getTranslationKey());
        }
    }

    @EventHandler
    void onRespawn(PlayerRespawnEvent event) {
        tracker.respawning(event);
    }
}
