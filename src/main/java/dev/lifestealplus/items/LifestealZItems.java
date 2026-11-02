package dev.lifestealplus.items;

import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * After switching over from LifeStealZ, its heart items still sit in inventories and chests
 * but nothing uses them any more. This swaps them for this plugin's Hearts, keeping their
 * value, the moment a player joins or opens a container holding one. Its revive items have
 * no equivalent here and are left alone.
 */
public final class LifestealZItems implements Listener {

    private static final NamespacedKey TYPE = NamespacedKey.fromString("lifestealz:customitemtype");
    private static final NamespacedKey VALUE = NamespacedKey.fromString("lifestealz:customheartvalue");

    private final LifestealPlugin plugin;
    private final HeartItems items;

    public LifestealZItems(LifestealPlugin plugin, HeartItems items) {
        this.plugin = plugin;
        this.items = items;
    }

    private boolean on() {
        return plugin.settings().extras().convertLifestealZItems();
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        if (!on()) return;
        Player player = event.getPlayer();
        int hearts = convert(player.getInventory()) + convert(player.getEnderChest());
        if (hearts > 0) Msg.send(player, "import.items-converted", Msg.t("n", hearts));
    }

    @EventHandler
    void onOpen(InventoryOpenEvent event) {
        if (on()) convert(event.getInventory());
    }

    /** Returns how many hearts' worth was converted. */
    private int convert(Inventory inventory) {
        int converted = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) continue;
            var data = item.getPersistentDataContainer();
            if (!"heart".equalsIgnoreCase(data.get(TYPE, PersistentDataType.STRING))) continue;
            Integer value = data.get(VALUE, PersistentDataType.INTEGER);
            int total = Math.max(1, value == null ? 1 : value) * item.getAmount();
            inventory.setItem(slot, items.heart(Math.min(64, total)));
            if (total > 64) inventory.addItem(items.heart(total - 64));
            converted += total;
        }
        return converted;
    }
}
