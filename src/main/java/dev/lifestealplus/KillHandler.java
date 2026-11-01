package dev.lifestealplus;

import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.KillOutcome;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
        if (loss <= 0) return;
        tracker.track(hearts.apply(List.of(HeartRequest.add(victim, -loss, HeartCause.DEATH, "server").withNote(note))));
    }

    /** Anything about a kill beyond the plain heart transfer gets said out loud; the server hears vengeance and bounties. */
    private void tellAboutKill(KillOutcome outcome) {
        String killerName = outcome.killer().name(), victimName = outcome.victim().name();
        if (outcome.repeat()) explainRepeatKill(outcome);
        if (outcome.shielded()) {
            Player killer = plugin.getServer().getPlayer(outcome.killer().player());
            if (killer != null) killer.sendRichMessage("<aqua>🛡 No hearts moved: one of you still has a new-player shield.");
        }
        if (outcome.vengeance()) {
            broadcast("<red>⚔ <killer> took vengeance on <victim>, taking back <hearts> hearts.",
                    Placeholder.unparsed("killer", killerName),
                    Placeholder.unparsed("victim", victimName),
                    Placeholder.unparsed("hearts", String.valueOf(outcome.killer().delta())));
        }
        if (outcome.marked()) {
            String window = formatWindow(hearts.rules().vengeanceWindow());
            int back = hearts.rules().heartsPerKill() + hearts.rules().vengeanceBonus();
            Player avenger = plugin.getServer().getPlayer(outcome.victim().player());
            if (avenger != null) {
                avenger.sendRichMessage("<red>You've marked <killer> for vengeance.</red> <gray>Kill them within <window> "
                                + "to take back <back> hearts.",
                        Placeholder.unparsed("killer", killerName),
                        Placeholder.unparsed("window", window),
                        Placeholder.unparsed("back", String.valueOf(back)));
            }
            Player marked = plugin.getServer().getPlayer(outcome.killer().player());
            if (marked != null) {
                marked.sendRichMessage("<red><victim> has marked you for vengeance</red> <gray>for the next <window>.",
                        Placeholder.unparsed("victim", victimName),
                        Placeholder.unparsed("window", window));
            }
        }
        if (outcome.bounty() != null && outcome.bounty().change().delta() > 0) {
            broadcast("<gold>💰 <killer> claimed the bounty on <victim>: <hearts> hearts.",
                    Placeholder.unparsed("killer", killerName),
                    Placeholder.unparsed("victim", victimName),
                    Placeholder.unparsed("hearts", String.valueOf(outcome.bounty().change().delta())));
        }
        if (outcome.bounty() != null && outcome.bounty().remaining() > 0) {
            broadcast("<gold><left> hearts of bounty are still on <victim> <gray>(<killer> couldn't carry more).",
                    Placeholder.unparsed("left", String.valueOf(outcome.bounty().remaining())),
                    Placeholder.unparsed("victim", victimName),
                    Placeholder.unparsed("killer", killerName));
        }
    }

    private void broadcast(String message, TagResolver... tags) {
        plugin.getServer().broadcast(MiniMessage.miniMessage().deserialize(message, tags));
    }

    /** Without this, a kill that pays nothing looks like a bug to both players. */
    private void explainRepeatKill(KillOutcome outcome) {
        String window = formatWindow(hearts.rules().repeatKillWindow());
        Player killer = plugin.getServer().getPlayer(outcome.killer().player());
        if (killer != null) {
            killer.sendRichMessage("<gray>No heart from <victim>: you already took one from them in the last <window>.",
                    Placeholder.unparsed("victim", outcome.victim().name()),
                    Placeholder.unparsed("window", window));
        }
        Player victim = plugin.getServer().getPlayer(outcome.victim().player());
        if (victim != null) {
            victim.sendRichMessage("<gray>You kept your heart: <killer> already took one from you in the last <window>.",
                    Placeholder.unparsed("killer", outcome.killer().name()),
                    Placeholder.unparsed("window", window));
        }
    }

    static String formatWindow(Duration window) {
        long minutes = window.toMinutes();
        if (minutes % 60 == 0) return (minutes / 60) + (minutes == 60 ? " hour" : " hours");
        return minutes + " minutes";
    }
}
