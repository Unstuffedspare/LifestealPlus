package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.core.BountyException;
import dev.lifestealplus.core.BountyTotal;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.LedgerEntry;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.core.VengeanceMark;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /hearts and /lifesteal. Player arguments are names looked up in the database, not
 * online-player selectors, so admins can fix the hearts of someone who's offline.
 */
final class LifestealCommands {

    private static final int LEDGER_PAGE_SIZE = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;

    LifestealCommands(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
    }

    void register(Commands commands) {
        commands.register(heartsCommand(), "Show how many hearts you or another player have");
        commands.register(adminCommand(), "Lifesteal admin tools", List.of("lsp"));
        commands.register(bountyCommand(), "Stake your hearts on another player's head");
        commands.register(vengeanceCommand(), "See who you can take vengeance on, and who can on you");
    }

    private LiteralCommandNode<CommandSourceStack> bountyCommand() {
        return Commands.literal("bounty")
                .requires(src -> src.getSender().hasPermission("lifestealplus.bounty"))
                .executes(ctx -> listBounties(ctx.getSource().getSender()))
                .then(Commands.literal("list").executes(ctx -> listBounties(ctx.getSource().getSender())))
                .then(Commands.literal("check")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_NAMES)
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    withPlayer(sender, StringArgumentType.getString(ctx, "player"), target ->
                                            hearts.bountyOn(target.uuid()).whenCompleteAsync((total, error) -> {
                                                if (error != null) fail(sender, error);
                                                else sender.sendRichMessage(total == 0
                                                                ? "<gray>There's no bounty on <player>."
                                                                : "<gold>There's a <total>-heart bounty on <player>.",
                                                        Placeholder.unparsed("player", target.name()),
                                                        Placeholder.unparsed("total", String.valueOf(total)));
                                            }, tracker.mainThread()));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("place")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_NAMES)
                                .then(Commands.argument("hearts", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getSender() instanceof Player placer)) {
                                                ctx.getSource().getSender().sendRichMessage("<red>Only players can place bounties.");
                                                return 0;
                                            }
                                            int amount = IntegerArgumentType.getInteger(ctx, "hearts");
                                            withPlayer(placer, StringArgumentType.getString(ctx, "player"),
                                                    target -> placeBounty(placer, target, amount));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .build();
    }

    private void placeBounty(Player placer, PlayerHearts target, int amount) {
        hearts.placeBounty(placer.getUniqueId(), target.uuid(), amount).whenCompleteAsync((placed, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                if (cause instanceof BountyException rule) {
                    placer.sendRichMessage("<red><reason>", Placeholder.unparsed("reason", rule.getMessage()));
                } else {
                    fail(placer, error);
                }
                return;
            }
            // Only successful placements reach the tracker, so a refused bounty isn't logged as a failure.
            tracker.track(CompletableFuture.completedFuture(List.of(placed.placerChange())));
            String who = plugin.settings().bountyAnonymous() ? "Someone" : placer.getName();
            plugin.getServer().broadcast(MiniMessage.miniMessage().deserialize(
                    "<gold>💰 <who> has put a <hearts>-heart bounty on <target></gold> <gray>(<total> hearts in total).",
                    Placeholder.unparsed("who", who),
                    Placeholder.unparsed("hearts", String.valueOf(amount)),
                    Placeholder.unparsed("target", target.name()),
                    Placeholder.unparsed("total", String.valueOf(placed.totalOnTarget()))));
            placer.sendRichMessage("<gray>Your bounty is placed. You have <left> hearts left.",
                    Placeholder.unparsed("left", String.valueOf(placed.placerChange().after())));
        }, tracker.mainThread());
    }

    private int listBounties(CommandSender sender) {
        hearts.topBounties(10).whenCompleteAsync((top, error) -> {
            if (error != null) {
                fail(sender, error);
                return;
            }
            if (top.isEmpty()) {
                sender.sendRichMessage("<gray>No bounties right now. Place one with /bounty place <player> <hearts>.");
                return;
            }
            sender.sendRichMessage("<gold>Bounties");
            for (BountyTotal b : top) {
                sender.sendRichMessage(" <gold><hearts>❤</gold> <white><player>",
                        Placeholder.unparsed("hearts", String.valueOf(b.hearts())),
                        Placeholder.unparsed("player", b.targetName()));
            }
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    private LiteralCommandNode<CommandSourceStack> vengeanceCommand() {
        return Commands.literal("vengeance")
                .requires(src -> src.getSender().hasPermission("lifestealplus.vengeance"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendRichMessage("<red>Only players have vengeance marks.");
                        return 0;
                    }
                    hearts.marks(player.getUniqueId()).whenCompleteAsync((marks, error) -> {
                        if (error != null) {
                            fail(player, error);
                            return;
                        }
                        if (marks.held().isEmpty() && marks.against().isEmpty()) {
                            player.sendRichMessage("<gray>No vengeance marks either way.");
                            return;
                        }
                        for (VengeanceMark m : marks.held()) {
                            player.sendRichMessage("<red>You can take vengeance on <player></red> <gray>(<left> left)",
                                    Placeholder.unparsed("player", m.markedName()),
                                    Placeholder.unparsed("left", timeLeft(m.expires())));
                        }
                        for (VengeanceMark m : marks.against()) {
                            player.sendRichMessage("<dark_red><player> can take vengeance on you</dark_red> <gray>(<left> left)",
                                    Placeholder.unparsed("player", m.avengerName()),
                                    Placeholder.unparsed("left", timeLeft(m.expires())));
                        }
                    }, tracker.mainThread());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static String timeLeft(Instant expires) {
        long minutes = Math.max(1, (Duration.between(Instant.now(), expires).toSeconds() + 59) / 60);
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    private LiteralCommandNode<CommandSourceStack> heartsCommand() {
        return Commands.literal("hearts")
                .requires(src -> src.getSender().hasPermission("lifestealplus.hearts"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player self)) {
                        ctx.getSource().getSender().sendRichMessage("<red>Usage: /hearts <player>");
                        return 0;
                    }
                    showHearts(self, self.getName());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> src.getSender().hasPermission("lifestealplus.hearts.others"))
                        .suggests(ONLINE_NAMES)
                        .executes(ctx -> {
                            showHearts(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> adminCommand() {
        return Commands.literal("lifesteal")
                .requires(src -> src.getSender().hasPermission("lifestealplus.admin"))
                .then(amountCommand("give", HeartCause.ADMIN_GIVE))
                .then(amountCommand("take", HeartCause.ADMIN_TAKE))
                .then(amountCommand("set", HeartCause.ADMIN_SET))
                .then(Commands.literal("revive")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_NAMES)
                                .executes(ctx -> revive(ctx, hearts.rules().reviveHearts()))
                                .then(Commands.argument("hearts", IntegerArgumentType.integer(1))
                                        .executes(ctx -> revive(ctx, IntegerArgumentType.getInteger(ctx, "hearts"))))))
                .then(Commands.literal("ledger")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_NAMES)
                                .executes(ctx -> ledger(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> ledger(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("bounty")
                        .then(Commands.literal("clear")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(ONLINE_NAMES)
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            withPlayer(sender, StringArgumentType.getString(ctx, "player"), target ->
                                                    tracker.track(hearts.clearBounties(target.uuid(), sender.getName()))
                                                            .whenCompleteAsync((refunds, error) -> {
                                                                if (error != null) fail(sender, error);
                                                                else sender.sendRichMessage(
                                                                        "<green>Cleared <count> bounties on <player> and refunded the placers.",
                                                                        Placeholder.unparsed("count", String.valueOf(refunds.size())),
                                                                        Placeholder.unparsed("player", target.name()));
                                                            }, tracker.mainThread()));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            try {
                                plugin.reloadSettings();
                                sender.sendRichMessage("<green>LifestealPlus config reloaded. (Changing database.file needs a restart.)");
                            } catch (RuntimeException e) {
                                sender.sendRichMessage("<red>Config not reloaded: <reason>", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> amountCommand(String name, HeartCause cause) {
        return Commands.literal(name)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_NAMES)
                        .then(Commands.argument("amount", IntegerArgumentType.integer(cause == HeartCause.ADMIN_SET ? 0 : 1))
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                    withPlayer(sender, StringArgumentType.getString(ctx, "player"), target -> {
                                        HeartRequest request = switch (cause) {
                                            case ADMIN_SET -> HeartRequest.set(target.uuid(), amount, cause, sender.getName());
                                            case ADMIN_TAKE -> HeartRequest.add(target.uuid(), -amount, cause, sender.getName());
                                            default -> HeartRequest.add(target.uuid(), amount, cause, sender.getName());
                                        };
                                        change(sender, request);
                                    });
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    private int revive(CommandContext<CommandSourceStack> ctx, int amount) {
        CommandSender sender = ctx.getSource().getSender();
        withPlayer(sender, StringArgumentType.getString(ctx, "player"), target -> {
            if (!target.eliminated()) {
                sender.sendRichMessage("<red><player> isn't eliminated (they have <hearts> hearts).",
                        Placeholder.unparsed("player", target.name()),
                        Placeholder.unparsed("hearts", String.valueOf(target.hearts())));
                return;
            }
            change(sender, HeartRequest.set(target.uuid(), amount, HeartCause.REVIVE, sender.getName()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int ledger(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        withPlayer(sender, StringArgumentType.getString(ctx, "player"), target ->
                hearts.ledger(target.uuid(), page, LEDGER_PAGE_SIZE).whenCompleteAsync((result, error) -> {
                    if (error != null) {
                        fail(sender, error);
                        return;
                    }
                    sender.sendRichMessage("<gold>Heart ledger for <player> <gray>(page <page>/<pages>, newest first)",
                            Placeholder.unparsed("player", target.name()),
                            Placeholder.unparsed("page", String.valueOf(result.page())),
                            Placeholder.unparsed("pages", String.valueOf(result.pages())));
                    if (result.entries().isEmpty()) sender.sendRichMessage("<gray>Nothing on this page.");
                    result.entries().forEach(entry -> sender.sendRichMessage(LEDGER_LINE, ledgerTags(entry)));
                }, tracker.mainThread()));
        return Command.SINGLE_SUCCESS;
    }

    private static final String LEDGER_LINE =
            "<dark_gray>#<event></dark_gray> <gray><time></gray> <delta> <white><before>→<after></white> <yellow><cause></yellow><detail>";

    private static TagResolver ledgerTags(LedgerEntry e) {
        int delta = e.after() - e.before();
        String deltaText = delta > 0 ? "<green>+" + delta : delta < 0 ? "<red>" + delta : "<gray>±0";
        StringBuilder detail = new StringBuilder();
        if (e.otherName() != null) detail.append(" vs ").append(e.otherName());
        if (e.actor() != null && !e.actor().equals("server")) detail.append(" by ").append(e.actor());
        if (e.note() != null) detail.append(" (").append(e.note()).append(")");
        return TagResolver.resolver(
                Placeholder.unparsed("event", String.valueOf(e.eventId())),
                Placeholder.unparsed("time", TIME.format(e.at())),
                Placeholder.parsed("delta", deltaText),
                Placeholder.unparsed("before", String.valueOf(e.before())),
                Placeholder.unparsed("after", String.valueOf(e.after())),
                Placeholder.unparsed("cause", e.cause().name()),
                Placeholder.unparsed("detail", detail.toString()));
    }

    private void change(CommandSender sender, HeartRequest request) {
        tracker.track(hearts.apply(List.of(request))).whenCompleteAsync((changes, error) -> {
            if (error != null) {
                fail(sender, error);
                return;
            }
            HeartChange c = changes.getFirst();
            String suffix = c.after() == c.before() ? " <gray>(no change: at the cap, or eliminated)" : "";
            sender.sendRichMessage("<green><player>: <before> → <after> hearts" + suffix,
                    Placeholder.unparsed("player", c.name()),
                    Placeholder.unparsed("before", String.valueOf(c.before())),
                    Placeholder.unparsed("after", String.valueOf(c.after())));
        }, tracker.mainThread());
    }

    private void showHearts(CommandSender sender, String name) {
        withPlayer(sender, name, target -> {
            if (target.eliminated()) {
                sender.sendRichMessage("<red><player> is eliminated.", Placeholder.unparsed("player", target.name()));
            } else {
                sender.sendRichMessage("<red>❤</red> <player> has <hearts>/<max> hearts.",
                        Placeholder.unparsed("player", target.name()),
                        Placeholder.unparsed("hearts", String.valueOf(target.hearts())),
                        Placeholder.unparsed("max", String.valueOf(hearts.rules().maxHearts())));
            }
        });
    }

    /** Looks a player up by name off the main thread, then runs {@code then} back on it. */
    private void withPlayer(CommandSender sender, String name, Consumer<PlayerHearts> then) {
        hearts.findByName(name).whenCompleteAsync((found, error) -> {
            if (error != null) {
                fail(sender, error);
            } else if (found.isEmpty()) {
                sender.sendRichMessage("<red>No player named <name> has joined this server.", Placeholder.unparsed("name", name));
            } else {
                then.accept(found.get());
            }
        }, tracker.mainThread());
    }

    private void fail(CommandSender sender, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Lifesteal command failed", error);
        sender.sendRichMessage("<red>That didn't work; the error is in the server log.");
    }

    private static final SuggestionProvider<CommandSourceStack> ONLINE_NAMES = (ctx, builder) -> {
        String typed = builder.getRemainingLowerCase();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(typed)) builder.suggest(p.getName());
        }
        return builder.buildFuture();
    };
}
