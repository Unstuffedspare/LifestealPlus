package dev.lifestealplus;

import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.KillOutcome;
import java.time.Duration;
import java.util.List;
import dev.lifestealplus.items.HeartItems;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

/**
 * What a death costs, and who hears about it. Shared by real player deaths and by
 * combat-loggers' bodies, so both follow exactly the same heart rules.
 */
public final class KillHandler {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final HeartItems heartItems;

    KillHandler(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, HeartItems heartItems) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.heartItems = heartItems;
    }

    public void pvpKill(UUID victim, UUID killer) {
        var kill = hearts.kill(victim, killer);
        tracker.track(kill.thenApply(KillOutcome::changes));
        kill.thenAcceptAsync(this::tellAboutKill, tracker.mainThread());
    }

    /** A death with no player to blame. {@code note} goes in the ledger (the damage type, or "body"). */
    /** @param where where they died, which is where any hearts they lose drop */
    public void naturalDeath(UUID victim, String note, Location where) {
        int loss = plugin.settings().naturalDeathLoss();
        if (loss <= 0) {
            // Dying still ends a kill streak, even when it costs nothing.
            hearts.endStreak(victim);
            return;
        }
        tracker.track(hearts.apply(List.of(HeartRequest.add(victim, -loss, HeartCause.DEATH, "server").withNote(note))))
                .thenAcceptAsync(changes -> dropLostHearts(changes.getFirst(), where.clone()), tracker.mainThread());
    }

    /** Hearts lost to the world land where their owner died, glowing, and safe from lava and despawning. */
    private void dropLostHearts(HeartChange change, Location where) {
        int lost = -change.delta();
        World world = where.getWorld();
        if (lost <= 0 || world == null || !plugin.settings().extras().naturalDeathDrops()) return;
        if (where.getY() < world.getMinHeight()) {
            // Fell into the void: put them on the ground above, or at the world's spawn if there's none.
            Block top = world.getHighestBlockAt(where);
            where = top.getType().isAir() ? world.getSpawnLocation() : top.getLocation().add(0.5, 1, 0.5);
        }
        Item item = world.dropItemNaturally(where, heartItems.heart(lost));
        item.setInvulnerable(true);
        item.setUnlimitedLifetime(true);
        item.setGlowing(true);
        Player owner = plugin.getServer().getPlayer(change.player());
        if (owner != null) {
            Msg.send(owner, "items.hearts-dropped", Msg.t("n", lost),
                    Msg.t("x", where.getBlockX()), Msg.t("y", where.getBlockY()), Msg.t("z", where.getBlockZ()));
        }
    }

    /** Anything about a kill beyond the plain heart transfer gets said out loud; the server hears vengeance and bounties. */
    private void tellAboutKill(KillOutcome outcome) {
        String killerName = outcome.killer().name(), victimName = outcome.victim().name();
        if (outcome.repeat()) explainRepeatKill(outcome);
        if (outcome.linked() && plugin.settings().extras().altDetection()) {
            for (Player admin : plugin.getServer().getOnlinePlayers()) {
                if (!admin.hasPermission("lifestealplus.admin")) continue;
                Msg.send(admin, "alts.linked-kill", Msg.t("killer", killerName), Msg.t("victim", victimName));
            }
            plugin.getLogger().info("Linked accounts: " + killerName + " killed " + victimName);
        }
        if (outcome.shielded()) {
            Player killer = plugin.getServer().getPlayer(outcome.killer().player());
            Msg.send(killer, "shield.kill-blocked");
        }
        if (outcome.vengeance()) {
            Msg.broadcast("vengeance.taken", Msg.t("killer", killerName), Msg.t("victim", victimName),
                    Msg.t("hearts", outcome.killer().delta()));
        }
        if (outcome.marked()) {
            String window = formatWindow(hearts.rules().vengeanceWindow());
            int back = hearts.rules().heartsPerKill() + hearts.rules().vengeanceBonus();
            Player avenger = plugin.getServer().getPlayer(outcome.victim().player());
            if (avenger != null) {
                Msg.send(avenger, "vengeance.you-marked", Msg.t("killer", killerName), Msg.t("window", window), Msg.t("back", back));
            }
            Player marked = plugin.getServer().getPlayer(outcome.killer().player());
            if (marked != null) {
                Msg.send(marked, "vengeance.you-are-marked", Msg.t("victim", victimName), Msg.t("window", window));
            }
        }
        tellAboutStreaks(outcome);
        if (outcome.bounty() != null && outcome.bounty().change().delta() > 0) {
            Msg.broadcast("bounty.claimed", Msg.t("killer", killerName), Msg.t("victim", victimName),
                    Msg.t("hearts", outcome.bounty().change().delta()));
        }
        if (outcome.bounty() != null && outcome.bounty().remaining() > 0) {
            Msg.broadcast("bounty.left-over", Msg.t("left", outcome.bounty().remaining()), Msg.t("victim", victimName),
                    Msg.t("killer", killerName));
        }
    }

    /** Without this, a kill that pays nothing looks like a bug to both players. */
    private void tellAboutStreaks(KillOutcome outcome) {
        String killerName = outcome.killer().name(), victimName = outcome.victim().name();
        if (outcome.endedStreak() > 0) {
            Msg.broadcast(outcome.shutdown() ? "streak.shutdown" : "streak.ended", Msg.t("killer", killerName),
                    Msg.t("victim", victimName), Msg.t("n", outcome.endedStreak()),
                    Msg.t("bonus", plugin.settings().streaks().shutdownBonus()));
        }
        boolean grew = !outcome.repeat() && !outcome.shielded() && outcome.victim().delta() < 0;
        if (grew && plugin.settings().streaks().announceAt().contains(outcome.killerStreak())) {
            Msg.broadcast("streak.milestone", Msg.t("player", killerName), Msg.t("n", outcome.killerStreak()));
        }
    }

    private void explainRepeatKill(KillOutcome outcome) {
        String window = formatWindow(hearts.rules().repeatKillWindow());
        Player killer = plugin.getServer().getPlayer(outcome.killer().player());
        if (killer != null) {
            Msg.send(killer, "repeat-kill.killer", Msg.t("victim", outcome.victim().name()), Msg.t("window", window));
        }
        Player victim = plugin.getServer().getPlayer(outcome.victim().player());
        if (victim != null) {
            Msg.send(victim, "repeat-kill.victim", Msg.t("killer", outcome.killer().name()), Msg.t("window", window));
        }
    }

    static String formatWindow(Duration window) {
        return Msg.duration(window);
    }
}
