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
    BOUNTY_REFUND(false),
    HEART_ITEM(false),
    HEART_WITHDRAW(false),
    RIFT(false),
    REVIVAL_PLEDGE(false),
    REVIVAL_REFUND(false),
    PACT_SHARE(false),
    IMPORT(true),
    /** Another plugin through the API: adding can't revive, setting can. */
    PLUGIN_ADD(false),
    PLUGIN_SET(true),
    /** A prize from an event, like King of the Hill. */
    EVENT_PRIZE(false),
    /** Brought back with a Revival Charm. */
    REVIVAL_ITEM(true),
    /** Hearts handed to another player with /giveheart, and received that way. */
    TRADE_SENT(false),
    TRADE_RECEIVED(false);

    private final boolean canRevive;

    HeartCause(boolean canRevive) {
        this.canRevive = canRevive;
    }

    /** Whether this cause may bring an eliminated (0-heart) player back. */
    public boolean canRevive() {
        return canRevive;
    }
}
