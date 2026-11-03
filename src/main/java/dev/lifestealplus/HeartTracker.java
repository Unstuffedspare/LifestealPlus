package dev.lifestealplus;

import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.event.HeartsChangedEvent;
import dev.lifestealplus.event.PlayerEliminatedEvent;
import dev.lifestealplus.event.PlayerRevivedEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Applies committed heart state to the live server: max health, spectator mode for
 * eliminated players, and the eliminated/revived events. The database is the source
 * of truth; this only mirrors it onto players, always on the main thread.
 */
public final class HeartTracker {

    private final LifestealPlugin plugin;
    private final Executor mainThread;
    /** Last known hearts for online players. Read from command suggestions and listeners. */
    private final Map<UUID, Integer> online = new ConcurrentHashMap<>();
    /** Set on players this plugin eliminated, so revives don't touch admins who chose spectator. */
    private final NamespacedKey eliminatedTag;
    private EliminationHandler elimination = new SpectatorElimination();
    private final LastStand lastStand;

    HeartTracker(LifestealPlugin plugin) {
        this.plugin = plugin;
        this.mainThread = task -> {
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, task);
        };
        this.eliminatedTag = new NamespacedKey(plugin, "eliminated");
        this.lastStand = new LastStand(plugin);
        // Milk and /effect clear can strip Last Stand between heart changes; put it back.
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> online.forEach((id, hearts) -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && !player.isDead()) lastStand.update(player, hearts);
        }), 200, 200);
    }

    public Executor mainThread() {
        return mainThread;
    }

    void setEliminationHandler(EliminationHandler elimination) {
        this.elimination = elimination;
    }

    /** Mirrors the outcome of a heart operation once it commits. Logs failures instead of losing them. */
    public CompletableFuture<List<HeartChange>> track(CompletableFuture<List<HeartChange>> pending) {
        return pending.whenCompleteAsync((changes, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Heart change failed", error);
                return;
            }
            changes.forEach(this::applyChange);
        }, mainThread);
    }

    private void applyChange(HeartChange change) {
        plugin.getServer().getPluginManager().callEvent(new HeartsChangedEvent(change));
        Player player = plugin.getServer().getPlayer(change.player());
        if (player != null) {
            online.put(change.player(), change.after());
            sync(player, change.after());
        }
        if (change.eliminatedNow()) {
            plugin.getServer().getPluginManager().callEvent(new PlayerEliminatedEvent(change));
            announceElimination(change);
        }
        if (change.revivedNow()) {
            plugin.getServer().getPluginManager().callEvent(new PlayerRevivedEvent(change));
            Msg.broadcast("elimination.revived", Msg.t("player", change.name()), Msg.t("hearts", change.after()));
        }
    }

    /** Said differently for a kill, a death to the world, or an admin; with a title and a sound for everyone. */
    private void announceElimination(HeartChange change) {
        String key = switch (change.cause()) {
            case PVP_DEATH -> change.otherName() == null ? "elimination.eliminated" : "elimination.by-player";
            case DEATH -> "elimination.by-world";
            case ADMIN_SET, ADMIN_TAKE -> "elimination.by-admin";
            default -> "elimination.eliminated";
        };
        String killer = change.otherName() == null ? "" : change.otherName();
        Msg.broadcast(key, Msg.t("player", change.name()), Msg.t("killer", killer));
        PluginSettings.Extras extras = plugin.settings().extras();
        if (extras.eliminationTitle()) {
            plugin.getServer().showTitle(Title.title(Msg.get("elimination.title", Msg.t("player", change.name())),
                    Msg.get("elimination.subtitle", Msg.t("player", change.name()), Msg.t("killer", killer))));
        }
        if (extras.eliminationSound() != null) {
            plugin.getServer().playSound(net.kyori.adventure.sound.Sound.sound(
                    net.kyori.adventure.key.Key.key(extras.eliminationSound()), net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f));
        }
    }

    public void joined(Player player, PlayerHearts hearts) {
        online.put(player.getUniqueId(), hearts.hearts());
        sync(player, hearts.hearts());
    }

    public void left(Player player) {
        online.remove(player.getUniqueId());
    }

    /** Sends eliminated players to the handler's respawn point, then re-applies their state once they're back. */
    public void respawning(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Integer hearts = online.get(player.getUniqueId());
        if (hearts == null) return;
        if (hearts <= 0) {
            Location where = elimination.respawnLocation(player);
            if (where != null) event.setRespawnLocation(where);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Integer now = online.get(player.getUniqueId());
            if (now != null) sync(player, now);
        });
    }

    public Integer cachedHearts(UUID uuid) {
        return online.get(uuid);
    }

    public boolean isEliminated(Player player) {
        Integer hearts = online.get(player.getUniqueId());
        return hearts != null && hearts <= 0;
    }

    /**
     * Brings one online player in line with their heart count. Safe to call repeatedly:
     * it only changes what differs. Dead players are left alone until they respawn.
     */
    private void sync(Player player, int hearts) {
        if (!player.isOnline()) return;
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = hearts > 0 ? hearts * 2.0 : elimination.eliminatedMaxHealth();
        if (maxHealth != null && maxHealth.getBaseValue() != max) {
            maxHealth.setBaseValue(max);
            if (player.getHealth() > max) player.setHealth(max);
        }
        if (player.isDead()) return;

        // The tag marks players this plugin moved, so restoring never touches an admin who chose spectator.
        var tags = player.getPersistentDataContainer();
        boolean taggedEliminated = tags.has(eliminatedTag, PersistentDataType.BOOLEAN);
        if (hearts <= 0 && !taggedEliminated) {
            tags.set(eliminatedTag, PersistentDataType.BOOLEAN, true);
            elimination.eliminate(player);
        } else if (hearts <= 0) {
            elimination.enforce(player);
        } else if (taggedEliminated) {
            tags.remove(eliminatedTag);
            elimination.restore(player, hearts);
        }
        lastStand.update(player, hearts);
    }
}
