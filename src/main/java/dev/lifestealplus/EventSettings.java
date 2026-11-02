package dev.lifestealplus;

import java.time.Duration;

/** Settings for heart items and the server events (Blood Moon, airdrops, finale). */
public record EventSettings(int fragmentsPerHeart, BloodMoon bloodMoon, Airdrops airdrops, double finaleDamagePerBlock) {

    /**
     * @param every average time between Blood Moons (the exact time is random); zero means off
     * @param killMultiplier PvP kills move this many times the usual hearts while it lasts
     * @param fragmentChance chance a hostile mob killed by a player drops a Heart Fragment
     */
    public record BloodMoon(Duration every, Duration length, int killMultiplier, double fragmentChance, int minPlayers) {
    }

    /** @param every average time between drops; zero means off */
    public record Airdrops(Duration every, int radius, Duration warning, Duration despawn, int minPlayers) {
    }

    static EventSettings from(ConfigReader in) {
        int fragments = in.whole("heart-items.fragments-per-heart", 4, 1, 64);

        boolean moon = in.flag("blood-moon.enabled", true);
        BloodMoon bloodMoon = new BloodMoon(
                moon ? PluginSettings.hours(in.number("blood-moon.every-days", 7, 0.01, 365) * 24) : Duration.ZERO,
                PluginSettings.minutes(in.number("blood-moon.minutes", 20, 1, 60 * 24)),
                in.whole("blood-moon.kill-multiplier", 2, 1, 10),
                in.number("blood-moon.fragment-chance", 0.02, 0, 1),
                in.whole("blood-moon.min-players-online", 3, 1, 1000));

        boolean drops = in.flag("airdrops.enabled", true);
        Airdrops airdrops = new Airdrops(
                drops ? PluginSettings.minutes(in.number("airdrops.every-minutes", 90, 1, 60 * 24 * 30)) : Duration.ZERO,
                in.whole("airdrops.radius", 1500, 16, 1_000_000),
                PluginSettings.minutes(in.number("airdrops.warning-minutes", 5, 0, 120)),
                PluginSettings.minutes(in.number("airdrops.despawn-minutes", 20, 0.1, 60 * 24)),
                in.whole("airdrops.min-players-online", 3, 1, 1000));

        return new EventSettings(fragments, bloodMoon, airdrops, in.number("finale.damage-per-block", 1.0, 0, 100));
    }
}
