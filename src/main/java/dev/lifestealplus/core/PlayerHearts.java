package dev.lifestealplus.core;

import java.util.UUID;

public record PlayerHearts(UUID uuid, String name, int hearts) {

    /** A player with no hearts left is out of the game. */
    public boolean eliminated() {
        return hearts <= 0;
    }
}
