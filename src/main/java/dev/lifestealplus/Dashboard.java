package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lifestealplus.core.EconomyReport;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.NewsItem;
import dev.lifestealplus.events.Airdrops;
import dev.lifestealplus.events.BloodMoon;
import dev.lifestealplus.events.Finale;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;

/**
 * /lifesteal dashboard: the server at a glance for admins. The season, who's in and out, how
 * many hearts exist and where this week's came from (the early warning for heart inflation),
 * what events are coming, and the latest eliminations.
 */
final class Dashboard {

    private static final Duration WINDOW = Duration.ofDays(7);
    private static final List<String> ELIMINATIONS = List.of(NewsItem.ELIMINATED, NewsItem.ELIMINATED_BY);

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final BloodMoon bloodMoon;
    private final Airdrops airdrops;
    private final Finale finale;

    Dashboard(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, BloodMoon bloodMoon, Airdrops airdrops,
              Finale finale) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.bloodMoon = bloodMoon;
        this.airdrops = airdrops;
        this.finale = finale;
    }

    LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("dashboard").executes(ctx -> {
            show(ctx.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        });
    }

    private record Snapshot(String season, Optional<String> started, EconomyReport economy, List<NewsItem> news) {
    }

    private void show(CommandSender sender) {
        hearts.state("season.name").thenCombine(hearts.state(CapSchedule.SEASON_STARTED), (name, started) -> List.of(
                        name.orElse("Season 1"), started.orElse("")))
                .thenCompose(season -> hearts.economy(WINDOW).thenCombine(hearts.latestNews(30), (economy, news) ->
                        new Snapshot(season.get(0), season.get(1).isEmpty() ? Optional.empty() : Optional.of(season.get(1)),
                                economy, news)))
                .whenCompleteAsync((snap, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Couldn't build the dashboard", error);
                        Msg.send(sender, "common.error");
                        return;
                    }
                    print(sender, snap);
                }, tracker.mainThread());
    }

    private void print(CommandSender to, Snapshot snap) {
        EconomyReport e = snap.economy();
        String week = snap.started().map(ms -> String.valueOf(
                Duration.between(Instant.ofEpochMilli(Long.parseLong(ms)), Instant.now()).toDays() / 7 + 1)).orElse("1");
        Msg.send(to, "dashboard.header");
        Msg.send(to, "dashboard.season", Msg.t("season", snap.season()), Msg.t("week", week),
                Msg.t("online", plugin.getServer().getOnlinePlayers().size()), Msg.t("alive", e.alive()),
                Msg.t("eliminated", e.eliminated()));
        Msg.send(to, "dashboard.hearts", Msg.t("hearts", e.heartsInPlay()),
                Msg.t("average", e.alive() == 0 ? "0" : String.format(Locale.ROOT, "%.1f", (double) e.heartsInPlay() / e.alive())),
                Msg.t("bounties", e.inBounties()));
        Msg.send(to, "dashboard.week", Msg.t("gained", sources(e.gained())), Msg.t("lost", sources(e.lost())),
                Msg.t("net", (e.net() > 0 ? "+" : "") + e.net()));
        Msg.send(to, "dashboard.events", Msg.t("blood-moon", bloodMoonStatus()), Msg.t("airdrop", airdropStatus()),
                Msg.t("finale", finale.isActive() ? Msg.raw("dashboard.switched-on") : Msg.raw("dashboard.switched-off")));
        List<String> recent = snap.news().stream().filter(n -> ELIMINATIONS.contains(n.kind())).limit(3)
                .map(n -> n.player() + " (" + Msg.duration(Duration.between(n.at(), Instant.now())) + ")").toList();
        Msg.send(to, "dashboard.recent", Msg.t("eliminations", recent.isEmpty() ? Msg.raw("dashboard.none") : String.join(", ", recent)));
    }

    /** "kills 12, crafting 3" in the order given. */
    private static String sources(Map<HeartCause, Integer> byCause) {
        if (byCause.isEmpty()) return Msg.raw("dashboard.none");
        return byCause.entrySet().stream()
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " " + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String bloodMoonStatus() {
        if (bloodMoon.isActive()) return Msg.raw("dashboard.happening");
        Instant next = bloodMoon.nextRising();
        return next == null ? Msg.raw("dashboard.switched-off") : Msg.duration(Duration.between(Instant.now(), next));
    }

    private String airdropStatus() {
        Instant next = airdrops.nextDrop();
        return next == null ? Msg.raw("dashboard.switched-off") : Msg.duration(Duration.between(Instant.now(), next));
    }
}
