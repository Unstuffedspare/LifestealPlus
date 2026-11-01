package dev.lifestealplus.core;

/** The killer's payout from a bounty; {@code remaining} hearts stay on the target when the cap stopped the rest. */
public record BountyClaim(HeartChange change, int remaining) {
}
