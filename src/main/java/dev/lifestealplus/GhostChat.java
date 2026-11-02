package dev.lifestealplus;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Keeps the dead from talking freely to the living, so /whisper (once an hour) is worth
 * something. Eliminated players still chat with each other, and admins hear everyone.
 */
final class GhostChat implements Listener {

    /** Commands that would put a message in a living player's chat. */
    private static final Set<String> DIRECT = Set.of("msg", "tell", "w", "me", "teammsg", "tm");

    private final LifestealPlugin plugin;
    private final HeartTracker tracker;

    GhostChat(LifestealPlugin plugin, HeartTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    private boolean limited(Player player) {
        return plugin.settings().social().limitGhostChat() && tracker.isEliminated(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onChat(AsyncChatEvent event) {
        if (!limited(event.getPlayer())) return;
        event.viewers().removeIf(viewer -> viewer instanceof Player p
                && !tracker.isEliminated(p) && !p.hasPermission("lifestealplus.admin"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onCommand(PlayerCommandPreprocessEvent event) {
        if (!limited(event.getPlayer())) return;
        String root = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (!DIRECT.contains(root)) return;
        event.setCancelled(true);
        Msg.send(event.getPlayer(), "ghosts.living-cant-hear");
    }
}
