package dev.lifestealplus.combat;

import dev.lifestealplus.EliminationHandler;
import dev.lifestealplus.KillHandler;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartService;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Logging out mid-fight doesn't save you. The player's body stays behind for a while,
 * wearing their skin and gear; killing it counts as killing them. If it survives, nothing
 * happens. Main thread only.
 */
public final class CombatBodies implements Listener {

    /** A body waiting in the world. {@code items} is what drops if it dies: the owner's whole inventory at logout. */
    private record Body(UUID entity, ItemStack[] items, int exp, BukkitTask expiry) {
    }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final KillHandler kills;
    private final CombatTagger tagger;
    private final NamespacedKey ownerTag;
    private final Map<UUID, Body> byOwner = new HashMap<>();

    public CombatBodies(LifestealPlugin plugin, HeartService hearts, KillHandler kills, CombatTagger tagger) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.kills = kills;
        this.tagger = tagger;
        this.ownerTag = new NamespacedKey(plugin, "body_of");
    }

    /** Admin kicks and crashes on our side don't count; leaving or losing connection does. */
    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean tagged = tagger.isTagged(player);
        tagger.untag(player);
        if (!tagged || player.isDead()) return;
        if (event.getReason() != PlayerQuitEvent.QuitReason.DISCONNECTED
                && event.getReason() != PlayerQuitEvent.QuitReason.TIMED_OUT) return;
        Duration life = plugin.settings().combat().body();
        if (life.isZero()) return;
        leaveBody(player, life);
    }

    private void leaveBody(Player player, Duration life) {
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        Mannequin body = player.getWorld().spawn(player.getLocation(), Mannequin.class, CreatureSpawnEvent.SpawnReason.CUSTOM, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            m.customName(Component.text(player.getName()));
            m.setCustomNameVisible(true);
            m.setDescription(Msg.get("combat-log.body-label"));
            // For show only: when the body dies, onBodyDeath replaces its drops with the owner's real inventory.
            for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
                m.getEquipment().setItem(slot, player.getInventory().getItem(slot).clone());
            }
            m.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHealth);
            m.setHealth(Math.min(player.getHealth(), maxHealth));
            m.setRemoveWhenFarAway(false);
            // A restart clears bodies rather than leaving one with nobody tracking it.
            m.setPersistent(false);
            m.getPersistentDataContainer().set(ownerTag, PersistentDataType.STRING, player.getUniqueId().toString());
        });

        ItemStack[] items = Arrays.stream(player.getInventory().getContents())
                .map(item -> item == null ? null : item.clone())
                .toArray(ItemStack[]::new);
        // What a vanilla death drops.
        int exp = Math.min(player.getLevel() * 7, 100);
        UUID owner = player.getUniqueId();
        String name = player.getName();
        BukkitTask expiry = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Body left = byOwner.remove(owner);
            if (left == null) return;
            Entity e = plugin.getServer().getEntity(left.entity());
            if (e != null) e.remove();
            Msg.broadcast("combat-log.body-faded", Msg.t("player", name));
        }, life.toSeconds() * 20);
        byOwner.put(owner, new Body(body.getUniqueId(), items, exp, expiry));

        Msg.broadcast("combat-log.logged-out", Msg.t("player", name), Msg.t("seconds", life.toSeconds()));
    }

    @EventHandler
    void onBodyDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mannequin mannequin)) return;
        String ownerId = mannequin.getPersistentDataContainer().get(ownerTag, PersistentDataType.STRING);
        if (ownerId == null) return;
        UUID owner = UUID.fromString(ownerId);
        Body body = byOwner.remove(owner);
        event.getDrops().clear();
        if (body == null) return;
        body.expiry().cancel();

        for (ItemStack item : body.items()) {
            if (item != null && !item.isEmpty()) event.getDrops().add(item);
        }
        event.setDroppedExp(body.exp());
        hearts.recordBodyDeath(owner).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Couldn't record a body death for " + owner, error);
            return null;
        });

        String name = plugin.getServer().getOfflinePlayer(owner).getName();
        Player killer = mannequin.getKiller();
        if (killer != null && !killer.getUniqueId().equals(owner)) {
            kills.pvpKill(owner, killer.getUniqueId());
            Msg.broadcast("combat-log.body-killed", Msg.t("killer", killer.getName()), Msg.t("player", name));
        } else {
            kills.naturalDeath(owner, "body", mannequin.getLocation());
            Msg.broadcast("combat-log.body-destroyed", Msg.t("player", name));
        }
    }

    /** Back before the body was killed: step back into it, wherever it is and however hurt. */
    public void rejoined(Player player) {
        Body body = byOwner.remove(player.getUniqueId());
        if (body == null) return;
        body.expiry().cancel();
        if (plugin.getServer().getEntity(body.entity()) instanceof Mannequin mannequin && !mannequin.isDead()) {
            player.teleport(mannequin.getLocation());
            player.setHealth(Math.min(mannequin.getHealth(), player.getAttribute(Attribute.MAX_HEALTH).getValue()));
            mannequin.remove();
            Msg.send(player, "combat-log.back-in-body");
        }
    }

    /** The body died while they were away: their items already dropped there, so this is the rest of a death. */
    public void finishBodyDeath(Player player) {
        player.getInventory().clear();
        player.setLevel(0);
        player.setExp(0);
        player.setFireTicks(0);
        player.setFoodLevel(20);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.teleport(EliminationHandler.home(player));
        Msg.send(player, "combat-log.body-died-while-away");
    }

    /** On disable: bodies vanish without anyone dying. */
    public void shutdown() {
        byOwner.values().forEach(body -> {
            body.expiry().cancel();
            Entity e = plugin.getServer().getEntity(body.entity());
            if (e != null) e.remove();
        });
        byOwner.clear();
    }

}
