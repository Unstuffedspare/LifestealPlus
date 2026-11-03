package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.bukkit.command.CommandSender;

/**
 * /lifesteal (or /lifesteal help): the plugin's commands, for everyone. Each person sees only
 * what they can use and what's switched on; admins also see their part of /lifesteal.
 */
final class HelpCommand {

    /** One line of help: how to type it, its description in messages.yml, and who sees it. */
    private record Entry(String usage, String message, String permission, BooleanSupplier on) {
    }

    private final LifestealPlugin plugin;
    private final List<Entry> players;
    private final List<Entry> admins;

    HelpCommand(LifestealPlugin plugin, boolean purgatory) {
        this.plugin = plugin;
        BooleanSupplier always = () -> true;
        this.players = List.of(
                new Entry("/hearts [player]", "help.hearts", "lifestealplus.hearts", always),
                new Entry("/stats [player] [season]", "help.stats", "lifestealplus.stats", always),
                new Entry("/leaderboard [season]", "help.leaderboard", "lifestealplus.leaderboard", always),
                new Entry("/news", "help.news", "lifestealplus.news", () -> plugin.settings().recap().enabled()),
                new Entry("/streak [player]", "help.streak", "lifestealplus.streak", () -> plugin.settings().streaks().enabled()),
                new Entry("/bounty", "help.bounty", "lifestealplus.bounty", () -> plugin.settings().bountiesEnabled()),
                new Entry("/vengeance", "help.vengeance", "lifestealplus.vengeance",
                        () -> !plugin.settings().rules().vengeanceWindow().isZero()),
                new Entry("/shield", "help.shield", "lifestealplus.shield", () -> plugin.settings().extras().shieldsEnabled()),
                new Entry("/withdraw [hearts]", "help.withdraw", "lifestealplus.withdraw", () -> plugin.settings().social().withdrawEnabled()),
                new Entry("/giveheart <player> <hearts>", "help.giveheart", "lifestealplus.giveheart",
                        () -> plugin.settings().economy().trading()),
                new Entry("/heartshop", "help.heartshop", "lifestealplus.heartshop", () -> plugin.settings().economy().shop().enabled()),
                new Entry("/pact <player>", "help.pact", "lifestealplus.pact", () -> plugin.settings().social().pactsEnabled()),
                new Entry("/rift <player>", "help.rift", "lifestealplus.rift", () -> plugin.settings().social().riftsEnabled()),
                new Entry("/pledge <player> <hearts>", "help.pledge", "lifestealplus.pledge", () -> plugin.settings().social().pledgesEnabled()),
                new Entry("/purgatory", "help.purgatory", "lifestealplus.purgatory", () -> purgatory),
                new Entry("/ghost", "help.ghost", "lifestealplus.ghost", () -> plugin.settings().social().ghostsEnabled()),
                new Entry("/whisper <player> <message>", "help.whisper", "lifestealplus.whisper",
                        () -> plugin.settings().social().whispersEnabled()));
        this.admins = List.of(
                new Entry("/lifesteal settings", "help.settings", AdminPermissions.SETTINGS, always),
                new Entry("/lifesteal dashboard", "help.dashboard", AdminPermissions.RECORDS, always),
                new Entry("/lifesteal give|take|set <player> <hearts>", "help.change-hearts", AdminPermissions.HEARTS, always),
                new Entry("/lifesteal revive <player>", "help.revive", AdminPermissions.HEARTS, always),
                new Entry("/lifesteal ledger <player>", "help.ledger", AdminPermissions.HEARTS, always),
                new Entry("/lifesteal event status", "help.events", AdminPermissions.EVENTS, always),
                new Entry("/lifesteal season", "help.season", AdminPermissions.SEASON, always),
                new Entry("/lifesteal audit", "help.audit", AdminPermissions.RECORDS, always),
                new Entry("/lifesteal backup", "help.backup", AdminPermissions.RECORDS, always),
                new Entry("/lifesteal reload", "help.reload", AdminPermissions.SETTINGS, always));
    }

    LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("help").executes(ctx -> show(ctx.getSource().getSender()));
    }

    int show(CommandSender sender) {
        Msg.send(sender, "help.header");
        print(sender, players);
        if (AdminPermissions.any(sender)) {
            Msg.send(sender, "help.admin-header");
            print(sender, admins);
        }
        return Command.SINGLE_SUCCESS;
    }

    private void print(CommandSender sender, List<Entry> entries) {
        for (Entry entry : entries) {
            if (!sender.hasPermission(entry.permission()) || !entry.on().getAsBoolean()) continue;
            String typed = entry.usage().contains(" ") ? entry.usage().substring(0, entry.usage().indexOf(' ') + 1) : entry.usage();
            Msg.send(sender, "help.line", Msg.t("command", entry.usage()), Msg.t("typed", typed), Msg.c("description", Msg.get(entry.message())));
        }
    }
}
