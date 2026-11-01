package dev.lifestealplus;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * What happens to a player while they have no hearts. {@link HeartTracker} decides
 * when each method runs and only ever calls them on the main thread, with the player
 * online and alive.
 */
public interface EliminationHandler {

    /** The player just became eliminated (or joined eliminated for the first time since). */
    void eliminate(Player player);

    /** The player is still eliminated; put them back where they belong if they've strayed (after a join or respawn). */
    void enforce(Player player);

    /** The player has hearts again; undo {@link #eliminate} and send them home. */
    void restore(Player player, int hearts);

    /** Max health (in half-hearts) while eliminated. Spectators don't need any; Purgatory players have to fight. */
    default double eliminatedMaxHealth() {
        return 2.0;
    }

    /** Where an eliminated player respawns, or null to leave the vanilla respawn alone. */
    default Location respawnLocation(Player player) {
        return null;
    }

    /** Back to the player's bed or anchor, or the main world's spawn. */
    static Location home(Player player) {
        Location respawn = player.getRespawnLocation();
        return respawn != null ? respawn : player.getServer().getWorlds().getFirst().getSpawnLocation();
    }
}
