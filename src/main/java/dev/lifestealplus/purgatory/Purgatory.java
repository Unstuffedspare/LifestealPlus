package dev.lifestealplus.purgatory;

import dev.lifestealplus.EliminationHandler;
import dev.lifestealplus.LifestealPlugin;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
    private TrialManager trials;

    public Purgatory(LifestealPlugin plugin, PurgatoryWorld world) {
        this.plugin = plugin;
        this.world = world;
        this.kit = new Kit(plugin);
        this.stashKey = new NamespacedKey(plugin, "purgatory_stash");
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
                Component.text("Purgatory", NamedTextColor.DARK_AQUA),
                Component.text("Earn your way back", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        player.playSound(player, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1f, 0.8f);
        player.sendRichMessage("""
                <dark_aqua>You've lost your last heart and fallen into Purgatory.</dark_aqua>
                <gray>Win <white><needed></white> trials to earn your way back. Right-click the lodestone or use \
                <white>/purgatory start</white> to call one; others here can join you.""",
                Placeholder.unparsed("needed", String.valueOf(plugin.settings().purgatory().fragmentsNeeded())));
    }

    @Override
    public void enforce(Player player) {
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
        player.getInventory().clear();
        unstash(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(EliminationHandler.home(player));
        player.setHealth(hearts * 2.0);
        player.setFoodLevel(20);
        player.showTitle(Title.title(
                Component.text("Revived", NamedTextColor.GREEN),
                Component.text(hearts + " hearts", NamedTextColor.GRAY)));
        player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        player.sendRichMessage("<green>You've clawed your way out of Purgatory with <hearts> hearts.",
                Placeholder.unparsed("hearts", String.valueOf(hearts)));
    }

    @Override
    public double eliminatedMaxHealth() {
        return 20.0;
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
