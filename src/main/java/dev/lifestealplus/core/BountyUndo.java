package dev.lifestealplus.core;

/** Bounties taken back: the refund to the placer, how many hearts that was, and what's left on the target. */
public record BountyUndo(HeartChange refund, int hearts, int totalOnTarget) {
}
