package dev.lifestealplus.admin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.AltFlag;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.core.SeasonStanding;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;

/** /lifesteal alts, flags and season (admins), and /leaderboard (everyone). */
public final class AdminCommands {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final SeasonReset seasons;

    public AdminCommands(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, SeasonReset seasons) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.seasons = seasons;
    }

    public LiteralArgumentBuilder<CommandSourceStack> altsNode() {
        return Commands.literal("alts")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String name = StringArgumentType.getString(ctx, "player");
                            hearts.findByName(name)
                                    .thenCompose(found -> found.map(p -> hearts.linkedAccounts(p.uuid())
                                                    .thenApply(linked -> new Lookup(p, linked)))
                                            .orElse(java.util.concurrent.CompletableFuture.completedFuture(null)))
                                    .whenCompleteAsync((lookup, error) -> {
                                        if (error != null) {
                                            fail(sender, error);
                                        } else if (lookup == null) {
                                            Msg.send(sender, "common.no-such-player", Msg.t("name", name));
                                        } else if (lookup.linked().isEmpty()) {
                                            Msg.send(sender, "alts.none", Msg.t("player", lookup.player().name()));
                                        } else {
                                            Msg.send(sender, "alts.header", Msg.t("player", lookup.player().name()));
                                            for (PlayerHearts other : lookup.linked()) {
                                                Msg.send(sender, "alts.line", Msg.t("name", other.name()), Msg.t("hearts", other.hearts()));
                                            }
                                        }
                                    }, tracker.mainThread());
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private record Lookup(PlayerHearts player, List<PlayerHearts> linked) {
    }

    public LiteralArgumentBuilder<CommandSourceStack> flagsNode() {
        return Commands.literal("flags").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            hearts.altFlags(10).whenCompleteAsync((flags, error) -> {
                if (error != null) {
                    fail(sender, error);
                    return;
                }
                if (flags.isEmpty()) {
                    Msg.send(sender, "alts.flags-none");
                    return;
                }
                Msg.send(sender, "alts.flags-header");
                for (AltFlag f : flags) {
                    Msg.send(sender, "alts.flags-line", Msg.t("time", TIME.format(f.at())), Msg.t("killer", f.killerName()),
                            Msg.t("victim", f.victimName()));
                }
            }, tracker.mainThread());
            return Command.SINGLE_SUCCESS;
        });
    }

    public LiteralArgumentBuilder<CommandSourceStack> seasonNode() {
        return Commands.literal("season")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    seasons.currentName().thenCombine(hearts.seasons(), (current, archived) -> {
                        Msg.send(sender, "season.current", Msg.t("name", current));
                        Msg.send(sender, "season.archived", Msg.c("list", archived.isEmpty()
                                ? Msg.get("season.none-archived") : net.kyori.adventure.text.Component.text(String.join(", ", archived))));
                        return null;
                    }).exceptionally(error -> {
                        fail(sender, error);
                        return null;
                    });
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reset")
                        .then(Commands.argument("next", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    seasons.request(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "next").trim());
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("confirm").executes(ctx -> {
                    seasons.confirm(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    /** Everyone: the live top ten, or an archived season's final standings. */
    public LiteralCommandNode<CommandSourceStack> leaderboardCommand() {
        return Commands.literal("leaderboard")
                .requires(src -> src.getSender().hasPermission("lifestealplus.leaderboard"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    seasons.currentName().thenCombine(hearts.topHearts(10), Live::new).whenCompleteAsync((live, error) -> {
                        if (error != null) {
                            fail(sender, error);
                            return;
                        }
                        Msg.send(sender, "leaderboard.live-header", Msg.t("season", live.season()));
                        int rank = 0;
                        for (PlayerHearts p : live.top()) {
                            Msg.send(sender, "leaderboard.live-line", Msg.t("rank", ++rank), Msg.t("name", p.name()), Msg.t("hearts", p.hearts()));
                        }
                        if (live.top().isEmpty()) Msg.send(sender, "leaderboard.empty");
                    }, tracker.mainThread());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("season", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String season = StringArgumentType.getString(ctx, "season").trim();
                            if (!historyVisibleTo(sender)) {
                                Msg.send(sender, "common.feature-off");
                                return Command.SINGLE_SUCCESS;
                            }
                            hearts.seasonStandings(season).whenCompleteAsync((standings, error) -> {
                                if (error != null) {
                                    fail(sender, error);
                                    return;
                                }
                                if (standings.isEmpty()) {
                                    Msg.send(sender, "leaderboard.no-such-season", Msg.t("season", season));
                                    return;
                                }
                                Msg.send(sender, "leaderboard.final-header", Msg.t("season", season));
                                for (SeasonStanding s : standings.subList(0, Math.min(10, standings.size()))) {
                                    Msg.send(sender, "leaderboard.final-line", Msg.t("rank", s.rank()), Msg.t("name", s.name()),
                                            Msg.t("hearts", s.hearts()), Msg.t("kills", s.kills()), Msg.t("deaths", s.deaths()),
                                            Msg.t("elims", s.eliminations()));
                                }
                            }, tracker.mainThread());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private record Live(String season, List<PlayerHearts> top) {
    }

    private boolean historyVisibleTo(CommandSender sender) {
        return plugin.settings().extras().publicSeasons() || sender.hasPermission("lifestealplus.admin");
    }

    /** Everyone (unless seasons.public-history is off): past seasons and who finished on top. */
    public LiteralCommandNode<CommandSourceStack> seasonsCommand() {
        return Commands.literal("seasons")
                .requires(src -> src.getSender().hasPermission("lifestealplus.leaderboard")).executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            if (!historyVisibleTo(sender)) {
                Msg.send(sender, "common.feature-off");
                return Command.SINGLE_SUCCESS;
            }
            seasons.currentName().thenCombine(hearts.seasons(), Seasons::new).whenCompleteAsync((all, error) -> {
                if (error != null) {
                    fail(sender, error);
                    return;
                }
                Msg.send(sender, "season.current", Msg.t("name", all.current()));
                if (all.archived().isEmpty()) {
                    Msg.send(sender, "season.archived", Msg.c("list", Msg.get("season.none-archived")));
                    return;
                }
                for (String past : all.archived()) {
                    hearts.seasonStandings(past).whenCompleteAsync((standings, err) -> {
                        if (err != null || standings.isEmpty()) return;
                        Msg.send(sender, "season.past-line", Msg.t("season", past), Msg.t("winner", standings.getFirst().name()),
                                Msg.t("hearts", standings.getFirst().hearts()));
                    }, tracker.mainThread());
                }
            }, tracker.mainThread());
            return Command.SINGLE_SUCCESS;
        }).build();
    }

    private record Seasons(String current, List<String> archived) {
    }

    private void fail(CommandSender sender, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Admin command failed", error);
        Msg.send(sender, "common.error");
    }
}
