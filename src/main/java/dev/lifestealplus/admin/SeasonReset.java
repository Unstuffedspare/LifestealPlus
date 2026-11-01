package dev.lifestealplus.admin;

import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.combat.CombatBodies;
import dev.lifestealplus.combat.NewbieShield;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.core.SeasonStanding;
import dev.lifestealplus.events.Finale;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Ends a season and starts the next. Asks for confirmation first, since it wipes
 * everyone's hearts; the old season's standings and ledger go to the archive.
 */
public final class SeasonReset {

    private static final String CURRENT = "season.name";
    private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);

    private record Pending(String nextName, Instant expires) {
    }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final NewbieShield shield;
    private final CombatBodies bodies;
    private final Finale finale;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public SeasonReset(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, NewbieShield shield,
                       CombatBodies bodies, Finale finale) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.shield = shield;
        this.bodies = bodies;
        this.finale = finale;
    }

    public CompletableFuture<String> currentName() {
        return hearts.state(CURRENT).thenApply(name -> name.orElse("Season 1"));
    }

    /** Step one: remember what the admin asked for, and tell them how to confirm. */
    public void request(CommandSender sender, String nextName) {
        currentName().whenCompleteAsync((current, error) -> {
            if (error != null) {
                fail(sender, error);
                return;
            }
            if (current.equalsIgnoreCase(nextName)) {
                sender.sendRichMessage("<red>The new season needs a different name from the current one (<current>).",
                        Placeholder.unparsed("current", current));
                return;
            }
            pending.put(sender.getName(), new Pending(nextName, Instant.now().plus(CONFIRM_WINDOW)));
            sender.sendRichMessage("""
                    <red><bold>This ends <current> for everyone.</bold></red> <gray>Its standings and ledger are archived, \
                    then every player's hearts, bounties, marks and Purgatory progress are wiped and <next> begins.</gray>
                    <yellow>Run <white>/lifesteal season confirm</white> within 30 seconds to go ahead.""",
                    Placeholder.unparsed("current", current),
                    Placeholder.unparsed("next", nextName));
        }, tracker.mainThread());
    }

    /** Step two. */
    public void confirm(CommandSender sender) {
        Pending request = pending.remove(sender.getName());
        if (request == null || Instant.now().isAfter(request.expires())) {
            sender.sendRichMessage("<gray>Nothing to confirm. Start with /lifesteal season reset <next season name>.");
            return;
        }
        if (finale.isActive()) finale.stop();
        // Nobody should be able to kill a logged-out body whose owner is about to be wiped.
        bodies.shutdown();
        // Chained, never blocking: these callbacks run on the database thread itself.
        currentName()
                .thenCompose(current -> hearts.archiveSeason(current)
                        .thenCompose(standings -> hearts.putState(CURRENT, request.nextName())
                                .thenApply(saved -> new Archived(current, standings))))
                .whenCompleteAsync((archived, error) -> {
                    if (error != null) {
                        fail(sender, error);
                        return;
                    }
                    announce(archived, request.nextName());
                    for (Player player : plugin.getServer().getOnlinePlayers()) restart(player);
                }, tracker.mainThread());
    }

    private record Archived(String season, List<SeasonStanding> standings) {
    }

    private void announce(Archived archived, String next) {
        String winner = archived.standings().isEmpty() ? null : archived.standings().getFirst().name();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.showTitle(Title.title(Component.text(next, NamedTextColor.GOLD),
                    Component.text(archived.season() + " is over", NamedTextColor.GRAY)));
        }
        plugin.getServer().broadcast(MiniMessage.miniMessage().deserialize(
                "<gold><bold><old> is over.</bold></gold> <yellow><winner></yellow> <gold>Welcome to <new>: everyone starts fresh.</gold> "
                        + "<gray>See the final standings with /leaderboard <old>",
                Placeholder.unparsed("old", archived.season()),
                Placeholder.unparsed("new", next),
                Placeholder.unparsed("winner", winner == null ? "" : winner + " finished on top.")));
    }

    /** Gives an online player their fresh start now, rather than at their next login. */
    private void restart(Player player) {
        hearts.touch(player.getUniqueId(), player.getName())
                .thenCombine(hearts.shieldLeft(player.getUniqueId()), Fresh::new)
                .whenCompleteAsync((fresh, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Couldn't restart " + player.getName() + " for the new season", error);
                        return;
                    }
                    if (!player.isOnline()) return;
                    tracker.joined(player, fresh.hearts());
                    shield.changed(player.getUniqueId(), fresh.shieldLeft());
                }, tracker.mainThread());
    }

    private record Fresh(PlayerHearts hearts, Duration shieldLeft) {
    }

    private void fail(CommandSender sender, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        if (cause instanceof IllegalArgumentException rule) {
            sender.sendRichMessage("<red><reason>", Placeholder.unparsed("reason", rule.getMessage()));
            return;
        }
        plugin.getLogger().log(Level.SEVERE, "Season reset failed", error);
        sender.sendRichMessage("<red>The season reset failed; the error is in the server log.");
    }
}
