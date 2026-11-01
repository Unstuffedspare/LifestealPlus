package dev.lifestealplus.core;

import java.util.UUID;

/** What actually happened after rules were applied to a {@link HeartRequest}. */
public record HeartChange(
        long ledgerId,
        long eventId,
        UUID player,
        String name,
        HeartCause cause,
        int before,
        int after) {

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
