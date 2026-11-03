package dev.lifestealplus.combat;

import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.PluginSettings.CombatItemRules;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;

/**
 * Switches for the items lifesteal servers argue about most: totems, end crystals, and the
 * respawn anchors and beds that explode outside their own dimension. Switched off, they stop
 * working against players but keep working otherwise (crystals still respawn the dragon).
 */
public final class CombatItems implements Listener {

    private final LifestealPlugin plugin;

    public CombatItems(LifestealPlugin plugin) {
        this.plugin = plugin;
    }

    private CombatItemRules rules() {
        return plugin.settings().combatItems();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onResurrect(EntityResurrectEvent event) {
        if (rules().totems() || !(event.getEntity() instanceof Player player)) return;
        event.setCancelled(true);
        Msg.send(player, "combat-items.totem-off");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        var source = event.getDamageSource();
        if (!rules().endCrystals() && source.getDirectEntity() instanceof EnderCrystal) {
            event.setCancelled(true);
        } else if (!rules().respawnBombs() && source.getDamageType() == DamageType.BAD_RESPAWN_POINT) {
            event.setCancelled(true);
        }
    }
}
