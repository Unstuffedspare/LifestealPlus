package dev.lifestealplus.core;

/** What a pledge did: the pledger's payment, the new total, and the revival if this pledge completed it (else null). */
public record PledgeOutcome(HeartChange paid, PledgeTotal total, HeartChange revive) {
}
