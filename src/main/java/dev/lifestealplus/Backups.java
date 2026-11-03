package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.command.CommandSender;

/**
 * Copies of the heart database: made automatically (every backups.every-hours, and before every
 * season reset) or on demand, with the oldest pruned past backups.keep. From one, an admin can
 * put a single player's hearts back, or the whole database at the next start. The ledger and
 * the audit log can also be exported as CSV for a spreadsheet.
 */
final class Backups {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    /** Names the backup to swap in at the next start. */
    private static final String RESTORE_MARKER = "restore-on-start.txt";

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final Path folder;
    private final Path exports;

    Backups(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.folder = plugin.getDataFolder().toPath().resolve("backups");
        this.exports = plugin.getDataFolder().toPath().resolve("exports");
        // Checked every 10 minutes; a backup is made once the last one is older than every-hours.
        plugin.tasks().timer(this::maybeBackUp, 20 * 60, 20 * 60 * 10);
    }

    /**
     * Before the database opens: if an admin asked for a restore, the current database is set
     * aside in backups/ and the chosen backup takes its place.
     */
    static void restorePending(Path dataFolder, Path database, Logger log) {
        Path marker = dataFolder.resolve(RESTORE_MARKER);
        if (!Files.exists(marker)) return;
        try {
            String name = Files.readString(marker).strip();
            Path backup = dataFolder.resolve("backups").resolve(name).normalize();
            if (!backup.startsWith(dataFolder.resolve("backups")) || !Files.isRegularFile(backup)) {
                log.warning("Asked to restore " + name + ", but there's no such backup; starting with the current database.");
            } else {
                if (Files.exists(database)) {
                    Files.copy(database, dataFolder.resolve("backups").resolve("before-restore-" + LocalDateTime.now().format(STAMP) + ".db"));
                }
                Files.deleteIfExists(Path.of(database + "-wal"));
                Files.deleteIfExists(Path.of(database + "-shm"));
                Files.copy(backup, database, StandardCopyOption.REPLACE_EXISTING);
                log.info("Restored the heart database from backups/" + name + ". The database it replaced is in backups/ too.");
            }
            Files.delete(marker);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Couldn't restore the database from a backup; starting with the current one", e);
        }
    }

    private PluginSettings.BackupSettings settings() {
        return plugin.settings().backups();
    }

    private void maybeBackUp() {
        if (settings().every().isZero() || !hearts.isSqlite()) return;
        Path newest = list().stream().filter(p -> !p.getFileName().toString().startsWith("before-")).findFirst().orElse(null);
        try {
            if (newest != null && Files.getLastModifiedTime(newest).toInstant().plus(settings().every()).isAfter(java.time.Instant.now())) return;
        } catch (IOException ignored) {
            // Can't tell how old it is: make a new one.
        }
        backUp("auto", null);
    }

    /** Makes a backup now; {@code reason} goes in its name. Tells {@code who}, if anyone asked. */
    java.util.concurrent.CompletableFuture<Path> backUp(String reason, CommandSender who) {
        if (!hearts.isSqlite()) {
            // A MySQL database is backed up with its own tools; nothing to do (and nothing to wait for).
            if (who != null) Msg.send(who, "backup.mysql");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        Path file = folder.resolve("lifesteal-" + LocalDateTime.now().format(STAMP) + "-" + reason + ".db");
        return hearts.backUpTo(file).whenCompleteAsync((ok, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't back up the heart database", error);
                if (who != null) Msg.send(who, "common.error");
                return;
            }
            prune();
            if (who != null) Msg.send(who, "backup.made", Msg.t("file", file.getFileName().toString()));
        }, tracker.mainThread()).thenApply(ok -> file);
    }

    /** Newest first. */
    List<Path> list() {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void prune() {
        int keep = settings().keep();
        List<Path> made = list().stream().filter(p -> p.getFileName().toString().startsWith("lifesteal-")).toList();
        for (Path old : made.subList(Math.min(keep, made.size()), made.size())) {
            try {
                Files.deleteIfExists(old);
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't delete old backup " + old.getFileName() + ": " + e.getMessage());
            }
        }
    }

    private Path resolve(String name) {
        Path file = folder.resolve(name).normalize();
        return file.startsWith(folder) && Files.isRegularFile(file) ? file : null;
    }

    LiteralArgumentBuilder<CommandSourceStack> backupNode() {
        return Commands.literal("backup")
                .executes(ctx -> showList(ctx.getSource().getSender()))
                .then(Commands.literal("list").executes(ctx -> showList(ctx.getSource().getSender())))
                .then(Commands.literal("now").executes(ctx -> {
                    backUp("manual", ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("restore")
                        .then(Commands.argument("file", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    list().forEach(p -> builder.suggest(p.getFileName().toString()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> stageRestore(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "file")))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> restorePlayer(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "file"), StringArgumentType.getString(ctx, "player"))))));
    }

    private int showList(CommandSender sender) {
        List<Path> files = list();
        if (files.isEmpty()) {
            Msg.send(sender, "backup.none");
            return Command.SINGLE_SUCCESS;
        }
        Msg.send(sender, "backup.header", Msg.t("n", files.size()));
        files.stream().limit(15).forEach(p -> Msg.send(sender, "backup.line", Msg.t("file", p.getFileName().toString())));
        return Command.SINGLE_SUCCESS;
    }

    /** The whole database, at the next start: swapping it while the server runs could lose what's in flight. */
    private int stageRestore(CommandSender sender, String name) {
        Path file = resolve(name);
        if (file == null) {
            Msg.send(sender, "backup.unknown", Msg.t("file", name));
            return 0;
        }
        try {
            Files.writeString(plugin.getDataFolder().toPath().resolve(RESTORE_MARKER), name);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't stage a restore", e);
            Msg.send(sender, "common.error");
            return 0;
        }
        Msg.send(sender, "backup.restore-staged", Msg.t("file", name));
        return Command.SINGLE_SUCCESS;
    }

    /** One player's hearts as they were in the backup, put back now and recorded in their ledger. */
    private int restorePlayer(CommandSender sender, String name, String player) {
        Path file = resolve(name);
        if (file == null) {
            Msg.send(sender, "backup.unknown", Msg.t("file", name));
            return 0;
        }
        hearts.heartsInBackup(file, player).thenCompose(found -> {
            if (found.isEmpty()) return java.util.concurrent.CompletableFuture.completedFuture(null);
            return tracker.track(hearts.apply(List.of(HeartRequest.set(found.get().uuid(), found.get().hearts(),
                    HeartCause.RESTORE, sender.getName()).withNote("from " + name)))).thenApply(List::getFirst);
        }).whenCompleteAsync((change, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't restore " + player + " from " + name, error);
                Msg.send(sender, "common.error");
            } else if (change == null) {
                Msg.send(sender, "backup.player-not-in", Msg.t("player", player), Msg.t("file", name));
            } else {
                Msg.send(sender, "backup.player-restored", Msg.t("player", change.name()), Msg.t("before", change.before()),
                        Msg.t("after", change.after()), Msg.t("file", name));
            }
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    /** /lifesteal export ledger [player] | audit: CSV files in plugins/LifestealPlus/exports. */
    LiteralArgumentBuilder<CommandSourceStack> exportNode() {
        return Commands.literal("export")
                .then(Commands.literal("ledger")
                        .executes(ctx -> export(ctx.getSource().getSender(), "ledger", null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> export(ctx.getSource().getSender(), "ledger", StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("audit").executes(ctx -> export(ctx.getSource().getSender(), "audit", null)));
    }

    private int export(CommandSender sender, String what, String player) {
        Path file = exports.resolve(what + (player == null ? "" : "-" + player) + "-" + LocalDateTime.now().format(STAMP) + ".csv");
        (what.equals("audit") ? hearts.exportAudit(file) : hearts.exportLedger(file, player)).whenCompleteAsync((rows, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't export the " + what, error);
                Msg.send(sender, "common.error");
                return;
            }
            Msg.send(sender, "backup.exported", Msg.t("n", rows), Msg.t("file", "exports/" + file.getFileName()));
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }
}
