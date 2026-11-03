package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.items.HeartItems;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /heartshop: buy Hearts, Heart Fragments and Revival Charms with money, through Vault and
 * whatever economy plugin the server runs. Off unless heart-shop.enabled, and a daily limit
 * keeps money from buying a season outright.
 */
final class HeartShop {

    /** Takes money from a player; the one part that needs Vault. */
    interface Wallet {
        /** Takes {@code amount} if they have it; false (and nothing taken) if they don't. */
        boolean charge(Player player, double amount);

        String format(double amount);
    }

    private record Bought(LocalDate day, int count) {
    }

    private final LifestealPlugin plugin;
    private final HeartItems items;
    /** Looked up each time: the economy plugin may enable after this one. Gives null without one. */
    private final java.util.function.Supplier<Wallet> wallets;
    private final Map<UUID, Bought> today = new ConcurrentHashMap<>();

    HeartShop(LifestealPlugin plugin, HeartItems items, java.util.function.Supplier<Wallet> wallets) {
        this.plugin = plugin;
        this.items = items;
        this.wallets = wallets;
    }

    LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("heartshop")
                .requires(src -> src.getSender().hasPermission("lifestealplus.heartshop"))
                .executes(ctx -> list(ctx.getSource().getSender()))
                .then(Commands.literal("buy")
                        .then(product("heart", items::heart, () -> shop().heartPrice()))
                        .then(product("fragment", items::fragment, () -> shop().fragmentPrice()))
                        .then(product("charm", items::revivalCharm, () -> shop().charmPrice())))
                .build();
    }

    private PluginSettings.Shop shop() {
        return plugin.settings().economy().shop();
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> product(
            String name, IntFunction<ItemStack> make, java.util.function.DoubleSupplier price) {
        return Commands.literal(name)
                .executes(ctx -> buy(ctx.getSource().getSender(), name, make, price.getAsDouble(), 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> buy(ctx.getSource().getSender(), name, make, price.getAsDouble(),
                                IntegerArgumentType.getInteger(ctx, "amount"))));
    }

    /** The wallet if the shop can sell right now; null (having said why) otherwise. */
    private Wallet open(CommandSender sender) {
        if (!shop().enabled()) {
            Msg.send(sender, "common.feature-off");
            return null;
        }
        Wallet wallet = wallets.get();
        if (wallet == null) Msg.send(sender, "shop.no-economy");
        return wallet;
    }

    private int list(CommandSender sender) {
        Wallet wallet = open(sender);
        if (wallet == null) return 0;
        Msg.send(sender, "shop.header");
        Msg.send(sender, "shop.line", Msg.t("item", "heart"), Msg.t("price", wallet.format(shop().heartPrice())));
        Msg.send(sender, "shop.line", Msg.t("item", "fragment"), Msg.t("price", wallet.format(shop().fragmentPrice())));
        if (shop().charmPrice() > 0) {
            Msg.send(sender, "shop.line", Msg.t("item", "charm"), Msg.t("price", wallet.format(shop().charmPrice())));
        }
        if (shop().dailyLimit() > 0) Msg.send(sender, "shop.limit", Msg.t("n", shop().dailyLimit()));
        return Command.SINGLE_SUCCESS;
    }

    private int buy(CommandSender sender, String name, IntFunction<ItemStack> make, double price, int amount) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "common.players-only");
            return 0;
        }
        Wallet wallet = open(player);
        if (wallet == null) return 0;
        if (price <= 0) {
            Msg.send(player, "shop.not-sold", Msg.t("item", name));
            return 0;
        }
        // A rank's lifestealplus.shop-limit.<n> replaces the daily limit for its players.
        int ranked = PersonalCaps.highest(player, "lifestealplus.shop-limit.");
        int limit = ranked > 0 ? ranked : shop().dailyLimit();
        LocalDate day = LocalDate.now();
        Bought bought = today.get(player.getUniqueId());
        int already = bought != null && bought.day().equals(day) ? bought.count() : 0;
        if (limit > 0 && already + amount > limit) {
            Msg.send(player, "shop.over-limit", Msg.t("n", limit), Msg.t("left", Math.max(0, limit - already)));
            return 0;
        }
        double total = price * amount;
        if (!wallet.charge(player, total)) {
            Msg.send(player, "shop.too-poor", Msg.t("price", wallet.format(total)));
            return 0;
        }
        today.put(player.getUniqueId(), new Bought(day, already + amount));
        player.getInventory().addItem(make.apply(amount)).values()
                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        Msg.send(player, "shop.bought", Msg.t("n", amount), Msg.t("item", name), Msg.t("price", wallet.format(total)));
        plugin.getLogger().info(player.getName() + " bought " + amount + " " + name + " for " + wallet.format(total) + ".");
        return Command.SINGLE_SUCCESS;
    }
}
