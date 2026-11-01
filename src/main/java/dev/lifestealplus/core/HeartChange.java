package dev.lifestealplus.core;

import java.util.UUID;

/** What actually happened after rules were applied to a {@link HeartRequest}. {@code otherName} may be null. */
public record HeartChange(
        long ledgerId,
        long eventId,
        UUID player,
        String name,
        HeartCause cause,
        int before,
        int after,
        String otherName) {

    public int delta() {
        return after - before;
    }

    public boolean eliminatedNow() {
        return before > 0 && after <= 0;
    }

    public boolean revivedNow() {
        return before <= 0 && after > 0;
    }
}
