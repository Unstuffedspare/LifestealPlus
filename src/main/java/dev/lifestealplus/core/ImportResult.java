package dev.lifestealplus.core;

/** How an import went: players new to this server, and players who were already here and got updated. */
public record ImportResult(int created, int updated) {
}
