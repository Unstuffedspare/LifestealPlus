package dev.lifestealplus;

import dev.lifestealplus.core.HeartRules;
import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Everything read from config.yml, validated in one place.
 *
 * @param bountyAnonymous announce new bounties without naming who placed them
 * @param lastStandHearts players at this many hearts or fewer (but not eliminated) get Last Stand; 0 turns it off
 */
public record PluginSettings(HeartRules rules, int naturalDeathLoss, String databaseFile, PurgatorySettings purgatory,
                             boolean bountyAnonymous, int lastStandHearts, MostWantedSettings mostWanted,
                             CombatSettings combat) {

    /**
     * @param tag how long a PvP hit keeps both players in combat
     * @param body how long a combat-logger's body stays to be killed; zero means no bodies
     */
    public record CombatSettings(Duration tag, Duration body) {
    }

    /**
     * @param interval how often the leader's rough location is broadcast; zero turns it off
     * @param precision coordinates are rounded to the nearest this many blocks
     * @param minPlayers don't single anyone out unless at least this many players are online
     */
    public record MostWantedSettings(Duration interval, int precision, int minPlayers) {
    }

    /**
     * @param enabled read once at startup: turning Purgatory on or off needs a restart
     * @param world read once at startup, like {@code enabled}
     * @param difficulty scales how many enemies a trial sends (1.0 is normal)
     */
    public record PurgatorySettings(boolean enabled, String world, int fragmentsNeeded, Duration trialCooldown, double difficulty) {
    }

    public static PluginSettings from(FileConfiguration config) {
        double repeatHours = config.getDouble("pvp.repeat-kill-cooldown-hours", 6);
        if (repeatHours < 0) throw new IllegalArgumentException("pvp.repeat-kill-cooldown-hours can't be negative");
        double vengeanceMinutes = config.getDouble("pvp.vengeance-minutes", 30);
        if (vengeanceMinutes < 0) throw new IllegalArgumentException("pvp.vengeance-minutes can't be negative");
        HeartRules rules = new HeartRules(
                config.getInt("hearts.starting", 10),
                config.getInt("hearts.max", 20),
                config.getInt("hearts.revive", 3),
                config.getInt("hearts.per-kill", 1),
                Duration.ofMinutes(Math.round(repeatHours * 60)),
                Duration.ofSeconds(Math.round(vengeanceMinutes * 60)),
                config.getInt("pvp.vengeance-bonus-hearts", 1));
        int naturalDeathLoss = config.getInt("hearts.natural-death-loss", 1);
        if (naturalDeathLoss < 0) throw new IllegalArgumentException("hearts.natural-death-loss can't be negative");

        int fragments = config.getInt("purgatory.fragments-needed", 3);
        if (fragments < 1) throw new IllegalArgumentException("purgatory.fragments-needed must be at least 1");
        double cooldownMinutes = config.getDouble("purgatory.trial-cooldown-minutes", 20);
        if (cooldownMinutes < 0) throw new IllegalArgumentException("purgatory.trial-cooldown-minutes can't be negative");
        double difficulty = config.getDouble("purgatory.difficulty", 1.0);
        if (difficulty <= 0) throw new IllegalArgumentException("purgatory.difficulty must be above 0");
        PurgatorySettings purgatory = new PurgatorySettings(
                config.getBoolean("purgatory.enabled", true),
                config.getString("purgatory.world", "lifesteal_purgatory"),
                fragments,
                Duration.ofSeconds(Math.round(cooldownMinutes * 60)),
                difficulty);

        int lastStand = config.getInt("last-stand.hearts", 1);
        if (lastStand < 0) throw new IllegalArgumentException("last-stand.hearts can't be negative");

        double wantedMinutes = config.getDouble("most-wanted.interval-minutes", 120);
        if (wantedMinutes < 0) throw new IllegalArgumentException("most-wanted.interval-minutes can't be negative");
        int precision = config.getInt("most-wanted.precision-blocks", 100);
        if (precision < 1) throw new IllegalArgumentException("most-wanted.precision-blocks must be at least 1");
        MostWantedSettings mostWanted = new MostWantedSettings(
                Duration.ofSeconds(Math.round(wantedMinutes * 60)), precision,
                Math.max(1, config.getInt("most-wanted.min-players-online", 3)));

        int tagSeconds = config.getInt("combat.tag-seconds", 15);
        int bodySeconds = config.getInt("combat.body-seconds", 30);
        if (tagSeconds < 0 || bodySeconds < 0) throw new IllegalArgumentException("combat times can't be negative");
        CombatSettings combat = new CombatSettings(Duration.ofSeconds(tagSeconds), Duration.ofSeconds(bodySeconds));

        return new PluginSettings(rules, naturalDeathLoss, config.getString("database.file", "lifesteal.db"), purgatory,
                config.getBoolean("bounty.anonymous", true), lastStand, mostWanted, combat);
    }
}
