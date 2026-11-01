package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A killer gets nothing for killing the same victim again within the window, and the victim keeps their heart. */
class RepeatKillTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path dir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = new HeartStore(dir.resolve("test.db"), clock);
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
    void secondKillWithinWindowMovesNothingAndIsLogged() throws SQLException {
        assertFalse(store.kill(BOB, ALICE, RULES).repeat());
        clock.advance(Duration.ofHours(1));
        KillOutcome again = store.kill(BOB, ALICE, RULES);

        assertTrue(again.repeat());
        assertEquals(9, hearts(BOB));
        assertEquals(11, hearts(ALICE));
        assertEquals(0, again.victim().delta());
        assertEquals(0, again.killer().delta());
        LedgerEntry victimRow = store.ledger(BOB, 1, 0).getFirst();
        assertEquals(HeartCause.PVP_DEATH, victimRow.cause());
        assertEquals("repeat kill", victimRow.note());
        assertEquals("repeat kill", store.ledger(ALICE, 1, 0).getFirst().note());
        assertEquals(again.victim().eventId(), again.killer().eventId());
    }

    @Test
    void windowCountsFromLastPaidKillSoCampingDoesNotExtendIt() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        clock.advance(Duration.ofHours(5));
        assertTrue(store.kill(BOB, ALICE, RULES).repeat());
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        assertFalse(store.kill(BOB, ALICE, RULES).repeat());
        assertEquals(8, hearts(BOB));
        assertEquals(12, hearts(ALICE));
    }

    @Test
    void revengeKillStillPays() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        assertFalse(store.kill(ALICE, BOB, RULES).repeat());
        assertEquals(10, hearts(ALICE));
        assertEquals(10, hearts(BOB));
    }

    @Test
    void otherKillersAreUnaffected() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        assertFalse(store.kill(BOB, CARA, RULES).repeat());
        assertEquals(8, hearts(BOB));
    }

    @Test
    void repeatKillCannotEliminate() throws SQLException {
        store.apply(List.of(HeartRequest.set(BOB, 2, HeartCause.ADMIN_SET, "op")), RULES);
        store.kill(BOB, ALICE, RULES);
        KillOutcome again = store.kill(BOB, ALICE, RULES);
        assertFalse(again.victim().eliminatedNow());
        assertEquals(1, hearts(BOB));
    }

    @Test
    void killThatOnlyHitTheCapStillStartsTheWindow() throws SQLException {
        store.apply(List.of(HeartRequest.set(ALICE, 20, HeartCause.ADMIN_SET, "op")), RULES);
        store.kill(BOB, ALICE, RULES);
        assertTrue(store.kill(BOB, ALICE, RULES).repeat());
        assertEquals(9, hearts(BOB));
    }

    @Test
    void zeroWindowTurnsTheRuleOff() throws SQLException {
        HeartRules off = new HeartRules(10, 20, 3, 1, Duration.ZERO);
        store.kill(BOB, ALICE, off);
        assertFalse(store.kill(BOB, ALICE, off).repeat());
        assertEquals(8, hearts(BOB));
    }

    /** A clock tests can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
