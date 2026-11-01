package dev.lifestealplus.core;

/**
 * What winning a trial gave one player. {@code revive} is set when that fragment was
 * the last one they needed; fragments is 0 if they weren't eliminated (nothing to earn).
 */
public record TrialReward(int fragments, int needed, HeartChange revive) {
}
