package dev.lifestealplus.core;

import java.time.Instant;

/**
 * One admin action: who, what kind (a command, a setting changed in the menu, a preset, a
 * backup), and the details.
 */
public record AuditEntry(Instant at, String actor, String action, String detail) {
}
