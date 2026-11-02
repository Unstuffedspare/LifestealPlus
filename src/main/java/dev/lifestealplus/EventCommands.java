package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.lifestealplus.events.Airdrops;
import dev.lifestealplus.events.BloodMoon;
import dev.lifestealplus.events.Finale;
import dev.lifestealplus.items.HeartItems;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Admin controls for server events, the finale, and heart items, under /lifesteal. */
final class EventCommands {

    private final LifestealPlugin plugin;
    private final HeartItems items;
    private final BloodMoon bloodMoon;
    private final Airdrops airdrops;
    private final Finale finale;

    EventCommands(LifestealPlugin plugin, HeartItems items, BloodMoon bloodMoon, Airdrops airdrops, Finale finale) {
        this.plugin = plugin;
        this.items = items;
        this.bloodMoon = bloodMoon;
        this.airdrops = airdrops;
        this.finale = finale;
    }

    /** /lifesteal event bloodmoon start [minutes] | stop, /lifesteal event airdrop [here], /lifesteal event status */
    LiteralArgumentBuilder<CommandSourceStack> eventNode() {
        return Commands.literal("event")
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource().getSender())))
                .then(Commands.literal("bloodmoon")
                        .then(Commands.literal("start")
                                .executes(ctx -> startBloodMoon(ctx, plugin.settings().events().bloodMoon().length()))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                        .executes(ctx -> startBloodMoon(ctx,
                                                Duration.ofMinutes(IntegerArgumentType.getInteger(ctx, "minutes"))))))
                        .then(Commands.literal("stop").executes(ctx -> {
                            if (!bloodMoon.end()) Msg.send(ctx.getSource().getSender(), "admin.no-blood-moon");
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("airdrop")
                        .executes(ctx -> {
                            if (!airdrops.announce(randomDrop(), plugin.settings().events().airdrops().warning())) {
                                Msg.send(ctx.getSource().getSender(), "admin.airdrop-busy");
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("here").executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                Msg.send(ctx.getSource().getSender(), "common.players-only");
                                return 0;
                            }
                            if (!airdrops.announce(player.getLocation().add(3, 0, 0), Duration.ofSeconds(10))) {
                                Msg.send(player, "admin.airdrop-busy");
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    /** /lifesteal finale start <minutes> [final size] | stop */
    LiteralArgumentBuilder<CommandSourceStack> finaleNode() {
        return Commands.literal("finale")
                .then(Commands.literal("start")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                .executes(ctx -> startFinale(ctx, 50))
                                .then(Commands.argument("size", IntegerArgumentType.integer(5))
                                        .executes(ctx -> startFinale(ctx, IntegerArgumentType.getInteger(ctx, "size"))))))
                .then(Commands.literal("stop").executes(ctx -> {
                    if (!finale.stop()) Msg.send(ctx.getSource().getSender(), "admin.no-finale");
                    return Command.SINGLE_SUCCESS;
                }));
    }

    /** /lifesteal item <player> heart|fragment [amount], for prizes and testing. */
    LiteralArgumentBuilder<CommandSourceStack> itemNode() {
        return Commands.literal("item")
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(itemKind("heart"))
                        .then(itemKind("fragment")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> itemKind(String kind) {
        return Commands.literal(kind)
                .executes(ctx -> giveItem(ctx, kind, 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> giveItem(ctx, kind, IntegerArgumentType.getInteger(ctx, "amount"))));
    }

    private int giveItem(CommandContext<CommandSourceStack> ctx, String kind, int amount) {
        CommandSender sender = ctx.getSource().getSender();
        Player target = plugin.getServer().getPlayerExact(StringArgumentType.getString(ctx, "player"));
        if (target == null) {
            Msg.send(sender, "common.not-online");
            return 0;
        }
        ItemStack item = kind.equals("heart") ? items.heart(amount) : items.fragment(amount);
        target.getInventory().addItem(item).values().forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        Msg.send(sender, "admin.items-given", Msg.t("player", target.getName()), Msg.t("amount", amount), Msg.t("kind", kind));
        return Command.SINGLE_SUCCESS;
    }

    private int startBloodMoon(CommandContext<CommandSourceStack> ctx, Duration length) {
        if (!bloodMoon.start(length)) Msg.send(ctx.getSource().getSender(), "admin.blood-moon-already");
        return Command.SINGLE_SUCCESS;
    }

    private int startFinale(CommandContext<CommandSourceStack> ctx, int size) {
        Duration over = Duration.ofMinutes(IntegerArgumentType.getInteger(ctx, "minutes"));
        if (!finale.start(over, size)) Msg.send(ctx.getSource().getSender(), "admin.finale-already");
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSender sender) {
        Msg.send(sender, "admin.status-blood-moon", Msg.c("state", bloodMoon.isActive() ? Msg.get("admin.state-now") : describe(bloodMoon.nextRising())));
        Msg.send(sender, "admin.status-airdrop", Msg.c("state", describe(airdrops.nextDrop())));
        Msg.send(sender, "admin.status-finale", Msg.c("state", Msg.get(finale.isActive() ? "admin.state-running" : "admin.state-not-running")));
        return Command.SINGLE_SUCCESS;
    }

    private static net.kyori.adventure.text.Component describe(Instant when) {
        if (when == null) return Msg.get("admin.state-not-scheduled");
        return Msg.get("admin.state-in", Msg.t("time", Msg.duration(Duration.between(Instant.now(), when))));
    }

    /** Same spread as the scheduled drops. */
    private org.bukkit.Location randomDrop() {
        var world = plugin.getServer().getWorlds().getFirst();
        var spawn = world.getSpawnLocation();
        double angle = Math.random() * 2 * Math.PI;
        double distance = plugin.settings().events().airdrops().radius() * Math.sqrt(Math.random());
        return new org.bukkit.Location(world, spawn.getX() + Math.cos(angle) * distance, 0, spawn.getZ() + Math.sin(angle) * distance);
    }
}
