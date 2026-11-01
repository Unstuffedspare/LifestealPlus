package dev.lifestealplus.core;

import java.util.List;

/**
 * Both sides of a PvP kill. {@code repeat} means the same killer already took a heart
 * from this victim within the repeat-kill window, so nothing moved.
 */
public record KillOutcome(HeartChange victim, HeartChange killer, boolean repeat) {

    public List<HeartChange> changes() {
        return List.of(victim, killer);
    }
}
