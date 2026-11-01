package dev.lifestealplus;

import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

/** Settings for heart items and the server events (Blood Moon, airdrops, finale). */
public record EventSettings(int fragmentsPerHeart, BloodMoon bloodMoon, Airdrops airdrops, double finaleDamagePerBlock) {

    /**
     * @param every average time between Blood Moons (the exact time is random); zero turns them off
     * @param killMultiplier PvP kills move this many times the usual hearts while it lasts
     * @param fragmentChance chance a hostile mob killed by a player drops a Heart Fragment
     */
    public record BloodMoon(Duration every, Duration length, int killMultiplier, double fragmentChance, int minPlayers) {
    }

    /** @param every average time between drops; zero turns them off */
    public record Airdrops(Duration every, int radius, Duration warning, Duration despawn, int minPlayers) {
    }

    static EventSettings from(FileConfiguration config) {
        int fragments = config.getInt("heart-items.fragments-per-heart", 4);
        if (fragments < 1) throw new IllegalArgumentException("heart-items.fragments-per-heart must be at least 1");

        double chance = config.getDouble("blood-moon.fragment-chance", 0.02);
        if (chance < 0 || chance > 1) throw new IllegalArgumentException("blood-moon.fragment-chance must be between 0 and 1");
        BloodMoon bloodMoon = new BloodMoon(
                hours(config.getDouble("blood-moon.every-days", 7) * 24, "blood-moon.every-days"),
                minutes(config.getDouble("blood-moon.minutes", 20), "blood-moon.minutes"),
                Math.max(1, config.getInt("blood-moon.kill-multiplier", 2)),
                chance,
                Math.max(1, config.getInt("blood-moon.min-players-online", 3)));

        Airdrops airdrops = new Airdrops(
                minutes(config.getDouble("airdrops.every-minutes", 90), "airdrops.every-minutes"),
                Math.max(16, config.getInt("airdrops.radius", 1500)),
                minutes(config.getDouble("airdrops.warning-minutes", 5), "airdrops.warning-minutes"),
                minutes(config.getDouble("airdrops.despawn-minutes", 20), "airdrops.despawn-minutes"),
                Math.max(1, config.getInt("airdrops.min-players-online", 3)));

        double damage = config.getDouble("finale.damage-per-block", 1.0);
        if (damage < 0) throw new IllegalArgumentException("finale.damage-per-block can't be negative");
        return new EventSettings(fragments, bloodMoon, airdrops, damage);
    }

    private static Duration minutes(double value, String key) {
        if (value < 0) throw new IllegalArgumentException(key + " can't be negative");
        return Duration.ofSeconds(Math.round(value * 60));
    }

    private static Duration hours(double value, String key) {
        if (value < 0) throw new IllegalArgumentException(key + " can't be negative");
        return Duration.ofMinutes(Math.round(value * 60));
    }
}
