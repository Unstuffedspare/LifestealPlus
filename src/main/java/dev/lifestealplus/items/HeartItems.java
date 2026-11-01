package dev.lifestealplus.items;

import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Hearts you can hold. A Heart adds one to whoever right-clicks it; Heart Fragments
 * combine into a Heart. They look like dyes but never act like them: no crafting, looms,
 * sheep or signs, so nobody loses one by accident.
 */
public final class HeartItems implements Listener {

    private enum Kind { HEART, FRAGMENT }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final NamespacedKey kindKey;

    public HeartItems(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.kindKey = new NamespacedKey(plugin, "heart_item");
    }

    public ItemStack heart(int amount) {
        return make(Kind.HEART, Material.RED_DYE, amount, Component.text("Heart", NamedTextColor.RED),
                "Right-click the air (or sneak-click a block) to gain a heart.");
    }

    public ItemStack fragment(int amount) {
        int needed = plugin.settings().events().fragmentsPerHeart();
        return make(Kind.FRAGMENT, Material.PINK_DYE, amount, Component.text("Heart Fragment", NamedTextColor.LIGHT_PURPLE),
                "Right-click the air with " + needed + " to make a Heart.");
    }

    private ItemStack make(Kind kind, Material material, int amount, Component name, String hint) {
        ItemStack item = new ItemStack(material, amount);
        item.setData(DataComponentTypes.ITEM_NAME, name);
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                Component.text(hint, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))));
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        item.editPersistentDataContainer(data -> data.set(kindKey, PersistentDataType.STRING, kind.name()));
        return item;
    }

    private Kind kind(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        String kind = item.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        return kind == null ? null : Kind.valueOf(kind);
    }

    public boolean isHeartItem(ItemStack item) {
        return kind(item) != null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        Kind kind = kind(item);
        if (kind == null) return;
        // Never a dye: no dyeing signs, no composting.
        event.setUseItemInHand(Event.Result.DENY);
        // On a block, the block gets the click (chests and doors still open) unless the player sneaks.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (!event.getPlayer().isSneaking()) return;
            event.setUseInteractedBlock(Event.Result.DENY);
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (kind == Kind.HEART) useHeart(player, hand);
        else combineFragments(player, hand);
    }

    private void useHeart(Player player, EquipmentSlot hand) {
        if (tracker.isEliminated(player)) {
            player.sendRichMessage("<gray>You can't use a Heart while eliminated.");
            return;
        }
        ItemStack held = player.getInventory().getItem(hand);
        held.subtract();
        // Take it now, give it back if it did nothing, so a double-click can't spend one heart twice.
        tracker.track(hearts.apply(List.of(HeartRequest.add(player.getUniqueId(), 1, HeartCause.HEART_ITEM, player.getName()))))
                .whenCompleteAsync((changes, error) -> {
                    if (error != null || changes.getFirst().delta() == 0) {
                        player.getInventory().addItem(heart(1)).values()
                                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                        player.sendRichMessage(error != null
                                ? "<red>That didn't work; the error is in the server log."
                                : "<gray>You're already at the most hearts you can have.");
                        return;
                    }
                    player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                    player.sendRichMessage("<red>❤</red> <green>+1 heart.</green> <gray>You have <hearts> now.",
                            Placeholder.unparsed("hearts", String.valueOf(changes.getFirst().after())));
                }, tracker.mainThread());
    }

    private void combineFragments(Player player, EquipmentSlot hand) {
        int needed = plugin.settings().events().fragmentsPerHeart();
        ItemStack held = player.getInventory().getItem(hand);
        if (held.getAmount() < needed) {
            player.sendRichMessage("<gray>Hold <needed> Heart Fragments together to make a Heart (you have <have>).",
                    Placeholder.unparsed("needed", String.valueOf(needed)),
                    Placeholder.unparsed("have", String.valueOf(held.getAmount())));
            return;
        }
        held.subtract(needed);
        player.getInventory().addItem(heart(1)).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        player.sendRichMessage("<light_purple>The fragments fuse into a Heart.");
    }

    @EventHandler(ignoreCancelled = true)
    void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isHeartItem(event.getPlayer().getInventory().getItem(event.getHand()))) event.setCancelled(true);
    }

    @EventHandler
    void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isHeartItem(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    /** Looms, anvils, smithing tables and the rest. */
    @EventHandler
    void onPrepareResult(PrepareResultEvent event) {
        for (ItemStack item : event.getInventory().getContents()) {
            if (isHeartItem(item)) {
                event.setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    void onCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter) {
            for (ItemStack item : crafter.getInventory().getContents()) {
                if (isHeartItem(item)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }
}
