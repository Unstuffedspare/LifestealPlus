package dev.lifestealplus.core;

/** A player's kill streak now, and their longest this season. */
public record PlayerStreak(String name, int current, int best) {
}
