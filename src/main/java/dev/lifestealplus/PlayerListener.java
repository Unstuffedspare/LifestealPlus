package dev.lifestealplus;

import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.KillOutcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

final class PlayerListener implements Listener {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;

    PlayerListener(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
    }

    /**
     * Makes sure the player has a heart record before they're in the world. If the
     * database can't answer, keep them out rather than let them play untracked.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            hearts.touch(event.getUniqueId(), event.getName()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't load hearts for " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Lifesteal data is unavailable right now. Try again in a minute."));
        }
    }

    /** Reads hearts fresh rather than reusing the pre-login value, in case an admin changed them in between. */
    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        hearts.find(player.getUniqueId()).whenCompleteAsync((found, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't load hearts for " + player.getName(), error);
            } else if (found.isPresent() && player.isOnline()) {
                tracker.joined(player, found.get());
            }
        }, tracker.mainThread());
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        tracker.left(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Integer current = tracker.cachedHearts(victim.getUniqueId());
        if (current != null && current <= 0) return;

        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            CompletableFuture<KillOutcome> kill = hearts.kill(victim.getUniqueId(), killer.getUniqueId());
            tracker.track(kill.thenApply(KillOutcome::changes));
            kill.thenAcceptAsync(this::tellAboutKill, tracker.mainThread());
            return;
        }
        int loss = plugin.settings().naturalDeathLoss();
        if (loss > 0) {
            String cause = event.getDamageSource().getDamageType().getTranslationKey();
            tracker.track(hearts.apply(List.of(
                    HeartRequest.add(victim.getUniqueId(), -loss, HeartCause.DEATH, "server").withNote(cause))));
        }
    }

    /** Anything about a kill beyond the plain heart transfer gets said out loud; the server hears vengeance and bounties. */
    private void tellAboutKill(KillOutcome outcome) {
        String killerName = outcome.killer().name(), victimName = outcome.victim().name();
        if (outcome.repeat()) explainRepeatKill(outcome);
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

    private static String formatWindow(Duration window) {
        long minutes = window.toMinutes();
        if (minutes % 60 == 0) return (minutes / 60) + (minutes == 60 ? " hour" : " hours");
        return minutes + " minutes";
    }

    @EventHandler
    void onRespawn(PlayerRespawnEvent event) {
        tracker.respawning(event);
    }
}
