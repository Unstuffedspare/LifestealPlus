package dev.lifestealplus;

import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.KillOutcome;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * What a death costs, and who hears about it. Shared by real player deaths and by
 * combat-loggers' bodies, so both follow exactly the same heart rules.
 */
public final class KillHandler {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;

    KillHandler(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
    }

    public void pvpKill(UUID victim, UUID killer) {
        var kill = hearts.kill(victim, killer);
        tracker.track(kill.thenApply(KillOutcome::changes));
        kill.thenAcceptAsync(this::tellAboutKill, tracker.mainThread());
    }

    /** A death with no player to blame. {@code note} goes in the ledger (the damage type, or "body"). */
    public void naturalDeath(UUID victim, String note) {
        int loss = plugin.settings().naturalDeathLoss();
        if (loss <= 0) {
            // Dying still ends a kill streak, even when it costs nothing.
            hearts.endStreak(victim);
            return;
        }
        tracker.track(hearts.apply(List.of(HeartRequest.add(victim, -loss, HeartCause.DEATH, "server").withNote(note))));
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
