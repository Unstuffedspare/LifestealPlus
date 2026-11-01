package dev.lifestealplus.core;

/** A bounty that was placed: what it cost the placer, and the target's new total. */
public record BountyPlacement(HeartChange placerChange, int totalOnTarget) {
}
