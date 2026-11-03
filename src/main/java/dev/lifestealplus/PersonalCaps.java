package dev.lifestealplus;

import dev.lifestealplus.core.HeartRules;
import dev.lifestealplus.core.HeartService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Players' own heart caps, from permissions like lifestealplus.max-hearts.30 (the highest one
 * counts). Read when they join and every minute after, so a rank change shows within a minute,
 * and saved with their hearts so the cap still applies while they're offline.
 */
final class PersonalCaps implements Listener {

    static final String PREFIX = "lifestealplus.max-hearts.";
    private static final int NONE = 0;

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    /** The cap last read for each online player; NONE when no permission gives them one. */
    private final Map<UUID, Integer> saved = new ConcurrentHashMap<>();

    PersonalCaps(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        plugin.tasks().timer(() -> plugin.getServer().getOnlinePlayers().forEach(this::refresh), 1200, 1200);
    }

    /** The highest lifestealplus.max-hearts.&lt;n&gt; the player has, or NONE. */
    static int capOf(Permissible player) {
        return highest(player, PREFIX);
    }

    /** The highest &lt;n&gt; among the player's permissions {@code prefix}&lt;n&gt;, or NONE. */
    static int highest(Permissible player, String prefix) {
        int best = NONE;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String node = info.getPermission();
            if (!info.getValue() || !node.startsWith(prefix)) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // lifestealplus.max-hearts.* and other non-numbers mean nothing here.
            }
        }
        return best;
    }

    /** The cap that applies to an online player: their own, or everyone's. */
    int maxFor(UUID player, HeartRules rules) {
        int own = saved.getOrDefault(player, NONE);
        return own == NONE ? rules.maxHearts() : rules.personalCap(own + hearts.capBonus()).maxHearts();
    }

    /** Saved every time, not only on a change, so it's back within a minute after a season reset clears it. */
    private void refresh(Player player) {
        int cap = capOf(player);
        saved.put(player.getUniqueId(), cap);
        hearts.setPersonalCap(player.getUniqueId(), cap == NONE ? null : cap).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Couldn't save the heart cap for " + player.getName(), error);
            return null;
        });
    }

    /** After the permissions plugin has set the player up. */
    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        refresh(player);
        // lifestealplus.starting-hearts.<n>: a brand-new player with it starts with n hearts instead.
        int starting = highest(player, "lifestealplus.starting-hearts.");
        if (starting > 0) {
            tracker.track(hearts.applyStartingHearts(player.getUniqueId(), starting).thenApply(change ->
                    change == null ? java.util.List.<dev.lifestealplus.core.HeartChange>of() : java.util.List.of(change)));
        }
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        saved.remove(event.getPlayer().getUniqueId());
    }
}
