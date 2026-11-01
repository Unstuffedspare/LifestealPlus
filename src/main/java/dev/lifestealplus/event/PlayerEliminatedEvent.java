package dev.lifestealplus.event;

import dev.lifestealplus.core.HeartChange;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player just reached 0 hearts. Fired on the main thread after the change is
 * committed, so it can't be cancelled; it's the hook for what happens to eliminated
 * players (Purgatory, later). The player may be offline.
 */
public class PlayerEliminatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HeartChange change;

    public PlayerEliminatedEvent(HeartChange change) {
        this.change = change;
    }

    public HeartChange change() {
        return change;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
