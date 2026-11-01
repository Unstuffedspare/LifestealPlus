package dev.lifestealplus.core;

import java.time.Instant;
import java.util.UUID;

/** A PvP kill between two accounts that have logged in from the same address: maybe farming, maybe housemates. */
public record AltFlag(long eventId, Instant at, UUID killer, String killerName, UUID victim, String victimName) {
}
