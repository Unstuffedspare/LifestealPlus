package dev.lifestealplus.admin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
                                            sender.sendRichMessage("<red>No player named <name> has joined.", Placeholder.unparsed("name", name));
                                        } else if (lookup.linked().isEmpty()) {
                                            sender.sendRichMessage("<gray><player> shares no address with any other account.",
                                                    Placeholder.unparsed("player", lookup.player().name()));
                                        } else {
                                            sender.sendRichMessage("<gold><player> shares an address with:",
                                                    Placeholder.unparsed("player", lookup.player().name()));
                                            for (PlayerHearts other : lookup.linked()) {
                                                sender.sendRichMessage(" <white><name></white> <gray>(<hearts> hearts)",
                                                        Placeholder.unparsed("name", other.name()),
                                                        Placeholder.unparsed("hearts", String.valueOf(other.hearts())));
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
                    sender.sendRichMessage("<gray>No kills between linked accounts so far.");
                    return;
                }
                sender.sendRichMessage("<gold>Kills between accounts sharing an address <gray>(newest first)");
                for (AltFlag f : flags) {
                    sender.sendRichMessage(" <gray><time></gray> <white><killer></white> killed <white><victim></white>",
                            Placeholder.unparsed("time", TIME.format(f.at())),
                            Placeholder.unparsed("killer", f.killerName()),
                            Placeholder.unparsed("victim", f.victimName()));
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
                        sender.sendRichMessage("<gold>Current season:</gold> <white><name>", Placeholder.unparsed("name", current));
                        sender.sendRichMessage("<gold>Archived:</gold> <gray><list>",
                                Placeholder.unparsed("list", archived.isEmpty() ? "none yet" : String.join(", ", archived)));
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
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    seasons.currentName().thenCombine(hearts.topHearts(10), Live::new).whenCompleteAsync((live, error) -> {
                        if (error != null) {
                            fail(sender, error);
                            return;
                        }
                        sender.sendRichMessage("<gold><season>: most hearts", Placeholder.unparsed("season", live.season()));
                        int rank = 0;
                        for (PlayerHearts p : live.top()) {
                            sender.sendRichMessage(" <gray>#<rank></gray> <white><name></white> <red><hearts>❤",
                                    Placeholder.unparsed("rank", String.valueOf(++rank)),
                                    Placeholder.unparsed("name", p.name()),
                                    Placeholder.unparsed("hearts", String.valueOf(p.hearts())));
                        }
                        if (live.top().isEmpty()) sender.sendRichMessage("<gray>Nobody yet.");
                    }, tracker.mainThread());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("season", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String season = StringArgumentType.getString(ctx, "season").trim();
                            hearts.seasonStandings(season).whenCompleteAsync((standings, error) -> {
                                if (error != null) {
                                    fail(sender, error);
                                    return;
                                }
                                if (standings.isEmpty()) {
                                    sender.sendRichMessage("<gray>No archived season called <season>.", Placeholder.unparsed("season", season));
                                    return;
                                }
                                sender.sendRichMessage("<gold><season>: final standings", Placeholder.unparsed("season", season));
                                for (SeasonStanding s : standings.subList(0, Math.min(10, standings.size()))) {
                                    sender.sendRichMessage(" <gray>#<rank></gray> <white><name></white> <red><hearts>❤</red> "
                                                    + "<gray><kills> kills, <deaths> deaths, eliminated <elims>×",
                                            Placeholder.unparsed("rank", String.valueOf(s.rank())),
                                            Placeholder.unparsed("name", s.name()),
                                            Placeholder.unparsed("hearts", String.valueOf(s.hearts())),
                                            Placeholder.unparsed("kills", String.valueOf(s.kills())),
                                            Placeholder.unparsed("deaths", String.valueOf(s.deaths())),
                                            Placeholder.unparsed("elims", String.valueOf(s.eliminations())));
                                }
                            }, tracker.mainThread());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private record Live(String season, List<PlayerHearts> top) {
    }

    private void fail(CommandSender sender, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Admin command failed", error);
        sender.sendRichMessage("<red>That didn't work; the error is in the server log.");
    }
}
