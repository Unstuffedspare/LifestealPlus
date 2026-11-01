package dev.lifestealplus.core;

/**
 * Why a player's hearts changed. Stored by name in the ledger, so renaming a
 * constant orphans old rows: add new ones instead.
 */
public enum HeartCause {
    FIRST_JOIN(true),
    PVP_KILL(false),
    PVP_DEATH(false),
    DEATH(false),
    ADMIN_SET(true),
    ADMIN_GIVE(true),
    ADMIN_TAKE(false),
    REVIVE(true),
    BOUNTY_PLACE(false),
    BOUNTY_CLAIM(false),
    BOUNTY_REFUND(false);

    private final boolean canRevive;

    HeartCause(boolean canRevive) {
        this.canRevive = canRevive;
    }

    /** Whether this cause may bring an eliminated (0-heart) player back. */
    public boolean canRevive() {
        return canRevive;
    }
}
