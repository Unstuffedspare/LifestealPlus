package dev.lifestealplus.purgatory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.core.HeartService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
            source.getSender().sendRichMessage("<red>Only players can use this.");
        }
        return Command.SINGLE_SUCCESS;
    }

    private void status(Player player) {
        if (!tracker.isEliminated(player)) {
            player.sendRichMessage("<gray>Purgatory is where eliminated players go to earn their way back. You're not eliminated.");
            return;
        }
        hearts.progress(player.getUniqueId()).whenCompleteAsync((progress, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't read Purgatory progress", error);
                player.sendRichMessage("<red>Couldn't read your progress; the error is in the server log.");
                return;
            }
            Duration wait = trials.cooldownLeft(progress);
            player.sendRichMessage("<dark_aqua>Fragments: <white><have>/<needed></white>. <gray><when>\n<gray><trial>",
                    Placeholder.unparsed("have", String.valueOf(progress.fragments())),
                    Placeholder.unparsed("needed", String.valueOf(plugin.settings().purgatory().fragmentsNeeded())),
                    Placeholder.unparsed("when", wait.isZero()
                            ? "Your next win earns a fragment."
                            : "Your next fragment can be earned in " + TrialManager.describe(wait) + "."),
                    Placeholder.unparsed("trial", trials.status()));
        }, tracker.mainThread());
    }
}
