package dev.lifestealplus.core;

import java.util.UUID;

/** The player has never joined, so there is no heart record to change. */
public class UnknownPlayerException extends RuntimeException {

    public UnknownPlayerException(UUID uuid) {
        super("No heart record for " + uuid);
    }
}
