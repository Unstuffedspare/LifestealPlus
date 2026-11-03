package dev.lifestealplus;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/** Settings for heart items and the server events (Blood Moon, airdrops, finale). */
public record EventSettings(int fragmentsPerHeart, BloodMoon bloodMoon, Airdrops airdrops, double finaleDamagePerBlock) {

    /**
     * @param every average time between Blood Moons (the exact time is random); zero means off
     * @param killMultiplier PvP kills move this many times the usual hearts while it lasts
     * @param fragmentChance chance a hostile mob killed by a player drops a Heart Fragment
     * @param schedule fixed times instead of random ones; empty for random
     * @param warnMinutes everyone is warned this many minutes before one rises
     */
    public record BloodMoon(Duration every, Duration length, int killMultiplier, double fragmentChance, int minPlayers,
                            EventSchedule schedule, List<Integer> warnMinutes) {

        /** Whether it happens at all. */
        public boolean enabled() {
            return !every.isZero();
        }
    }

    /**
     * @param every average time between drops; zero means off
     * @param schedule fixed landing times instead of every; empty for every
     */
    public record Airdrops(Duration every, int radius, Duration warning, Duration despawn, int minPlayers,
                           EventSchedule schedule) {
    }

    static EventSettings from(ConfigReader in) {
        int fragments = in.whole("heart-items.fragments-per-heart", 4, 1, 64);
        ZoneId zone = zone(in);

        boolean moon = in.flag("blood-moon.enabled", true);
        BloodMoon bloodMoon = new BloodMoon(
                moon ? PluginSettings.hours(in.number("blood-moon.every-days", 7, 0.01, 365) * 24) : Duration.ZERO,
                PluginSettings.minutes(in.number("blood-moon.minutes", 20, 1, 60 * 24)),
                in.whole("blood-moon.kill-multiplier", 2, 1, 10),
                in.number("blood-moon.fragment-chance", 0.02, 0, 1),
                in.whole("blood-moon.min-players-online", 3, 1, 1000),
                EventSchedule.read(in, "blood-moon.schedule", zone),
                in.wholes("blood-moon.warn-minutes", List.of(10)));

        boolean drops = in.flag("airdrops.enabled", true);
        Airdrops airdrops = new Airdrops(
                drops ? PluginSettings.minutes(in.number("airdrops.every-minutes", 90, 1, 60 * 24 * 30)) : Duration.ZERO,
                in.whole("airdrops.radius", 1500, 16, 1_000_000),
                PluginSettings.minutes(in.number("airdrops.warning-minutes", 5, 0, 120)),
                PluginSettings.minutes(in.number("airdrops.despawn-minutes", 20, 0.1, 60 * 24)),
                in.whole("airdrops.min-players-online", 3, 1, 1000),
                EventSchedule.read(in, "airdrops.schedule", zone));

        return new EventSettings(fragments, bloodMoon, airdrops, in.number("finale.damage-per-block", 1.0, 0, 100));
    }

    private static ZoneId zone(ConfigReader in) {
        String name = in.text("schedules.time-zone", "server").strip();
        if (name.equalsIgnoreCase("server")) return ZoneId.systemDefault();
        try {
            return ZoneId.of(name);
        } catch (java.time.DateTimeException e) {
            in.warn("schedules.time-zone is \"" + name + "\", which isn't a time zone like Europe/London; using the server's.");
            return ZoneId.systemDefault();
        }
    }
}
