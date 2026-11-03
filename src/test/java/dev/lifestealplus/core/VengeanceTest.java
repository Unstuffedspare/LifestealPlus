package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Killing the player who just took your heart takes it back, plus a bonus, from them. */
class VengeanceTest {

    private static final HeartRules RULES =
            new HeartRules(10, 20, 3, 1, Duration.ofHours(6), Duration.ofMinutes(30), 1);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-01T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), clock);
        store.touch(ALICE, "Alice", RULES);
        store.touch(BOB, "Bob", RULES);
        store.touch(CARA, "Cara", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    private int hearts(UUID uuid) throws SQLException {
        return store.find(uuid).orElseThrow().hearts();
    }

    @Test
    void aPaidKillMarksTheKiller() throws SQLException {
        KillOutcome kill = store.kill(BOB, ALICE, RULES);
        assertTrue(kill.marked());
        List<VengeanceMark> held = store.marksHeldBy(BOB);
        assertEquals(1, held.size());
        assertEquals(ALICE, held.getFirst().marked());
        assertEquals("Alice", held.getFirst().markedName());
        assertEquals(clock.instant().plus(Duration.ofMinutes(30)), held.getFirst().expires());
        assertEquals(1, store.marksOn(ALICE).size());
    }

    @Test
    void killingYourKillerInTheWindowTakesBackYourHeartAndABonus() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        clock.advance(Duration.ofMinutes(20));
        KillOutcome revenge = store.kill(ALICE, BOB, RULES);

        assertTrue(revenge.vengeance());
        assertEquals(9, hearts(ALICE));
        assertEquals(11, hearts(BOB));
        assertEquals("vengeance", store.ledger(BOB, 1, 0).getFirst().note());
        assertEquals("vengeance", store.ledger(ALICE, 1, 0).getFirst().note());
        assertTrue(store.marksHeldBy(BOB).isEmpty());
    }

    @Test
    void vengeanceDoesNotStartACounterFeud() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        KillOutcome revenge = store.kill(ALICE, BOB, RULES);
        assertFalse(revenge.marked());
        assertTrue(store.marksHeldBy(ALICE).isEmpty());
    }

    @Test
    void markExpires() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        clock.advance(Duration.ofMinutes(31));
        KillOutcome late = store.kill(ALICE, BOB, RULES);
        assertFalse(late.vengeance());
        assertEquals(10, hearts(ALICE));
        assertEquals(10, hearts(BOB));
        assertTrue(store.marksHeldBy(BOB).isEmpty());
    }

    @Test
    void avengerOnlyGainsWhatTheKillerHad() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        store.apply(List.of(HeartRequest.set(ALICE, 1, HeartCause.ADMIN_SET, "op")), RULES);
        KillOutcome revenge = store.kill(ALICE, BOB, RULES);
        assertTrue(revenge.victim().eliminatedNow());
        assertEquals(10, hearts(BOB));
    }

    @Test
    void otherKillersGetNoBonus() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        KillOutcome third = store.kill(ALICE, CARA, RULES);
        assertFalse(third.vengeance());
        assertEquals(10, hearts(ALICE));
        assertEquals(1, store.marksHeldBy(BOB).size());
    }

    @Test
    void blockedRepeatKillsDoNotMarkOrAvenge() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        clock.advance(Duration.ofMinutes(5));
        KillOutcome blocked = store.kill(BOB, ALICE, RULES);
        assertFalse(blocked.marked());
        assertEquals(clock.instant().minus(Duration.ofMinutes(5)).plus(Duration.ofMinutes(30)),
                store.marksHeldBy(BOB).getFirst().expires());
    }

    @Test
    void aLaterPaidKillRefreshesTheMark() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        clock.advance(Duration.ofHours(7));
        store.kill(BOB, ALICE, RULES);
        assertEquals(1, store.marksHeldBy(BOB).size());
        assertEquals(clock.instant().plus(Duration.ofMinutes(30)), store.marksHeldBy(BOB).getFirst().expires());
    }

    @Test
    void zeroWindowTurnsVengeanceOff() throws SQLException {
        HeartRules off = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
        KillOutcome kill = store.kill(BOB, ALICE, off);
        assertFalse(kill.marked());
        assertFalse(store.kill(ALICE, BOB, off).vengeance());
    }
}
