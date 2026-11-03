package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.admin.AdminCommands;
import dev.lifestealplus.admin.ImportCommand;
import dev.lifestealplus.combat.NewbieShield;
import dev.lifestealplus.core.BountyTotal;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.LedgerEntry;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.core.RuleException;
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
import net.kyori.adventure.text.Component;
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
    private final MostWanted mostWanted;
    private final NewbieShield shield;
    private final EventCommands events;
    private final AdminCommands admin;
    private final FeaturesCommand features;
    private final ImportCommand importer;
    private final SettingsMenu settingsMenu;
    private final PersonalCaps caps;

    LifestealCommands(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, MostWanted mostWanted,
                      NewbieShield shield, EventCommands events, AdminCommands admin, FeaturesCommand features,
                      ImportCommand importer, SettingsMenu settingsMenu, PersonalCaps caps) {
        this.caps = caps;
        this.importer = importer;
        this.settingsMenu = settingsMenu;
        this.events = events;
        this.admin = admin;
        this.features = features;
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.mostWanted = mostWanted;
        this.shield = shield;
    }

    void register(Commands commands) {
        commands.register(heartsCommand(), "Show how many hearts you or another player have");
        commands.register(adminCommand(), "Lifesteal admin tools", List.of("lsp"));
        commands.register(bountyCommand(), "Stake your hearts on another player's head");
        commands.register(vengeanceCommand(), "See who you can take vengeance on, and who can on you");
        commands.register(shieldCommand(), "See how long your new-player shield lasts");
        commands.register(admin.leaderboardCommand(), "Most hearts this season, or a past season's final standings");
    }

    private LiteralCommandNode<CommandSourceStack> shieldCommand() {
        return Commands.literal("shield")
                .requires(src -> src.getSender().hasPermission("lifestealplus.shield"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        Msg.send(ctx.getSource().getSender(), "common.players-only");
                        return 0;
                    }
                    if (shield.isShielded(player)) {
                        Msg.send(player, "shield.status", Msg.t("time", NewbieShield.describe(shield.left(player))));
                    } else {
                        Msg.send(player, "shield.none");
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
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
                                                else Msg.send(sender, total == 0 ? "bounty.check-none" : "bounty.check",
                                                        Msg.t("player", target.name()), Msg.t("total", total));
                                            }, tracker.mainThread()));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("place")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_NAMES)
                                .then(Commands.argument("hearts", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getSender() instanceof Player placer)) {
                                                Msg.send(ctx.getSource().getSender(), "common.players-only");
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
        if (!plugin.settings().bountiesEnabled()) {
            Msg.send(placer, "common.feature-off");
            return;
        }
        hearts.placeBounty(placer.getUniqueId(), target.uuid(), amount).whenCompleteAsync((placed, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                if (cause instanceof RuleException rule) {
                    Msg.send(placer, "common.not-allowed", Msg.t("reason", rule.getMessage()));
                } else {
                    fail(placer, error);
                }
                return;
            }
            // Only successful placements reach the tracker, so a refused bounty isn't logged as a failure.
            tracker.track(CompletableFuture.completedFuture(List.of(placed.placerChange())));
            Msg.broadcast(plugin.settings().bountyAnonymous() ? "bounty.placed-anonymous" : "bounty.placed",
                    Msg.t("who", placer.getName()), Msg.t("hearts", amount), Msg.t("target", target.name()),
                    Msg.t("total", placed.totalOnTarget()));
            Msg.send(placer, "bounty.placed-you", Msg.t("left", placed.placerChange().after()));
        }, tracker.mainThread());
    }

    private int listBounties(CommandSender sender) {
        hearts.topBounties(10).whenCompleteAsync((top, error) -> {
            if (error != null) {
                fail(sender, error);
                return;
            }
            if (top.isEmpty()) {
                Msg.send(sender, "bounty.list-empty");
                return;
            }
            Msg.send(sender, "bounty.list-header");
            for (BountyTotal b : top) {
                Msg.send(sender, "bounty.list-line", Msg.t("hearts", b.hearts()), Msg.t("player", b.targetName()));
            }
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    private LiteralCommandNode<CommandSourceStack> vengeanceCommand() {
        return Commands.literal("vengeance")
                .requires(src -> src.getSender().hasPermission("lifestealplus.vengeance"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        Msg.send(ctx.getSource().getSender(), "common.players-only");
                        return 0;
                    }
                    hearts.marks(player.getUniqueId()).whenCompleteAsync((marks, error) -> {
                        if (error != null) {
                            fail(player, error);
                            return;
                        }
                        if (marks.held().isEmpty() && marks.against().isEmpty()) {
                            Msg.send(player, "vengeance.none");
                            return;
                        }
                        for (VengeanceMark m : marks.held()) {
                            Msg.send(player, "vengeance.you-can-avenge", Msg.t("player", m.markedName()), Msg.t("left", timeLeft(m.expires())));
                        }
                        for (VengeanceMark m : marks.against()) {
                            Msg.send(player, "vengeance.can-avenge-you", Msg.t("player", m.avengerName()), Msg.t("left", timeLeft(m.expires())));
                        }
                    }, tracker.mainThread());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static String timeLeft(Instant expires) {
        return Msg.duration(Duration.between(Instant.now(), expires));
    }

    private LiteralCommandNode<CommandSourceStack> heartsCommand() {
        return Commands.literal("hearts")
                .requires(src -> src.getSender().hasPermission("lifestealplus.hearts"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player self)) {
                        Msg.send(ctx.getSource().getSender(), "hearts.usage");
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
                                                                else Msg.send(sender, "admin.bounties-cleared",
                                                                        Msg.t("count", refunds.size()), Msg.t("player", target.name()));
                                                            }, tracker.mainThread()));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("shield")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_NAMES)
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            Duration amount = Duration.ofMinutes(IntegerArgumentType.getInteger(ctx, "minutes"));
                                            withPlayer(sender, StringArgumentType.getString(ctx, "player"), target ->
                                                    hearts.setShield(target.uuid(), amount).whenCompleteAsync((ok, error) -> {
                                                        if (error != null) {
                                                            fail(sender, error);
                                                            return;
                                                        }
                                                        shield.changed(target.uuid(), amount);
                                                        Msg.send(sender, amount.isZero() ? "admin.shield-removed" : "admin.shield-set",
                                                                Msg.t("player", target.name()), Msg.t("time", NewbieShield.describe(amount)));
                                                    }, tracker.mainThread()));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(features.node())
                .then(settingsMenu.settingsNode())
                .then(settingsMenu.configNode())
                .then(settingsMenu.presetNode())
                .then(importer.node())
                .then(admin.altsNode())
                .then(admin.flagsNode())
                .then(admin.seasonNode())
                .then(events.eventNode())
                .then(events.finaleNode())
                .then(events.itemNode())
                .then(Commands.literal("mostwanted")
                        .executes(ctx -> {
                            if (mostWanted.announce() == null) {
                                Msg.send(ctx.getSource().getSender(), "admin.most-wanted-nobody");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            List<String> warnings = plugin.reloadSettings();
                            Msg.send(sender, warnings.isEmpty() ? "admin.reloaded" : "admin.reloaded-with-warnings",
                                    Msg.t("count", warnings.size()));
                            warnings.forEach(w -> Msg.send(sender, "admin.reload-warning", Msg.t("warning", w)));
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
                Msg.send(sender, "admin.not-eliminated", Msg.t("player", target.name()), Msg.t("hearts", target.hearts()));
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
                    Msg.send(sender, "ledger.header", Msg.t("player", target.name()), Msg.t("page", result.page()),
                            Msg.t("pages", result.pages()));
                    if (result.entries().isEmpty()) Msg.send(sender, "ledger.empty-page");
                    result.entries().forEach(entry -> Msg.send(sender, "ledger.line", ledgerTags(entry)));
                }, tracker.mainThread()));
        return Command.SINGLE_SUCCESS;
    }

    private static TagResolver ledgerTags(LedgerEntry e) {
        int delta = e.after() - e.before();
        Component deltaText = Msg.get(delta > 0 ? "ledger.gain" : delta < 0 ? "ledger.loss" : "ledger.no-change",
                Msg.t("n", Math.abs(delta)));
        Component detail = Component.empty();
        if (e.otherName() != null) detail = detail.append(Msg.get("ledger.versus", Msg.t("player", e.otherName())));
        if (e.actor() != null && !e.actor().equals("server")) detail = detail.append(Msg.get("ledger.by", Msg.t("player", e.actor())));
        if (e.note() != null) detail = detail.append(Msg.get("ledger.note", Msg.t("note", e.note())));
        return TagResolver.resolver(
                Msg.t("event", e.eventId()),
                Msg.t("time", TIME.format(e.at())),
                Msg.c("delta", deltaText),
                Msg.t("before", e.before()),
                Msg.t("after", e.after()),
                Msg.t("cause", e.cause().name()),
                Msg.c("detail", detail));
    }

    private void change(CommandSender sender, HeartRequest request) {
        tracker.track(hearts.apply(List.of(request))).whenCompleteAsync((changes, error) -> {
            if (error != null) {
                fail(sender, error);
                return;
            }
            HeartChange c = changes.getFirst();
            Msg.send(sender, c.after() == c.before() ? "admin.hearts-unchanged" : "admin.hearts-changed",
                    Msg.t("player", c.name()), Msg.t("before", c.before()), Msg.t("after", c.after()));
        }, tracker.mainThread());
    }

    private void showHearts(CommandSender sender, String name) {
        withPlayer(sender, name, target -> {
            if (target.eliminated()) {
                Msg.send(sender, "hearts.eliminated", Msg.t("player", target.name()));
            } else {
                Msg.send(sender, "hearts.show", Msg.t("player", target.name()), Msg.t("hearts", target.hearts()),
                        Msg.t("max", caps.maxFor(target.uuid(), hearts.rules())));
            }
        });
    }

    /** Looks a player up by name off the main thread, then runs {@code then} back on it. */
    private void withPlayer(CommandSender sender, String name, Consumer<PlayerHearts> then) {
        hearts.findByName(name).whenCompleteAsync((found, error) -> {
            if (error != null) {
                fail(sender, error);
            } else if (found.isEmpty()) {
                Msg.send(sender, "common.no-such-player", Msg.t("name", name));
            } else {
                then.accept(found.get());
            }
        }, tracker.mainThread());
    }

    private void fail(CommandSender sender, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Lifesteal command failed", error);
        Msg.send(sender, "common.error");
    }

    private static final SuggestionProvider<CommandSourceStack> ONLINE_NAMES = (ctx, builder) -> {
        String typed = builder.getRemainingLowerCase();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(typed)) builder.suggest(p.getName());
        }
        return builder.buildFuture();
    };
}
