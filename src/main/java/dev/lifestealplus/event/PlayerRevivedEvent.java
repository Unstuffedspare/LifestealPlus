package dev.lifestealplus.event;

import dev.lifestealplus.core.HeartChange;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** An eliminated player just got hearts back. Fired on the main thread after commit; the player may be offline. */
public class PlayerRevivedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HeartChange change;

    public PlayerRevivedEvent(HeartChange change) {
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
