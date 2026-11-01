package dev.lifestealplus;

import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Strength I and Speed I for players on their last hearts, so a low player can still turn
 * a fight and finishing them off carries some risk. The effects are infinite and only ever
 * removed when they're the infinite ones this class added, so beacons and potions are left alone.
 */
final class LastStand {

    private static final PotionEffectType[] EFFECTS = {PotionEffectType.STRENGTH, PotionEffectType.SPEED};

    private final LifestealPlugin plugin;
    private final NamespacedKey tag;

    LastStand(LifestealPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "last_stand");
    }

    /** Safe to call any time: adds what's missing (after death or milk), removes it once they're out of danger. */
    void update(Player player, int hearts) {
        int threshold = plugin.settings().lastStandHearts();
        boolean inLastStand = hearts > 0 && hearts <= threshold;
        var data = player.getPersistentDataContainer();
        boolean tagged = data.has(tag, PersistentDataType.BOOLEAN);

        if (inLastStand) {
            for (PotionEffectType type : EFFECTS) {
                PotionEffect current = player.getPotionEffect(type);
                if (current == null || !current.isInfinite()) {
                    player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, 0, true, false, true));
                }
            }
            if (!tagged) {
                data.set(tag, PersistentDataType.BOOLEAN, true);
                player.sendRichMessage("<gold><bold>Last Stand.</bold></gold> <yellow>You're down to your last hearts: "
                        + "Strength and Speed until you climb back.");
                player.playSound(player, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.4f);
            }
        } else if (tagged) {
            data.remove(tag);
            for (PotionEffectType type : EFFECTS) {
                PotionEffect current = player.getPotionEffect(type);
                if (current != null && current.isInfinite() && current.getAmplifier() == 0) player.removePotionEffect(type);
            }
        }
    }
}
