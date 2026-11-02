package dev.lifestealplus.items;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
        this.mintedKey = new NamespacedKey(plugin, "heart_minted");
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
    private final NamespacedKey mintedKey;

    /** A fresh Heart: its fading (if any) counts from now, and its tooltip says when. */
    public ItemStack heart(int amount) {
        ItemStack item = make(Kind.HEART, Material.RED_DYE, amount, Msg.get("items.heart-name"), Msg.get("items.heart-hint"));
        long now = System.currentTimeMillis();
        item.editPersistentDataContainer(data -> data.set(mintedKey, PersistentDataType.LONG, now));
        int decayDays = plugin.settings().social().decayDays();
        if (decayDays > 0) {
            Instant minted = Instant.ofEpochMilli(now);
            item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                    loreLine(Msg.get("items.heart-hint")),
                    loreLine(Msg.get("items.heart-fading",
                            Msg.t("starts", DATE.format(minted.plus(Duration.ofDays(1)))),
                            Msg.t("gone", DATE.format(minted.plus(Duration.ofDays(decayDays)))))))));
        }
        return item;
    }

    /** Lore is italic unless told otherwise; ours isn't. */
    private static Component loreLine(Component line) {
        return line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /**
     * How much of a Heart is left, in days of value out of decayDays: each full day since it
     * was made takes one off. Hearts from before decay existed never fade.
     */
    private int daysLeft(ItemStack item) {
        int decayDays = plugin.settings().social().decayDays();
        Long minted = item.getPersistentDataContainer().get(mintedKey, PersistentDataType.LONG);
        if (decayDays <= 0 || minted == null) return decayDays <= 0 ? 1 : decayDays;
        long age = Duration.between(Instant.ofEpochMilli(minted), Instant.now()).toDays();
        return (int) Math.max(0, decayDays - age);
    }

    public ItemStack fragment(int amount) {
        int needed = plugin.settings().events().fragmentsPerHeart();
        return make(Kind.FRAGMENT, Material.PINK_DYE, amount, Msg.get("items.fragment-name"),
                Msg.get("items.fragment-hint", Msg.t("needed", needed)));
    }

    private ItemStack make(Kind kind, Material material, int amount, Component name, Component hint) {
        ItemStack item = new ItemStack(material, amount);
        item.setData(DataComponentTypes.ITEM_NAME, name);
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(loreLine(hint))));
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
            Msg.send(player, "items.cant-use-eliminated");
            return;
        }
        ItemStack held = player.getInventory().getItem(hand);
        ItemStack original = held.asOne();
        int decayDays = plugin.settings().social().decayDays();
        int left = daysLeft(held);
        if (decayDays > 0 && left < decayDays) {
            held.subtract();
            fadedHeart(player, left, decayDays);
            return;
        }
        held.subtract();
        // Take it now, give it back if it did nothing, so a double-click can't spend one heart twice.
        tracker.track(hearts.apply(List.of(HeartRequest.add(player.getUniqueId(), 1, HeartCause.HEART_ITEM, player.getName()))))
                .whenCompleteAsync((changes, error) -> {
                    if (error != null || changes.getFirst().delta() == 0) {
                        // The very same item, so its fade date doesn't reset.
                        player.getInventory().addItem(original).values()
                                .forEach(leftOver -> player.getWorld().dropItem(player.getLocation(), leftOver));
                        Msg.send(player, error != null ? "common.error" : "items.at-cap");
                        return;
                    }
                    player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                    Msg.send(player, "items.heart-used", Msg.t("hearts", changes.getFirst().after()));
                }, tracker.mainThread());
    }

    /** A Heart that has lost value breaks into the fragments it's still worth, or into nothing. */
    private void fadedHeart(Player player, int daysLeft, int decayDays) {
        int fragments = plugin.settings().events().fragmentsPerHeart() * daysLeft / decayDays;
        if (fragments <= 0) {
            player.playSound(player, Sound.BLOCK_SAND_BREAK, 1f, 0.8f);
            Msg.send(player, "items.heart-crumbled");
            return;
        }
        player.getInventory().addItem(fragment(fragments)).values()
                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 0.9f);
        Msg.send(player, "items.heart-faded", Msg.t("n", fragments));
    }

    private void combineFragments(Player player, EquipmentSlot hand) {
        int needed = plugin.settings().events().fragmentsPerHeart();
        ItemStack held = player.getInventory().getItem(hand);
        if (held.getAmount() < needed) {
            Msg.send(player, "items.need-more-fragments", Msg.t("needed", needed), Msg.t("have", held.getAmount()));
            return;
        }
        held.subtract(needed);
        player.getInventory().addItem(heart(1)).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        Msg.send(player, "items.fragments-fused");
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
