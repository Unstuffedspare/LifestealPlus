package dev.lifestealplus.purgatory;

import dev.lifestealplus.EliminationHandler;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/**
 * Eliminated players live here until they've won enough trials (or someone revives
 * them). Whatever they were carrying when they arrived is stashed on the player and
 * handed back when they leave, so an admin elimination never eats an inventory.
 */
public final class Purgatory implements EliminationHandler {

    private final LifestealPlugin plugin;
    private final PurgatoryWorld world;
    private final Kit kit;
    private final NamespacedKey stashKey;
    /** Set while an eliminated player roams the overworld as a ghost instead of waiting in Purgatory. */
    private final NamespacedKey ghostKey;
    private TrialManager trials;

    public Purgatory(LifestealPlugin plugin, PurgatoryWorld world) {
        this.plugin = plugin;
        this.world = world;
        this.kit = new Kit(plugin);
        this.stashKey = new NamespacedKey(plugin, "purgatory_stash");
        this.ghostKey = new NamespacedKey(plugin, "ghost");
    }

    PurgatoryWorld world() {
        return world;
    }

    Kit kit() {
        return kit;
    }

    public void setTrials(TrialManager trials) {
        this.trials = trials;
    }

    @Override
    public void eliminate(Player player) {
        stash(player);
        player.teleport(world.hub());
        player.setGameMode(GameMode.ADVENTURE);
        kit.give(player);
        player.showTitle(Title.title(
                Msg.get("purgatory.arrived-title"),
                Msg.get("purgatory.arrived-subtitle"),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        player.playSound(player, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1f, 0.8f);
        Msg.send(player, "purgatory.arrived", Msg.t("needed", plugin.settings().purgatory().fragmentsNeeded()));
    }

    @Override
    public void enforce(Player player) {
        if (isGhost(player)) {
            if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
            return;
        }
        Location at = player.getLocation();
        if (!world.contains(at) || (world.inArena(at) && !trials.isParticipant(player))) {
            player.teleport(world.hub());
        }
        if (player.getGameMode() != GameMode.ADVENTURE) player.setGameMode(GameMode.ADVENTURE);
        if (Arrays.stream(player.getInventory().getContents()).noneMatch(kit::isKit)) kit.give(player);
    }

    @Override
    public void restore(Player player, int hearts) {
        trials.leave(player);
        player.getPersistentDataContainer().remove(ghostKey);
        player.getInventory().clear();
        unstash(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(EliminationHandler.home(player));
        player.setHealth(hearts * 2.0);
        player.setFoodLevel(20);
        player.showTitle(Title.title(Msg.get("purgatory.revived-title"), Msg.get("purgatory.revived-subtitle", Msg.t("hearts", hearts))));
        player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        Msg.send(player, "purgatory.revived", Msg.t("hearts", hearts));
    }

    @Override
    public double eliminatedMaxHealth() {
        return 20.0;
    }

    public boolean isGhost(Player player) {
        return player.getPersistentDataContainer().has(ghostKey, PersistentDataType.BOOLEAN);
    }

    /**
     * Swaps an eliminated player between waiting in Purgatory and drifting through the
     * overworld as a spectator. Returns true if they're a ghost now.
     */
    public boolean toggleGhost(Player player) {
        var data = player.getPersistentDataContainer();
        if (isGhost(player)) {
            data.remove(ghostKey);
            player.teleport(world.hub());
            player.setGameMode(GameMode.ADVENTURE);
            kit.give(player);
            Msg.send(player, "ghosts.back-to-purgatory");
            return false;
        }
        trials.leave(player);
        data.set(ghostKey, PersistentDataType.BOOLEAN, true);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(plugin.getServer().getWorlds().getFirst().getSpawnLocation());
        Msg.send(player, "ghosts.now-a-ghost");
        return true;
    }

    @Override
    public Location respawnLocation(Player player) {
        return world.hub();
    }

    /** Keeps whatever the player was holding on arrival; merges with an earlier stash rather than overwriting it. */
    private void stash(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean anything = false;
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].isEmpty() || kit.isKit(contents[i])) contents[i] = ItemStack.empty();
            else anything = true;
        }
        if (!anything) return;
        var data = player.getPersistentDataContainer();
        byte[] earlier = data.get(stashKey, PersistentDataType.BYTE_ARRAY);
        if (earlier != null) {
            List<ItemStack> merged = new ArrayList<>(Arrays.asList(ItemStack.deserializeItemsFromBytes(earlier)));
            merged.addAll(Arrays.asList(contents));
            contents = merged.toArray(ItemStack[]::new);
        }
        data.set(stashKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(contents));
        inv.clear();
    }

    private void unstash(Player player) {
        var data = player.getPersistentDataContainer();
        byte[] bytes = data.get(stashKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return;
        data.remove(stashKey);
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
        PlayerInventory inv = player.getInventory();
        if (items.length == inv.getSize()) {
            inv.setContents(items);
            return;
        }
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) continue;
            inv.addItem(item).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        }
    }
}
