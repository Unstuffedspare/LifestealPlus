package dev.lifestealplus;

import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.items.HeartItems;
import java.time.Duration;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

/**
 * Ways to earn hearts without fighting: Heart Fragments for advancements (each player gets
 * them once, when they first earn it) and for every few hours played this season. Fragments
 * rather than whole hearts, so it takes several milestones to make one.
 */
final class Rewards implements Listener {

    private static final Duration TICK = Duration.ofMinutes(1);

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final HeartItems items;

    Rewards(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, HeartItems items) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.items = items;
        long ticks = TICK.toSeconds() * 20;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::countPlaytime, ticks, ticks);
    }

    private PluginSettings.RewardSettings settings() {
        return plugin.settings().rewards();
    }

    @EventHandler
    void onAdvancement(PlayerAdvancementDoneEvent event) {
        String key = event.getAdvancement().getKey().toString();
        int fragments = settings().advancements().getOrDefault(key, 0);
        Player player = event.getPlayer();
        if (fragments <= 0 || tracker.isEliminated(player)) return;
        give(player, fragments);
        Msg.send(player, "rewards.advancement", Msg.t("n", fragments),
                Msg.c("advancement", event.getAdvancement().displayName()));
    }

    /** Each minute online (and still in the game) counts toward the next playtime reward. */
    private void countPlaytime() {
        Duration every = settings().playtimeEvery();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (tracker.isEliminated(player)) continue;
            hearts.addPlaytime(player.getUniqueId(), TICK, every).whenComplete((reached, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Couldn't count playtime for " + player.getName(), error);
                    return;
                }
                if (reached <= 0 || every.isZero()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    int fragments = reached * settings().playtimeFragments();
                    give(player, fragments);
                    Msg.send(player, "rewards.playtime", Msg.t("n", fragments), Msg.t("time", Msg.duration(every)));
                });
            });
        }
    }

    private void give(Player player, int fragments) {
        player.getInventory().addItem(items.fragment(fragments)).values()
                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
    }
}
