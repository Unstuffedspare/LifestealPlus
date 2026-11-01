package dev.lifestealplus.core;

import java.time.Instant;
import java.util.UUID;

/** {@code avenger} can take vengeance on {@code marked} (who took their heart) until {@code expires}. */
public record VengeanceMark(UUID marked, String markedName, UUID avenger, String avengerName, Instant expires) {
}
