package dev.lifestealplus.core;

import java.util.List;

/**
 * Kill streaks: kills that took a heart, in a row, without dying.
 *
 * @param announceAt streak lengths worth telling everyone about, e.g. 3, 5 and 10
 * @param shutdownMin ending a streak at least this long takes {@code shutdownBonus} extra hearts from the streaker
 */
public record StreakRules(boolean enabled, List<Integer> announceAt, int shutdownMin, int shutdownBonus) {

    public static final StreakRules OFF = new StreakRules(false, List.of(), 0, 0);

    public StreakRules {
        announceAt = List.copyOf(announceAt);
    }

    /** The shortest streak anyone hears about; a death ending one at least this long is news. */
    public int noteworthy() {
        return announceAt.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
    }

    public boolean shutdownApplies(int streak) {
        return enabled && shutdownBonus > 0 && shutdownMin > 0 && streak >= shutdownMin;
    }
}
