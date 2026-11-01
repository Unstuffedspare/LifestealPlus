package dev.lifestealplus;

import dev.lifestealplus.core.HeartRules;
import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Everything read from config.yml, validated in one place.
 *
 * @param bountyAnonymous announce new bounties without naming who placed them
 */
public record PluginSettings(HeartRules rules, int naturalDeathLoss, String databaseFile, PurgatorySettings purgatory,
                             boolean bountyAnonymous) {

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

        return new PluginSettings(rules, naturalDeathLoss, config.getString("database.file", "lifesteal.db"), purgatory,
                config.getBoolean("bounty.anonymous", true));
    }
}
