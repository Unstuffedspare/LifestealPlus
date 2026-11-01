package dev.lifestealplus.purgatory;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The gear everyone fights with in Purgatory. Handed out fresh on arrival, on respawn,
 * and before each trial, so a bad run never snowballs. Every piece is tagged so it can
 * be recognised and never leaves Purgatory.
 */
final class Kit {

    private final NamespacedKey tag;

    Kit(Plugin plugin) {
        this.tag = new NamespacedKey(plugin, "purgatory_kit");
    }

    void give(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setItem(0, item(Material.IRON_SWORD, 1));
        inv.setItem(1, item(Material.BOW, 1));
        inv.setItem(2, item(Material.COOKED_BEEF, 12));
        inv.setItem(3, item(Material.GOLDEN_APPLE, 1));
        inv.setItem(9, item(Material.ARROW, 24));
        inv.setItemInOffHand(item(Material.SHIELD, 1));
        inv.setHelmet(item(Material.CHAINMAIL_HELMET, 1));
        inv.setChestplate(item(Material.CHAINMAIL_CHESTPLATE, 1));
        inv.setLeggings(item(Material.CHAINMAIL_LEGGINGS, 1));
        inv.setBoots(item(Material.CHAINMAIL_BOOTS, 1));
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(10);
        player.setFireTicks(0);
    }

    boolean isKit(ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(tag, PersistentDataType.BOOLEAN);
    }

    private ItemStack item(Material material, int amount) {
        ItemStack item = new ItemStack(material, amount);
        item.editMeta(meta -> {
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }
}
