package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Fixed event times, written the way people say them. */
class EventScheduleTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static Instant at(String local) {
        return ZonedDateTime.parse(local + "[Europe/London]").toInstant();
    }

    @Test
    void entriesAreReadTheWayPeopleWriteThem() {
        assertEquals(new EventSchedule.Slot(DayOfWeek.SATURDAY, LocalTime.of(20, 0)), EventSchedule.parse("Saturday 20:00"));
        assertEquals(new EventSchedule.Slot(DayOfWeek.SATURDAY, LocalTime.of(20, 0)), EventSchedule.parse("sat 20:00"));
        assertEquals(new EventSchedule.Slot(null, LocalTime.of(18, 30)), EventSchedule.parse("daily 18:30"));
        assertEquals(new EventSchedule.Slot(null, LocalTime.of(9, 5)), EventSchedule.parse("9:05"));
        assertThrows(IllegalArgumentException.class, () -> EventSchedule.parse("caturday 20:00"));
        assertThrows(IllegalArgumentException.class, () -> EventSchedule.parse("saturday 8pm"));
        assertThrows(IllegalArgumentException.class, () -> EventSchedule.parse("every saturday at 20:00"));
    }

    @Test
    void theNextTimeIsTheSoonestSlot() {
        EventSchedule schedule = new EventSchedule(List.of(EventSchedule.parse("saturday 20:00"), EventSchedule.parse("daily 12:00")), LONDON);
        // Tuesday 3 November 2026, 13:00: the next noon is Wednesday's.
        assertEquals(at("2026-11-04T12:00:00Z"), schedule.next(at("2026-11-03T13:00:00Z")));
        // Saturday 14:00: tonight's 20:00 comes before Sunday noon.
        assertEquals(at("2026-11-07T20:00:00Z"), schedule.next(at("2026-11-07T14:00:00Z")));
        // Exactly on a slot, the next one is the following slot, not the same moment.
        assertEquals(at("2026-11-08T12:00:00Z"), schedule.next(at("2026-11-07T20:00:00Z")));
        assertNull(EventSchedule.NONE.next(Instant.now()));
    }

    @Test
    void aWeeklySlotWrapsToNextWeek() {
        EventSchedule saturdays = new EventSchedule(List.of(EventSchedule.parse("sat 20:00")), LONDON);
        assertEquals(at("2026-11-14T20:00:00Z"), saturdays.next(at("2026-11-07T21:00:00Z")));
    }

    @Test
    void badEntriesAndZonesAreSkippedWithAWarning() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                EventScheduleTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        config.set("blood-moon.schedule", List.of("saturday 20:00", "someday"));
        config.set("schedules.time-zone", "Mars/Olympus");
        PluginSettings.Loaded loaded = PluginSettings.load(config);
        assertEquals(1, loaded.settings().events().bloodMoon().schedule().slots().size());
        assertEquals(2, loaded.warnings().size());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.startsWith("schedules.time-zone")));
    }

    @Test
    void scheduleEntriesCanBeTypedInTheMenu() {
        SettingsCatalog catalog = new SettingsCatalog(YamlConfiguration.loadConfiguration(new InputStreamReader(
                EventScheduleTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)));
        assertEquals(List.of("saturday 20:00", "daily 18:00"),
                SettingsCatalog.parse(catalog.find("blood-moon.schedule"), "saturday 20:00, daily 18:00"));
        assertEquals(List.of(60, 10), SettingsCatalog.parse(catalog.find("blood-moon.warn-minutes"), "60 10"));
    }

    @Test
    void eliminationSoundsAreChecked() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                EventScheduleTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertEquals("entity.wither.spawn", PluginSettings.load(config).settings().extras().eliminationSound());
        config.set("eliminations.sound", "none");
        assertNull(PluginSettings.load(config).settings().extras().eliminationSound());
        config.set("eliminations.sound", "a big bang!");
        assertEquals(1, PluginSettings.load(config).warnings().size());
    }
}
