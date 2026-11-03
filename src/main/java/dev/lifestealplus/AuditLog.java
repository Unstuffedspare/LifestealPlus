package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lifestealplus.core.AuditEntry;
import dev.lifestealplus.core.HeartService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Every admin action, kept for good (season resets don't touch it): each /lifesteal command
 * that changes something, from a player, the console or RCON, plus changes made by clicking
 * in the settings menu. /lifesteal audit reads it back; /lifesteal export audit saves it as CSV.
 */
final class AuditLog implements Listener {

    private static final int PAGE = 10;
    /** /lifesteal subcommands that only look, so they'd only be noise. */
    private static final List<String> READ_ONLY = List.of("ledger", "dashboard", "features", "alts", "flags", "audit",
            "settings", "event status", "backup list",
            // The settings menu records these itself, with the old and new values.
            "config", "preset");

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;

    AuditLog(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
    }

    /** Records something done outside a command (a menu click). */
    void record(String actor, String action, String detail) {
        hearts.addAudit(actor, action, detail).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Couldn't record an admin action: " + action + " " + detail, error);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        command(event.getPlayer().getName(), event.getMessage().substring(1));
    }

    /** The console. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onServerCommand(ServerCommandEvent event) {
        command(event.getSender().getName(), event.getCommand().startsWith("/") ? event.getCommand().substring(1) : event.getCommand());
    }

    /** RCON, which Bukkit reports separately: listening for console commands doesn't hear it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onRemoteCommand(RemoteServerCommandEvent event) {
        onServerCommand(event);
    }

    private void command(String actor, String line) {
        String text = line.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        String rest;
        if (lower.startsWith("lifesteal ")) rest = lower.substring("lifesteal ".length());
        else if (lower.startsWith("lifestealplus:lifesteal ")) rest = lower.substring("lifestealplus:lifesteal ".length());
        else return;
        if (READ_ONLY.stream().anyMatch(rest::startsWith) || rest.equals("backup")) return;
        record(actor, "command", "/" + text);
    }

    LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("audit")
                .executes(ctx -> show(ctx.getSource().getSender(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> show(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "page"))));
    }

    private int show(CommandSender sender, int page) {
        var size = hearts.auditSize();
        var entries = hearts.audit(PAGE, (page - 1) * PAGE);
        CompletableFuture.allOf(size, entries)
                .whenCompleteAsync((done, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Couldn't read the audit log", error);
                        Msg.send(sender, "common.error");
                        return;
                    }
                    Msg.send(sender, "audit.header", Msg.t("page", page),
                            Msg.t("pages", Math.max(1, (size.join() + PAGE - 1) / PAGE)));
                    if (entries.join().isEmpty()) Msg.send(sender, "audit.empty");
                    for (AuditEntry entry : entries.join()) {
                        Msg.send(sender, "audit.line", Msg.t("ago", Msg.duration(Duration.between(entry.at(), Instant.now()))),
                                Msg.t("actor", entry.actor()), Msg.t("action", entry.action()), Msg.t("detail", entry.detail()));
                    }
                }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }
}
