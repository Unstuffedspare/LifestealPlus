package dev.lifestealplus;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fixed times for an event, written the way people say them: "saturday 20:00", "sat 20:00",
 * "daily 18:30", or just "18:30" for every day. An empty schedule means the event keeps its
 * usual random timing.
 */
public record EventSchedule(List<Slot> slots, ZoneId zone) {

    /** One time of day, on one weekday or (day null) every day. */
    public record Slot(DayOfWeek day, LocalTime time) {
    }

    public static final EventSchedule NONE = new EventSchedule(List.of(), ZoneId.systemDefault());

    public EventSchedule {
        slots = List.copyOf(slots);
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /** The first scheduled moment after {@code after}, or null for an empty schedule. */
    public Instant next(Instant after) {
        Instant best = null;
        ZonedDateTime from = after.atZone(zone);
        for (Slot slot : slots) {
            for (int days = 0; days <= 7; days++) {
                LocalDate date = from.toLocalDate().plusDays(days);
                if (slot.day() != null && date.getDayOfWeek() != slot.day()) continue;
                Instant at = ZonedDateTime.of(date, slot.time(), zone).toInstant();
                if (!at.isAfter(after)) continue;
                if (best == null || at.isBefore(best)) best = at;
                break;
            }
        }
        return best;
    }

    /** Reads one entry; throws IllegalArgumentException with a reason a person can act on. */
    static Slot parse(String entry) {
        String[] parts = entry.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length == 0 || parts.length > 2 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("\"" + entry + "\" should look like saturday 20:00, daily 18:30 or 18:30");
        }
        DayOfWeek day = null;
        if (parts.length == 2 && !parts[0].equals("daily") && !parts[0].equals("everyday")) {
            day = dayOf(parts[0]);
            if (day == null) throw new IllegalArgumentException("\"" + parts[0] + "\" isn't a day of the week");
        }
        String clock = parts[parts.length - 1];
        try {
            return new Slot(day, LocalTime.parse(clock.length() == 4 ? "0" + clock : clock));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + clock + "\" isn't a time like 20:00");
        }
    }

    private static DayOfWeek dayOf(String word) {
        for (DayOfWeek day : DayOfWeek.values()) {
            String name = day.name().toLowerCase(Locale.ROOT);
            if (word.length() >= 3 && name.startsWith(word)) return day;
        }
        return null;
    }

    /** The schedule in a config list, skipping (with a warning) any entry that can't be read. */
    static EventSchedule read(ConfigReader in, String key, ZoneId zone) {
        List<Slot> slots = new ArrayList<>();
        for (String entry : in.texts(key)) {
            try {
                slots.add(parse(entry));
            } catch (IllegalArgumentException e) {
                in.warn(key + ": " + e.getMessage() + "; skipping it.");
            }
        }
        return new EventSchedule(slots, zone);
    }
}
