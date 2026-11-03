package dev.lifestealplus.core;

/**
 * What a pledge did: the pledger's payment, the new total, the revival if this pledge completed
 * it (else null), and the hearts needed to bring this player back.
 */
public record PledgeOutcome(HeartChange paid, PledgeTotal total, HeartChange revive, int needed) {
}
