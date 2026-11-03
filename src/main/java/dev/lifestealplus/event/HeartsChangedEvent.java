package dev.lifestealplus.event;

import dev.lifestealplus.core.HeartChange;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player's hearts changed, for any reason: a kill, a death, a Heart item, an admin, another
 * plugin. Fired on the main thread once the change is saved, so it can't be cancelled. A change
 * that takes someone to 0 also fires {@link PlayerEliminatedEvent}, and one that brings them
 * back fires {@link PlayerRevivedEvent}. The player may be offline.
 */
public class HeartsChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HeartChange change;

    public HeartsChangedEvent(HeartChange change) {
        this.change = change;
    }

    /** Who, why ({@link HeartChange#cause()}), and their hearts before and after. */
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
