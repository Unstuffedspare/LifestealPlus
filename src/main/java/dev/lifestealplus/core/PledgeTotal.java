package dev.lifestealplus.core;

import java.util.UUID;

/** Hearts pledged so far to bring one eliminated player back, and by how many players. */
public record PledgeTotal(UUID target, String targetName, int hearts, int voters) {
}
