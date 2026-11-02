package dev.lifestealplus.purgatory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.entity.Player;

/** /purgatory: your progress, and calling or joining a trial. */
public final class PurgatoryCommands {

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final TrialManager trials;

    public PurgatoryCommands(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, TrialManager trials) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.trials = trials;
    }

    public void register(Commands commands) {
        commands.register(node(), "See your way out of Purgatory, or call and join trials");
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("purgatory")
                .requires(src -> src.getSender().hasPermission("lifestealplus.purgatory"))
                .executes(ctx -> asPlayer(ctx.getSource(), this::status))
                .then(Commands.literal("start").executes(ctx -> asPlayer(ctx.getSource(), trials::call)))
                .then(Commands.literal("join").executes(ctx -> asPlayer(ctx.getSource(), trials::join)))
                .build();
    }

    private int asPlayer(CommandSourceStack source, Consumer<Player> action) {
        if (source.getSender() instanceof Player player) {
            action.accept(player);
        } else {
            Msg.send(source.getSender(), "common.players-only");
        }
        return Command.SINGLE_SUCCESS;
    }

    private void status(Player player) {
        if (!tracker.isEliminated(player)) {
            Msg.send(player, "purgatory.not-eliminated");
            return;
        }
        hearts.progress(player.getUniqueId()).whenCompleteAsync((progress, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't read Purgatory progress", error);
                Msg.send(player, "common.error");
                return;
            }
            Duration wait = trials.cooldownLeft(progress);
            Msg.send(player, "purgatory.status",
                    Msg.t("have", progress.fragments()),
                    Msg.t("needed", plugin.settings().purgatory().fragmentsNeeded()),
                    Msg.c("when", wait.isZero()
                            ? Msg.get("purgatory.next-win-counts")
                            : Msg.get("purgatory.next-fragment-in", Msg.t("wait", TrialManager.describe(wait)))),
                    Msg.c("trial", trials.status()));
        }, tracker.mainThread());
    }
}
