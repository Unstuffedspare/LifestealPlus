package dev.lifestealplus.api;

import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.PlayerStats;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * LifestealPlus for other plugins. Get it with {@link #get()} once LifestealPlus has enabled
 * (add {@code depend} or {@code softdepend: [LifestealPlus]} to your plugin.yml).
 *
 * <p>Hearts live in a database, so anything that reads or changes them for a player who may be
 * offline returns a {@link CompletableFuture}; it completes off the main thread, so hop back
 * with the scheduler before touching the world. Changes made here are recorded in the heart
 * ledger like any other, and follow the same rules (the cap, newbie shields, eliminations).
 *
 * <p>To react to hearts changing, listen for {@link dev.lifestealplus.event.HeartsChangedEvent},
 * {@link dev.lifestealplus.event.PlayerEliminatedEvent} and
 * {@link dev.lifestealplus.event.PlayerRevivedEvent}.
 */
public interface LifestealAPI {

    /** The running instance, or null if LifestealPlus isn't installed or hasn't enabled yet. */
    static LifestealAPI get() {
        return Bukkit.getServicesManager().load(LifestealAPI.class);
    }

    /** An online player's hearts right now, without waiting; empty if they're offline. */
    OptionalInt onlineHearts(UUID player);

    /** Any player's hearts; empty if they've never joined. 0 means eliminated. */
    CompletableFuture<OptionalInt> hearts(UUID player);

    /** The most hearts this player can have: their own cap if a permission gives one, else the server's. */
    int maxHearts(UUID player);

    /**
     * Gives (or with a negative amount, takes) hearts. Gains stop at the cap. Adding hearts doesn't
     * bring back an eliminated player; use {@link #setHearts} for that. The future completes on the
     * main thread (Folia: the global region) once online players have the change, so don't wait on
     * it there with join() or get().
     *
     * @param reason shown in the ledger, e.g. your plugin's name and what for
     */
    CompletableFuture<HeartChange> addHearts(UUID player, int amount, String reason);

    /**
     * Sets a player's hearts. 0 eliminates them, and more than 0 brings an eliminated player back.
     * Like {@link #addHearts}, the future completes on the main thread (Folia: the global region).
     */
    CompletableFuture<HeartChange> setHearts(UUID player, int hearts, String reason);

    /** A player's record for the current season, by name; empty if there's no such player. */
    CompletableFuture<Optional<PlayerStats>> stats(String playerName);

    /** Whether hearts move at a place (false in excluded worlds and in WorldGuard regions flagged off). */
    boolean lifestealAt(Location location);
}
