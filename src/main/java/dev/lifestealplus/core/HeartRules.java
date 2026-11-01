package dev.lifestealplus.core;

import java.time.Duration;

/**
 * Tunable numbers from config. Immutable so a reload swaps the whole set at once.
 *
 * @param repeatKillWindow how long after a kill that moved a heart the same killer gets
 *     nothing for killing the same victim again; zero turns the rule off
 * @param vengeanceWindow how long a victim has to kill their killer back for a bonus; zero turns vengeance off
 * @param vengeanceBonus extra hearts a vengeance kill takes from the killer, on top of the usual heartsPerKill
 */
public record HeartRules(int startingHearts, int maxHearts, int reviveHearts, int heartsPerKill, Duration repeatKillWindow,
                         Duration vengeanceWindow, int vengeanceBonus) {

    /** Rules without vengeance. */
    public HeartRules(int startingHearts, int maxHearts, int reviveHearts, int heartsPerKill, Duration repeatKillWindow) {
        this(startingHearts, maxHearts, reviveHearts, heartsPerKill, repeatKillWindow, Duration.ZERO, 0);
    }

    public HeartRules {
        if (vengeanceWindow == null || vengeanceWindow.isNegative()) {
            throw new IllegalArgumentException("vengeanceWindow can't be negative");
        }
        if (vengeanceBonus < 0) throw new IllegalArgumentException("vengeanceBonus can't be negative");
        if (repeatKillWindow == null || repeatKillWindow.isNegative()) {
            throw new IllegalArgumentException("repeatKillWindow can't be negative");
        }
        if (maxHearts < 1) throw new IllegalArgumentException("maxHearts must be at least 1");
        if (startingHearts < 1 || startingHearts > maxHearts) {
            throw new IllegalArgumentException("startingHearts must be between 1 and maxHearts");
        }
        if (reviveHearts < 1 || reviveHearts > maxHearts) {
            throw new IllegalArgumentException("reviveHearts must be between 1 and maxHearts");
        }
        if (heartsPerKill < 1) throw new IllegalArgumentException("heartsPerKill must be at least 1");
    }

    /**
     * The hearts a player ends up with when {@code request} is applied to {@code before}.
     * Eliminated players only move for causes that can revive. Gains stop at the cap,
     * but a player already above it (the cap was lowered) keeps what they have.
     */
    public int resolve(int before, HeartRequest request) {
        if (before <= 0 && !request.cause().canRevive()) return before;
        if (request.op() == HeartRequest.Op.SET) {
            return Math.max(0, Math.min(maxHearts, request.amount()));
        }
        int target = before + request.amount();
        if (request.amount() >= 0) return Math.min(target, Math.max(before, maxHearts));
        return Math.max(target, 0);
    }
}
