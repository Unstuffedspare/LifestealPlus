package dev.lifestealplus.core;

import java.time.Instant;

/** An eliminated player's way back: fragments earned so far, and when they last won a trial (null if never). */
public record PurgatoryProgress(int fragments, Instant lastWin) {

    public static final PurgatoryProgress NONE = new PurgatoryProgress(0, null);
}
