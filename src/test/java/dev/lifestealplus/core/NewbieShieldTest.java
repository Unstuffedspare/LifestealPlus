package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/** New players can't lose or gain hearts until their shield runs out (or they break it). */
class NewbieShieldTest {

    private static final Duration THREE_HOURS = Duration.ofHours(3);
    private static final HeartRules RULES =
            new HeartRules(10, 20, 3, 1, Duration.ofHours(6), Duration.ofMinutes(30), 1, THREE_HOURS);
    private static final HeartRules NO_SHIELD = new HeartRules(10, 20, 3, 1, Duration.ofHours(6), Duration.ofMinutes(30), 1);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID NEWBIE = UUID.fromString("00000000-0000-0000-0000-00000000000e");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-01T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), clock);
        store.touch(ALICE, "Alice", NO_SHIELD);
        store.touch(NEWBIE, "Newbie", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    private int hearts(UUID uuid) throws SQLException {
        return store.find(uuid).orElseThrow().hearts();
    }

    @Test
    void newPlayersStartShielded() throws SQLException {
        assertEquals(THREE_HOURS, store.shieldLeft(NEWBIE));
        assertEquals(Duration.ZERO, store.shieldLeft(ALICE));
    }

    @Test
    void rejoiningDoesNotRefillTheShield() throws SQLException {
        store.spendShield(NEWBIE, Duration.ofHours(1));
        store.touch(NEWBIE, "Newbie", RULES);
        assertEquals(Duration.ofHours(2), store.shieldLeft(NEWBIE));
    }

    @Test
    void shieldedPlayersLoseNothingToNaturalDeaths() throws SQLException {
        store.apply(List.of(HeartRequest.add(NEWBIE, -1, HeartCause.DEATH, "server")), RULES);
        assertEquals(10, hearts(NEWBIE));
        assertEquals("newbie shield", store.ledger(NEWBIE, 1, 0).getFirst().note());
    }

    @Test
    void killingAShieldedPlayerMovesNothing() throws SQLException {
        store.placeBounty(ALICE, NEWBIE, 2, RULES);
        KillOutcome kill = store.kill(NEWBIE, ALICE, RULES);
        assertTrue(kill.shielded());
        assertFalse(kill.marked());
        assertNull(kill.bounty());
        assertEquals(10, hearts(NEWBIE));
        assertEquals(8, hearts(ALICE));
        assertEquals(2, store.bountyOn(NEWBIE));
        assertEquals("newbie shield", store.ledger(NEWBIE, 1, 0).getFirst().note());
    }

    @Test
    void shieldedPlayersGainNothingEither() throws SQLException {
        KillOutcome kill = store.kill(ALICE, NEWBIE, RULES);
        assertTrue(kill.shielded());
        assertEquals(10, hearts(ALICE));
        assertEquals(10, hearts(NEWBIE));
    }

    @Test
    void shieldedPlayersCannotPlaceBounties() throws SQLException {
        assertThrows(BountyException.class, () -> store.placeBounty(NEWBIE, ALICE, 1, RULES));
        assertEquals(10, hearts(NEWBIE));
    }

    @Test
    void playtimeWearsTheShieldDown() throws SQLException {
        assertEquals(Duration.ofHours(2), store.spendShield(NEWBIE, Duration.ofHours(1)));
        assertEquals(Duration.ZERO, store.spendShield(NEWBIE, Duration.ofHours(5)));
        store.apply(List.of(HeartRequest.add(NEWBIE, -1, HeartCause.DEATH, "server")), RULES);
        assertEquals(9, hearts(NEWBIE));
    }

    @Test
    void breakingTheShieldIsOneWay() throws SQLException {
        assertTrue(store.breakShield(NEWBIE));
        assertFalse(store.breakShield(NEWBIE));
        assertEquals(Duration.ZERO, store.shieldLeft(NEWBIE));
        assertFalse(store.kill(NEWBIE, ALICE, RULES).shielded());
        assertEquals(9, hearts(NEWBIE));
    }

    @Test
    void theShieldEndsAfterItsDayLimitEvenUnplayed() throws SQLException {
        store.setFeatures(true, Duration.ofDays(3), true, true);
        clock.advance(Duration.ofDays(2));
        assertEquals(THREE_HOURS, store.shieldLeft(NEWBIE));
        clock.advance(Duration.ofDays(1));
        assertEquals(Duration.ZERO, store.shieldLeft(NEWBIE));
        assertFalse(store.kill(NEWBIE, ALICE, RULES).shielded());
    }

    @Test
    void anAdminGrantedShieldGetsAFreshDayLimit() throws SQLException {
        store.setFeatures(true, Duration.ofDays(3), true, true);
        clock.advance(Duration.ofDays(10));
        store.setShield(ALICE, Duration.ofMinutes(30));
        assertEquals(Duration.ofMinutes(30), store.shieldLeft(ALICE));
        clock.advance(Duration.ofDays(3));
        assertEquals(Duration.ZERO, store.shieldLeft(ALICE));
    }

    @Test
    void adminsCanGrantAndChangeHeartsRegardless() throws SQLException {
        store.setShield(ALICE, Duration.ofMinutes(30));
        assertEquals(Duration.ofMinutes(30), store.shieldLeft(ALICE));
        store.apply(List.of(HeartRequest.add(NEWBIE, -2, HeartCause.ADMIN_TAKE, "op")), RULES);
        assertEquals(8, hearts(NEWBIE));
    }
}
