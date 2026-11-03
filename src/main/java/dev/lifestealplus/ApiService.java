package dev.lifestealplus;

import dev.lifestealplus.api.LifestealAPI;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PlayerStats;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;

/** {@link LifestealAPI}, registered with Bukkit's services so other plugins can find it. */
final class ApiService implements LifestealAPI {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final PersonalCaps caps;

    ApiService(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, PersonalCaps caps) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.caps = caps;
    }

    @Override
    public OptionalInt onlineHearts(UUID player) {
        Integer now = tracker.cachedHearts(player);
        return now == null ? OptionalInt.empty() : OptionalInt.of(Math.max(0, now));
    }

    @Override
    public CompletableFuture<OptionalInt> hearts(UUID player) {
        return hearts.find(player).thenApply(found -> found.map(p -> OptionalInt.of(p.hearts())).orElse(OptionalInt.empty()));
    }

    @Override
    public int maxHearts(UUID player) {
        return caps.maxFor(player, hearts.rules());
    }

    @Override
    public CompletableFuture<HeartChange> addHearts(UUID player, int amount, String reason) {
        return change(HeartRequest.add(player, amount, HeartCause.PLUGIN_ADD, reason));
    }

    @Override
    public CompletableFuture<HeartChange> setHearts(UUID player, int hearts, String reason) {
        if (hearts < 0) throw new IllegalArgumentException("hearts can't be negative");
        return change(HeartRequest.set(player, hearts, HeartCause.PLUGIN_SET, reason));
    }

    /** Through the tracker, so online players' health, the tab list and the events all follow. */
    private CompletableFuture<HeartChange> change(HeartRequest request) {
        return tracker.track(hearts.apply(List.of(request))).thenApply(List::getFirst);
    }

    @Override
    public CompletableFuture<Optional<PlayerStats>> stats(String playerName) {
        return hearts.stats(playerName, null);
    }

    @Override
    public boolean lifestealAt(Location location) {
        return plugin.zones().allows(location, Zones.Rule.LIFESTEAL);
    }
}
