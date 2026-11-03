package dev.lifestealplus;

import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.core.RuleException;
import dev.lifestealplus.events.Finale;
import dev.lifestealplus.items.HeartItems;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * What a Revival Charm opens: the heads of eliminated players. Click one, then click again to
 * be sure, and the charm is spent bringing them back.
 */
final class RevivalMenu implements Listener {

    private static final class Page implements InventoryHolder {
        final Map<Integer, PlayerHearts> slots = new HashMap<>();
        /** The slot clicked once; a second click on it revives. */
        int armed = -1;
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final HeartItems items;
    private final Finale finale;
    private final MenuLook look;

    RevivalMenu(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, HeartItems items, Finale finale,
                MenuLook look) {
        this.look = look;
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.items = items;
        this.finale = finale;
        items.onCharmUse(this::open);
    }

    private void open(Player player) {
        if (!plugin.settings().revival().charm().enabled()) {
            Msg.send(player, "common.feature-off");
            return;
        }
        if (tracker.isEliminated(player)) {
            Msg.send(player, "revival.charm-eliminated");
            return;
        }
        int size = look.rows("revival-menu", 1, 6) * 9;
        hearts.eliminatedPlayers(size).whenCompleteAsync((out, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't list eliminated players", error);
                Msg.send(player, "common.error");
                return;
            }
            if (out.isEmpty()) {
                Msg.send(player, "revival.nobody");
                return;
            }
            Page page = new Page();
            page.inventory = plugin.getServer().createInventory(page, size, Msg.get("revival.menu-title"));
            for (int i = 0; i < out.size(); i++) page.slots.put(i, out.get(i));
            draw(page);
            player.openInventory(page.inventory);
            look.play(player, "open");
        }, tracker.mainThread());
    }

    private void draw(Page page) {
        page.inventory.clear();
        page.slots.forEach((slot, target) -> {
            ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(plugin.getServer().getOfflinePlayer(target.uuid())));
            head.setData(DataComponentTypes.ITEM_NAME, Msg.get("revival.head", Msg.t("player", target.name())));
            Component hint = Msg.get(slot == page.armed ? "revival.click-again" : "revival.click",
                    Msg.t("player", target.name()), Msg.t("hearts", hearts.rules().reviveHearts()));
            head.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(hint.decorationIfAbsent(TextDecoration.ITALIC,
                    TextDecoration.State.FALSE))));
            page.inventory.setItem(slot, head);
        });
        look.fill(page.inventory, "revival-menu");
    }

    @EventHandler
    void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Page page)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        PlayerHearts target = page.slots.get(event.getRawSlot());
        if (target == null) return;
        look.play(player, "click");
        if (page.armed != event.getRawSlot()) {
            page.armed = event.getRawSlot();
            draw(page);
            return;
        }
        player.closeInventory();
        revive(player, target);
    }

    @EventHandler
    void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Page) event.setCancelled(true);
    }

    /** Takes the charm first and gives it back if the revival doesn't happen, so one charm can't revive twice. */
    private void revive(Player player, PlayerHearts target) {
        if (finale.isActive()) {
            Msg.send(player, "revival.sealed");
            return;
        }
        int slot = charmSlot(player);
        if (slot < 0) {
            Msg.send(player, "revival.no-charm");
            return;
        }
        ItemStack held = player.getInventory().getItem(slot);
        ItemStack charm = held.asOne();
        held.subtract();
        tracker.track(hearts.reviveWithItem(player.getUniqueId(), target.uuid()).thenApply(List::of))
                .whenCompleteAsync((changes, error) -> {
                    if (error != null) {
                        player.getInventory().addItem(charm).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        if (cause instanceof RuleException rule) Msg.send(player, "common.not-allowed", Msg.t("reason", rule.getMessage()));
                        else Msg.send(player, "common.error");
                        return;
                    }
                    player.playSound(player, Sound.ITEM_TOTEM_USE, 0.8f, 1f);
                    Msg.broadcast("revival.charm-used", Msg.t("player", player.getName()), Msg.t("target", target.name()));
                }, tracker.mainThread());
    }

    private int charmSlot(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (items.isRevivalCharm(contents[i])) return i;
        }
        return -1;
    }
}
