package dev.lifestealplus;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * How the menus look, from menus.yml: the item behind each button, sizes, a filler for empty
 * slots, and the sounds. Anything missing or misspelled falls back to the shipped look.
 */
final class MenuLook {

    private final LifestealPlugin plugin;
    private volatile YamlConfiguration look = new YamlConfiguration();

    MenuLook(LifestealPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "menus.yml");
        if (!file.exists()) plugin.saveResource("menus.yml", false);
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        loaded.setDefaults(YamlConfiguration.loadConfiguration(
                new InputStreamReader(plugin.getResource("menus.yml"), StandardCharsets.UTF_8)));
        look = loaded;
    }

    /** The item for a button, like "settings-menu.feature-on"; the shipped one if the name isn't an item. */
    Material icon(String key) {
        Material chosen = item(look.getString(key));
        if (chosen != null) return chosen;
        Material shipped = item(look.getDefaults() == null ? null : look.getDefaults().getString(key));
        return shipped != null ? shipped : Material.PAPER;
    }

    /** A preset's icon. */
    Material presetIcon(String preset) {
        return look.isSet("presets-menu.icons." + preset) ? icon("presets-menu.icons." + preset) : icon("presets-menu.other");
    }

    int rows(String menu, int min, int max) {
        return Math.max(min, Math.min(max, look.getInt(menu + ".rows", max)));
    }

    /** Puts the menu's filler in every empty slot, if it has one. */
    void fill(Inventory inventory, String menu) {
        Material filler = item(look.getString(menu + ".filler"));
        if (filler == null) return;
        ItemStack pane = ItemStack.of(filler);
        pane.setData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_DISPLAY,
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, pane);
        }
    }

    /** "open", "click", "changed" or "refused". */
    void play(Player player, String which) {
        String name = look.getString("sounds." + which, "none").strip().toLowerCase(Locale.ROOT);
        if (name.equals("none") || !name.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) return;
        player.playSound(Sound.sound(Key.key(name), Sound.Source.MASTER, 0.6f, 1f));
    }

    private static Material item(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) return null;
        Material material = Material.matchMaterial(name.strip());
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }
}
