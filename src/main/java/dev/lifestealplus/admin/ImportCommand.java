package dev.lifestealplus.admin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.LifestealZImport;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * /lifesteal import lifestealz [file], then /lifesteal import confirm [unban]: brings a
 * server's players over from LifeStealZ. The first step only reads and shows what will
 * happen; nothing changes until the confirm.
 */
public final class ImportCommand {

    private static final String SOURCE = "LifeStealZ";
    private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(60);

    private record Pending(List<LifestealZImport.Planned> plan, Instant expires) {
    }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ImportCommand(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
    }

    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("import")
                .then(Commands.literal("lifestealz")
                        .executes(ctx -> preview(ctx.getSource().getSender(), null))
                        .then(Commands.argument("file", StringArgumentType.word())
                                .executes(ctx -> preview(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "file")))))
                .then(Commands.literal("confirm")
                        .executes(ctx -> confirm(ctx.getSource().getSender(), false))
                        .then(Commands.literal("unban").executes(ctx -> confirm(ctx.getSource().getSender(), true))));
    }

    private Path lifestealzFolder() {
        return plugin.getDataFolder().getParentFile().toPath().resolve("LifeStealZ").normalize();
    }

    private boolean lifestealzRunning() {
        Plugin other = plugin.getServer().getPluginManager().getPlugin("LifeStealZ");
        return other != null && other.isEnabled();
    }

    /** Reads the data and shows what an import would do. Changes nothing. */
    private int preview(CommandSender sender, String fileName) {
        Path folder = lifestealzFolder();
        // A bare name is a CSV from /lifestealz data export; with no name, LifeStealZ's own SQLite database.
        String name = fileName == null ? "userData.db" : fileName.contains(".") ? fileName : fileName + ".csv";
        Path file = folder.resolve(name).normalize();
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
            Msg.send(sender, "import.not-found", Msg.t("file", folder.relativize(file.startsWith(folder) ? file : folder.resolve(name))));
            return Command.SINGLE_SUCCESS;
        }
        int minHearts = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).getInt("minHearts", 0);
        Msg.send(sender, "import.reading", Msg.t("file", "plugins/LifeStealZ/" + name));

        CompletableFuture.supplyAsync(() -> {
            try {
                return LifestealZImport.read(file);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).thenCompose(source -> {
            List<LifestealZImport.Planned> plan = LifestealZImport.plan(source.rows(), minHearts, hearts.rules());
            return hearts.countKnown(plan.stream().map(LifestealZImport.Planned::uuid).toList())
                    .thenApply(known -> new Preview(plan, source.skipped(), known));
        }).whenCompleteAsync((preview, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't read LifeStealZ data from " + file, error);
                Msg.send(sender, "import.unreadable");
                return;
            }
            showPreview(sender, preview);
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    private record Preview(List<LifestealZImport.Planned> plan, int skipped, int known) {
    }

    private void showPreview(CommandSender sender, Preview preview) {
        List<LifestealZImport.Planned> plan = preview.plan();
        if (plan.isEmpty()) {
            Msg.send(sender, "import.empty");
            return;
        }
        long eliminated = plan.stream().filter(LifestealZImport.Planned::eliminated).count();
        List<String> capped = plan.stream().filter(LifestealZImport.Planned::capped).map(LifestealZImport.Planned::name).toList();
        long banned = plan.stream().filter(p -> p.eliminated() && plugin.getServer().getOfflinePlayer(p.uuid()).isBanned()).count();

        Msg.send(sender, "import.preview", Msg.t("total", plan.size()), Msg.t("alive", plan.size() - eliminated),
                Msg.t("eliminated", eliminated), Msg.t("known", preview.known()));
        if (preview.skipped() > 0) Msg.send(sender, "import.skipped", Msg.t("n", preview.skipped()));
        if (!capped.isEmpty()) {
            Msg.send(sender, "import.capped", Msg.t("n", capped.size()), Msg.t("cap", hearts.rules().maxHearts()),
                    Msg.t("names", String.join(", ", capped.subList(0, Math.min(8, capped.size()))) + (capped.size() > 8 ? ", ..." : "")));
        }
        if (banned > 0) Msg.send(sender, "import.banned", Msg.t("n", banned));
        if (lifestealzRunning()) {
            Msg.send(sender, "import.lifestealz-running");
            return;
        }
        pending.put(sender.getName(), new Pending(plan, Instant.now().plus(CONFIRM_WINDOW)));
        Msg.send(sender, "import.confirm-hint");
    }

    private int confirm(CommandSender sender, boolean unban) {
        Pending request = pending.remove(sender.getName());
        if (request == null || Instant.now().isAfter(request.expires())) {
            Msg.send(sender, "import.nothing-to-confirm");
            return Command.SINGLE_SUCCESS;
        }
        if (lifestealzRunning()) {
            Msg.send(sender, "import.lifestealz-running");
            return Command.SINGLE_SUCCESS;
        }
        hearts.importPlayers(request.plan(), SOURCE).whenCompleteAsync((result, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "LifeStealZ import failed", error);
                Msg.send(sender, "import.failed");
                return;
            }
            int unbanned = unban ? unbanEliminated(request.plan()) : 0;
            // Anyone online gets their imported hearts (or Purgatory) straight away.
            for (LifestealZImport.Planned p : request.plan()) {
                Player online = plugin.getServer().getPlayer(p.uuid());
                if (online == null) continue;
                hearts.find(p.uuid()).thenAcceptAsync(found -> found.ifPresent(h -> tracker.joined(online, h)), tracker.mainThread());
            }
            plugin.getLogger().info("Imported " + request.plan().size() + " players from LifeStealZ (" + result.created()
                    + " new, " + result.updated() + " updated, " + unbanned + " unbanned).");
            Msg.send(sender, "import.done", Msg.t("total", request.plan().size()), Msg.t("created", result.created()),
                    Msg.t("updated", result.updated()));
            if (unban) Msg.send(sender, "import.unbanned", Msg.t("n", unbanned));
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    /** LifeStealZ bans eliminated players by default; banned players could never reach Purgatory. */
    private int unbanEliminated(List<LifestealZImport.Planned> plan) {
        ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
        int unbanned = 0;
        for (LifestealZImport.Planned p : plan) {
            if (!p.eliminated()) continue;
            OfflinePlayer player = plugin.getServer().getOfflinePlayer(p.uuid());
            if (player.isBanned()) {
                bans.pardon(player.getPlayerProfile());
                unbanned++;
            }
        }
        return unbanned;
    }
}
