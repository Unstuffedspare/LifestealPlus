package dev.lifestealplus;

import dev.lifestealplus.core.HeartRules;
import dev.lifestealplus.core.StreakRules;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Everything read from config.yml. A feature that's switched off shows up here as its
 * "nothing happens" value (a zero duration, a zero threshold, a false flag), so the code
 * using it never has to know about the switch.
 *
 * @param bountiesEnabled whether new bounties can be placed (existing ones still pay out)
 * @param bountyAnonymous announce new bounties without naming who placed them
 * @param lastStandHearts players at this many hearts or fewer (but not eliminated) get Last Stand; 0 means off
 * @param excludedWorlds lower-cased names of worlds where hearts don't move
 */
public record PluginSettings(HeartRules rules, int naturalDeathLoss, String databaseFile, PurgatorySettings purgatory,
                             boolean bountiesEnabled, boolean bountyAnonymous, int lastStandHearts,
                             MostWantedSettings mostWanted, CombatSettings combat, EventSettings events,
                             SocialSettings social, Extras extras, StreakRules streaks, RecapSettings recap,
                             Set<String> excludedWorlds, HeartRecipe heartRecipe, CombatItemRules combatItems,
                             String discordWebhook) {

    /** Whether dying and killing in this world move hearts (false for the worlds listed in worlds.excluded). */
    public boolean lifestealIn(World world) {
        return !excludedWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * @param enabled read once at startup: turning Purgatory on or off needs a restart
     * @param world read once at startup, like {@code enabled}
     * @param difficulty scales how many enemies a trial sends (1.0 is normal)
     */
    public record PurgatorySettings(boolean enabled, String world, int fragmentsNeeded, Duration trialCooldown, double difficulty) {
    }

    /**
     * @param interval how often the leader's rough location is broadcast; zero means off
     * @param precision coordinates are rounded to the nearest this many blocks
     * @param minPlayers don't single anyone out unless at least this many players are online
     */
    public record MostWantedSettings(Duration interval, int precision, int minPlayers) {
    }

    /**
     * @param tag how long a PvP hit keeps both players in combat; zero means no tagging (and so no bodies)
     * @param body how long a combat-logger's body stays to be killed; zero means no bodies
     */
    public record CombatSettings(Duration tag, Duration body) {
    }

    /**
     * Smaller switches.
     *
     * @param trackerRoundTo the vengeance tracker rounds distances to this many blocks
     * @param shieldsEnabled when false, existing newbie shields are suspended as well as new ones skipped
     * @param shieldExpiry a newbie shield ends this long after first join however little was played; zero for never
     * @param naturalDeathDrops hearts lost to a non-player death drop as Heart items where the player died
     * @param keepLogins how long alt detection remembers an address after its last use
     */
    public record Extras(boolean vengeanceTracker, int trackerRoundTo, boolean tabList, boolean pactsInTab,
                         boolean publicSeasons, boolean altDetection, boolean shieldsEnabled, Duration shieldExpiry,
                         boolean convertLifestealZItems, boolean naturalDeathDrops, Duration keepLogins) {
    }

    /**
     * @param totems Totems of Undying save players
     * @param endCrystals end crystal explosions hurt players
     * @param respawnBombs respawn anchors and beds exploding outside their dimension hurt players
     */
    public record CombatItemRules(boolean totems, boolean endCrystals, boolean respawnBombs) {
    }

    /** The crafting recipe for a Heart: three item names in a 3x3 square. Null ingredients when it's off. */
    public record HeartRecipe(boolean enabled, String corners, String sides, String middle) {

        public static final HeartRecipe OFF = new HeartRecipe(false, null, null, null);
    }

    /**
     * The "while you were away" summary and /news.
     *
     * @param minAway only players away at least this long get the summary
     * @param lines how many items the summary shows; /news has the rest
     * @param keep how long news is kept
     */
    public record RecapSettings(boolean enabled, Duration minAway, int lines, Duration keep) {
    }

    /** The settings, plus anything in the file that had to be replaced by a default. */
    public record Loaded(PluginSettings settings, List<String> warnings) {
    }

    /** Tells the settings which names are items, which only a running server knows. */
    static void itemNames(java.util.function.Predicate<String> isItem) {
        ConfigReader.itemNames = isItem;
    }

    public static Loaded load(FileConfiguration config) {
        ConfigReader in = new ConfigReader(config);

        int max = in.whole("hearts.max", 20, 1, 1000);
        int starting = in.whole("hearts.starting", 10, 1, 1000);
        if (starting > max) {
            in.warn("hearts.starting (" + starting + ") is above hearts.max (" + max + "); starting at " + max + " instead.");
            starting = max;
        }
        int revive = in.whole("hearts.revive", 3, 1, 1000);
        if (revive > max) {
            in.warn("hearts.revive (" + revive + ") is above hearts.max (" + max + "); reviving with " + max + " instead.");
            revive = max;
        }
        int perKill = in.whole("hearts.per-kill", 1, 1, 1000);

        Duration repeat = in.flag("repeat-kills.enabled", true)
                ? hours(in.number("repeat-kills.cooldown-hours", 6, 0, 24 * 365)) : Duration.ZERO;
        boolean vengeance = in.flag("vengeance.enabled", true);
        Duration vengeanceWindow = vengeance ? minutes(in.number("vengeance.minutes", 30, 0, 60 * 24 * 7)) : Duration.ZERO;
        int vengeanceBonus = vengeance ? in.whole("vengeance.bonus-hearts", 1, 0, 1000) : 0;
        boolean shields = in.flag("newbie-shield.enabled", true);
        Duration shield = shields ? hours(in.number("newbie-shield.hours", 3, 0, 24 * 30)) : Duration.ZERO;
        HeartRules rules = new HeartRules(starting, max, revive, perKill, repeat, vengeanceWindow, vengeanceBonus, shield);
        int naturalDeathLoss = in.whole("hearts.natural-death-loss", 1, 0, 1000);

        PurgatorySettings purgatory = new PurgatorySettings(
                in.flag("purgatory.enabled", true),
                in.text("purgatory.world", "lifesteal_purgatory"),
                in.whole("purgatory.fragments-needed", 3, 1, 100),
                minutes(in.number("purgatory.trial-cooldown-minutes", 20, 0, 60 * 24 * 30)),
                in.number("purgatory.difficulty", 1.0, 0.1, 10));

        int lastStand = in.flag("last-stand.enabled", true) ? in.whole("last-stand.hearts", 1, 0, 1000) : 0;

        boolean wanted = in.flag("most-wanted.enabled", true);
        MostWantedSettings mostWanted = new MostWantedSettings(
                wanted ? minutes(in.number("most-wanted.interval-minutes", 120, 1, 60 * 24 * 30)) : Duration.ZERO,
                in.whole("most-wanted.precision-blocks", 100, 1, 100_000),
                in.whole("most-wanted.min-players-online", 3, 1, 1000));

        boolean combatLog = in.flag("combat-log.enabled", true);
        CombatSettings combat = new CombatSettings(
                combatLog ? Duration.ofSeconds(in.whole("combat-log.tag-seconds", 15, 1, 600)) : Duration.ZERO,
                combatLog ? Duration.ofSeconds(in.whole("combat-log.body-seconds", 30, 0, 600)) : Duration.ZERO);

        Extras extras = new Extras(
                vengeance && in.flag("vengeance.tracker.enabled", true),
                in.whole("vengeance.tracker.round-to-blocks", 10, 1, 1000),
                in.flag("tab-list.enabled", true),
                in.flag("blood-pacts.show-in-tab", true),
                in.flag("seasons.public-history", true),
                in.flag("alt-detection.enabled", true),
                shields,
                hours(24 * in.number("newbie-shield.expires-after-days", 3, 0, 365)),
                in.flag("lifestealz-import.convert-items", true),
                in.flag("hearts.natural-death-drops", true),
                hours(24 * in.number("alt-detection.keep-days", 90, 1, 3650)));
        HeartRecipe heartRecipe = in.flag("heart-items.recipe.enabled", true)
                ? new HeartRecipe(true, in.item("heart-items.recipe.corners", "diamond_block"),
                        in.item("heart-items.recipe.sides", "gold_block"),
                        in.item("heart-items.recipe.middle", "netherite_ingot"))
                : HeartRecipe.OFF;

        StreakRules streaks = in.flag("streaks.enabled", true)
                ? new StreakRules(true, in.wholes("streaks.announce-at", List.of(3, 5, 10)),
                        in.whole("streaks.shutdown-at", 5, 0, 1000), in.whole("streaks.shutdown-bonus-hearts", 1, 0, 1000))
                : StreakRules.OFF;
        RecapSettings recap = new RecapSettings(
                in.flag("recap.enabled", true),
                hours(in.number("recap.min-away-hours", 1, 0, 24 * 30)),
                in.whole("recap.lines", 5, 1, 20),
                hours(24 * in.number("recap.keep-days", 14, 1, 365)));

        PluginSettings settings = new PluginSettings(rules, naturalDeathLoss, in.text("database.file", "lifesteal.db"), purgatory,
                in.flag("bounties.enabled", true), in.flag("bounties.anonymous", true), lastStand, mostWanted, combat,
                EventSettings.from(in), SocialSettings.from(in, starting, revive), extras, streaks, recap,
                in.texts("worlds.excluded").stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                heartRecipe,
                new CombatItemRules(in.flag("combat-items.totems", true), in.flag("combat-items.end-crystals", true),
                        in.flag("combat-items.bed-and-anchor-explosions", true)),
                webhook(in));
        return new Loaded(settings, List.copyOf(in.warnings()));
    }

    /** The Discord webhook link, or null for none. */
    private static String webhook(ConfigReader in) {
        String url = in.text("discord.webhook-url", "none").strip();
        if (url.equalsIgnoreCase("none") || url.equalsIgnoreCase("off")) return null;
        if (url.startsWith("https://") || url.startsWith("http://")) return url;
        in.warn("discord.webhook-url should be a link starting with https:// (or none); not posting to Discord.");
        return null;
    }

    static Duration minutes(double value) {
        return Duration.ofSeconds(Math.round(value * 60));
    }

    static Duration hours(double value) {
        return Duration.ofMinutes(Math.round(value * 60));
    }
}
