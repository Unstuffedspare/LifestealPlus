package dev.lifestealplus.core;

import java.util.UUID;

/** Every open bounty on one player, added up. */
public record BountyTotal(UUID target, String targetName, int hearts) {
}
