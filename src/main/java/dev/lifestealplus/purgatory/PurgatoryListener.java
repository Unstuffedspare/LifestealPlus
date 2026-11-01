package dev.lifestealplus.purgatory;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Keeps Purgatory self-contained: no stray spawns, no loot, no lost kits, no falling out of the world. */
public final class PurgatoryListener implements Listener {

    private final PurgatoryWorld world;
    private final TrialManager trials;

    public PurgatoryListener(PurgatoryWorld world, TrialManager trials) {
        this.world = world;
        this.trials = trials;
    }

    /** Only trials spawn things here (and admins, by command or egg). */
    @EventHandler(ignoreCancelled = true)
    void onSpawn(CreatureSpawnEvent event) {
        if (!world.contains(event.getLocation())) return;
        switch (event.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG -> { }
            default -> event.setCancelled(true);
        }
    }

    @EventHandler
    void onMobDeath(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent || !trials.isTrialMob(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    /** Kits aren't loot: dying here drops nothing, and the kit comes back on respawn. */
    @EventHandler(priority = EventPriority.HIGH)
    void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!world.contains(player.getLocation())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepLevel(true);
        trials.died(player);
    }

    @EventHandler(ignoreCancelled = true)
    void onDrop(PlayerDropItemEvent event) {
        if (world.contains(event.getPlayer().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    void onAltar(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null || !event.getClickedBlock().equals(world.altar())) return;
        event.setCancelled(true);
        trials.call(event.getPlayer());
    }

    /** Falling off an island puts you back on it rather than killing you. */
    @EventHandler(ignoreCancelled = true)
    void onFall(PlayerMoveEvent event) {
        if (event.getTo().getY() > PurgatoryWorld.FLOOR_Y - 20 || !world.contains(event.getTo())) return;
        Player player = event.getPlayer();
        player.setFallDistance(0);
        player.teleport(trials.isParticipant(player) ? world.arenaCenter() : world.hub());
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        trials.leave(event.getPlayer());
    }

    @EventHandler
    void onWorldChange(PlayerChangedWorldEvent event) {
        if (event.getFrom().equals(world.world())) trials.leave(event.getPlayer());
    }
}
