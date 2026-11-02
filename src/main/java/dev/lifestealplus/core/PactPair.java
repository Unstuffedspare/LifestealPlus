package dev.lifestealplus.core;

import java.util.UUID;

/** Two players bound by a Blood Pact. */
public record PactPair(UUID first, String firstName, UUID second, String secondName) {
}
