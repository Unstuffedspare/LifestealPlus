package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.command.CommandSender;

/** /lifesteal features: every feature, on or off, named by its section in config.yml. */
final class FeaturesCommand {

    private final LifestealPlugin plugin;

    FeaturesCommand(LifestealPlugin plugin) {
        this.plugin = plugin;
    }

    LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("features").executes(ctx -> {
            show(ctx.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        });
    }

    private void show(CommandSender sender) {
        PluginSettings s = plugin.settings();
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("repeat-kills", !s.rules().repeatKillWindow().isZero());
        features.put("vengeance", !s.rules().vengeanceWindow().isZero());
        features.put("vengeance.tracker", s.extras().vengeanceTracker());
        features.put("bounties", s.bountiesEnabled());
        features.put("newbie-shield", s.extras().shieldsEnabled());
        features.put("combat-log", !s.combat().tag().isZero());
        features.put("last-stand", s.lastStandHearts() > 0);
        features.put("most-wanted", !s.mostWanted().interval().isZero());
        features.put("hearts.max-by-week", !s.social().capSchedule().isEmpty());
        features.put("purgatory", s.purgatory().enabled());
        features.put("rifts", s.social().riftsEnabled());
        features.put("revival-votes", s.social().pledgesEnabled());
        features.put("blood-pacts", s.social().pactsEnabled());
        features.put("ghosts", s.social().ghostsEnabled());
        features.put("ghosts.whispers", s.social().whispersEnabled());
        features.put("ghosts.limit-chat", s.social().limitGhostChat());
        features.put("heart-items.withdraw", s.social().withdrawEnabled());
        features.put("heart-items.decay", s.social().decayDays() > 0);
        features.put("blood-moon", !s.events().bloodMoon().every().isZero());
        features.put("airdrops", !s.events().airdrops().every().isZero());
        features.put("alt-detection", s.extras().altDetection());
        features.put("tab-list", s.extras().tabList());
        features.put("seasons.public-history", s.extras().publicSeasons());

        Msg.send(sender, "features.header");
        features.forEach((name, on) -> Msg.send(sender, on ? "features.switched-on" : "features.switched-off", Msg.t("name", name)));
        Msg.send(sender, "features.footer");
    }
}
