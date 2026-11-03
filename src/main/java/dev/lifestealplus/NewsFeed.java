package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.NewsItem;
import dev.lifestealplus.core.PlayerStats;
import dev.lifestealplus.core.PlayerStreak;
import dev.lifestealplus.core.Recap;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeping everyone caught up: a short "while you were away" summary when a player comes back
 * after a while, /news for the latest happenings, and /streak.
 */
final class NewsFeed implements Listener {

    private static final int NEWS_LINES = 10;
    /** Wait a moment after joining, so the summary isn't lost among the join messages. */
    private static final long RECAP_DELAY_TICKS = 60;

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;

    NewsFeed(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        long sixHours = 20L * 60 * 60 * 6;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::prune, 20, sixHours);
    }

    void register(Commands commands) {
        commands.register(newsCommand(), "What's been happening on the server");
        commands.register(streakCommand(), "Your kill streak, or another player's");
        commands.register(statsCommand(), "Your season record, or another player's");
    }

    private PluginSettings.RecapSettings settings() {
        return plugin.settings().recap();
    }

    private void prune() {
        hearts.pruneNews(settings().keep()).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Couldn't clear out old news", error);
            return 0;
        });
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!settings().enabled()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            hearts.recap(player.getUniqueId(), settings().minAway(), settings().lines()).whenCompleteAsync((recap, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Couldn't put together news for " + player.getName(), error);
                    return;
                }
                if (player.isOnline()) recap.ifPresent(r -> show(player, r));
            }, tracker.mainThread());
        }, RECAP_DELAY_TICKS);
    }

    /** Recorded even while the recap is off, so switching it on later works straight away. */
    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        markLeft(event.getPlayer().getUniqueId());
    }

    /** The server stopping doesn't send quit events to plugins, so mark everyone still online. */
    void shutdown() {
        plugin.getServer().getOnlinePlayers().forEach(p -> markLeft(p.getUniqueId()));
    }

    private void markLeft(UUID player) {
        hearts.markLeft(player).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Couldn't note when a player left", error);
            return null;
        });
    }

    private void show(Player player, Recap recap) {
        Msg.send(player, "recap.header", Msg.t("time", Msg.duration(recap.away())));
        for (NewsItem item : oldestFirst(recap.items())) Msg.send(player, "recap.line", Msg.c("text", line(item)));
        int more = recap.total() - recap.items().size();
        if (more > 0) Msg.send(player, "recap.more", Msg.t("n", more));
    }

    private static List<NewsItem> oldestFirst(List<NewsItem> newestFirst) {
        List<NewsItem> items = new ArrayList<>(newestFirst);
        Collections.reverse(items);
        return items;
    }

    /** One piece of news in words, from news.&lt;kind&gt; in messages.yml. */
    static Component line(NewsItem item) {
        return Msg.get("news." + item.kind(),
                Msg.t("player", item.player() == null ? "" : item.player()),
                Msg.t("other", item.other() == null ? "" : item.other()),
                Msg.t("n", item.amount()));
    }

    // ---- /news ----

    private LiteralCommandNode<CommandSourceStack> newsCommand() {
        return Commands.literal("news")
                .requires(src -> src.getSender().hasPermission("lifestealplus.news"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    hearts.latestNews(NEWS_LINES).whenCompleteAsync((news, error) -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "Couldn't read the news", error);
                            Msg.send(sender, "common.error");
                            return;
                        }
                        if (news.isEmpty()) {
                            Msg.send(sender, "news.empty");
                            return;
                        }
                        Msg.send(sender, "news.header");
                        Instant now = Instant.now();
                        for (NewsItem item : oldestFirst(news)) {
                            Msg.send(sender, "news.line", Msg.t("ago", Msg.duration(Duration.between(item.at(), now))),
                                    Msg.c("text", line(item)));
                        }
                    }, tracker.mainThread());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // ---- /streak ----

    /** /stats [player] [season]: kills, deaths, hearts won and lost, bounties, comebacks. */
    private LiteralCommandNode<CommandSourceStack> statsCommand() {
        return Commands.literal("stats")
                .requires(src -> src.getSender().hasPermission("lifestealplus.stats"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (sender instanceof Player) showStats(sender, sender.getName(), null);
                    else Msg.send(sender, "common.players-only");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            plugin.getServer().getOnlinePlayers().forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            showStats(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("season", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    showStats(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"),
                                            StringArgumentType.getString(ctx, "season"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    private void showStats(CommandSender sender, String name, String season) {
        if (season != null && !plugin.settings().extras().publicSeasons() && !sender.hasPermission("lifestealplus.admin")) {
            Msg.send(sender, "stats.history-private");
            return;
        }
        hearts.stats(name, season).whenCompleteAsync((found, error) -> {
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Couldn't read stats for " + name, error);
                Msg.send(sender, "common.error");
                return;
            }
            if (found.isEmpty()) {
                Msg.send(sender, season == null ? "stats.unknown" : "stats.unknown-in-season",
                        Msg.t("player", name), Msg.t("season", season == null ? "" : season));
                return;
            }
            PlayerStats s = found.get();
            Msg.send(sender, "stats.header", Msg.t("player", s.name()), Msg.t("season", s.season()));
            Msg.send(sender, "stats.fights", Msg.t("kills", s.kills()), Msg.t("deaths", s.deaths()),
                    Msg.t("streak", s.bestStreak() < 0 ? "-" : String.valueOf(s.bestStreak())));
            Msg.send(sender, "stats.hearts", Msg.t("taken", s.heartsTaken()), Msg.t("lost", s.heartsLost()),
                    Msg.t("bounty", s.bountyHearts()));
            Msg.send(sender, "stats.comebacks", Msg.t("eliminated", s.eliminations()), Msg.t("comebacks", s.comebacks()));
        }, tracker.mainThread());
    }

    private LiteralCommandNode<CommandSourceStack> streakCommand() {
        return Commands.literal("streak")
                .requires(src -> src.getSender().hasPermission("lifestealplus.streak"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (sender instanceof Player player) showStreak(sender, hearts.streak(player.getUniqueId()));
                    else Msg.send(sender, "common.players-only");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            plugin.getServer().getOnlinePlayers().forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String name = StringArgumentType.getString(ctx, "player");
                            showStreak(sender, hearts.findByName(name).thenCompose(found -> found.isEmpty()
                                    ? CompletableFuture.completedFuture(Optional.empty())
                                    : hearts.streak(found.get().uuid())), name);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void showStreak(CommandSender sender, CompletableFuture<Optional<PlayerStreak>> lookup) {
        showStreak(sender, lookup, sender.getName());
    }

    private void showStreak(CommandSender sender, CompletableFuture<Optional<PlayerStreak>> lookup, String name) {
        if (!plugin.settings().streaks().enabled()) {
            Msg.send(sender, "common.feature-off");
            return;
        }
        lookup.whenCompleteAsync((streak, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't read a kill streak", error);
                Msg.send(sender, "common.error");
            } else if (streak.isEmpty()) {
                Msg.send(sender, "common.no-such-player", Msg.t("name", name));
            } else {
                PlayerStreak s = streak.get();
                Msg.send(sender, "streak.status", Msg.t("player", s.name()), Msg.t("current", s.current()), Msg.t("best", s.best()));
            }
        }, tracker.mainThread());
    }
}
