package dev.lifestealplus;

import java.util.List;
import java.util.Map;
import org.bukkit.permissions.Permissible;

/**
 * The parts of /lifesteal, each with its own permission, so a rank can be given just what it
 * needs (moderators the ledger, event hosts the events). lifestealplus.admin grants them all.
 */
final class AdminPermissions {

    static final String HEARTS = "lifestealplus.admin.hearts";
    static final String SETTINGS = "lifestealplus.admin.settings";
    static final String EVENTS = "lifestealplus.admin.events";
    static final String SEASON = "lifestealplus.admin.season";
    static final String RECORDS = "lifestealplus.admin.records";

    private static final List<String> ALL = List.of(HEARTS, SETTINGS, EVENTS, SEASON, RECORDS);

    /** Each /lifesteal subcommand and the permission it needs. */
    private static final Map<String, String> BY_SUBCOMMAND = Map.ofEntries(
            Map.entry("give", HEARTS), Map.entry("take", HEARTS), Map.entry("set", HEARTS), Map.entry("revive", HEARTS),
            Map.entry("ledger", HEARTS), Map.entry("shield", HEARTS), Map.entry("bounty", HEARTS),
            Map.entry("settings", SETTINGS), Map.entry("config", SETTINGS), Map.entry("preset", SETTINGS),
            Map.entry("reload", SETTINGS), Map.entry("features", SETTINGS),
            Map.entry("event", EVENTS), Map.entry("finale", EVENTS), Map.entry("item", EVENTS), Map.entry("mostwanted", EVENTS),
            Map.entry("season", SEASON),
            Map.entry("dashboard", RECORDS), Map.entry("audit", RECORDS), Map.entry("backup", RECORDS),
            Map.entry("export", RECORDS), Map.entry("import", RECORDS), Map.entry("alts", RECORDS), Map.entry("flags", RECORDS));

    private AdminPermissions() {
    }

    /** The permission a subcommand needs; anything new defaults to the full admin permission. */
    static String forSubcommand(String name) {
        return BY_SUBCOMMAND.getOrDefault(name, "lifestealplus.admin");
    }

    /** Whether they can use any part of /lifesteal (and so see the command at all). */
    static boolean any(Permissible who) {
        return ALL.stream().anyMatch(who::hasPermission);
    }
}
