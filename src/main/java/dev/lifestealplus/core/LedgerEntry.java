package dev.lifestealplus.core;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the heart ledger. {@code requested} is what was asked for (the delta
 * for ADD, the target for SET); comparing it with before/after shows when a cap
 * or elimination swallowed the change.
 */
public record LedgerEntry(
        long id,
        long eventId,
        Instant at,
        UUID player,
        String playerName,
        HeartCause cause,
        HeartRequest.Op op,
        int requested,
        int before,
        int after,
        UUID otherUuid,
        String otherName,
        String actor,
        String note) {
}
