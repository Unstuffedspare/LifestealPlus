package dev.lifestealplus;

import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PactPair;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Hearts (and Blood Pacts) next to names in the tab list, in the format set by the tab.*
 * messages. Switching tab-list off puts everyone's plain name back.
 */
final class TabList {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    /** Each pacted player's partner's name; refreshed every half minute. */
    private final Map<UUID, String> partners = new ConcurrentHashMap<>();
    private boolean showing;

    TabList(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::update, 40, 40);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshPacts, 20, 20 * 30);
    }

    private boolean pactsShown() {
        PluginSettings s = plugin.settings();
        return s.extras().tabList() && s.extras().pactsInTab() && s.social().pactsEnabled();
    }

    private void refreshPacts() {
        if (!pactsShown()) {
            partners.clear();
            return;
        }
        hearts.allPacts().whenComplete((pacts, error) -> {
            if (error != null) return;
            Map<UUID, String> fresh = new ConcurrentHashMap<>();
            for (PactPair pact : pacts) {
                fresh.put(pact.first(), pact.secondName());
                fresh.put(pact.second(), pact.firstName());
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                partners.clear();
                partners.putAll(fresh);
            });
        });
    }

    private void update() {
        if (!plugin.settings().extras().tabList()) {
            if (showing) plugin.getServer().getOnlinePlayers().forEach(p -> p.playerListName(null));
            showing = false;
            return;
        }
        showing = true;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Integer count = tracker.cachedHearts(player.getUniqueId());
            if (count == null) continue;
            String partner = pactsShown() ? partners.get(player.getUniqueId()) : null;
            Component pact = partner == null ? Component.empty() : Msg.get("tab.pact", Msg.t("partner", partner));
            Component name = Msg.get(count <= 0 ? "tab.eliminated" : "tab.player",
                    Msg.t("name", player.getName()), Msg.t("hearts", count), Msg.c("pact", pact));
            if (!name.equals(player.playerListName())) player.playerListName(name);
        }
    }
}
