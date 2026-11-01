package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** Withdrawing hearts, the rising cap, rifts, revival pledges and Blood Pacts. */
class MoreFeaturesTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID DAN = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-02T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = new HeartStore(dir.resolve("test.db"), clock);
        for (var p : List.of(new Object[] {ALICE, "Alice"}, new Object[] {BOB, "Bob"}, new Object[] {CARA, "Cara"},
                new Object[] {DAN, "Dan"})) {
            store.touch((UUID) p[0], (String) p[1], RULES);
        }
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    private int hearts(UUID uuid) throws SQLException {
        return store.find(uuid).orElseThrow().hearts();
    }

    private void eliminate(UUID uuid) throws SQLException {
        store.apply(List.of(HeartRequest.set(uuid, 0, HeartCause.ADMIN_SET, "op")), RULES);
        store.drainSideEffects();
    }

    @Test
    void withdrawingKeepsAtLeastOneHeart() throws SQLException {
        assertEquals(-3, store.withdraw(ALICE, 3, RULES).delta());
        assertEquals(7, hearts(ALICE));
        assertThrows(RuleException.class, () -> store.withdraw(ALICE, 7, RULES));
        assertEquals(7, hearts(ALICE));
        assertEquals(HeartCause.HEART_WITHDRAW, store.ledger(ALICE, 1, 0).getFirst().cause());
    }

    @Test
    void theCapFollowsTheScheduleThenHolds() {
        List<Integer> schedule = List.of(15, 20, 25);
        assertEquals(15, HeartRules.capForWeek(schedule, 20, 0));
        assertEquals(20, HeartRules.capForWeek(schedule, 20, 1));
        assertEquals(25, HeartRules.capForWeek(schedule, 20, 9));
        assertEquals(20, HeartRules.capForWeek(List.of(), 20, 3));
        assertEquals(15, RULES.withMaxHearts(15).maxHearts());
    }

    @Test
    void aRiftCostsTheRescuerAndRevivesTheTarget() throws SQLException {
        eliminate(BOB);
        List<HeartChange> rift = store.openRift(ALICE, BOB, 6, RULES);
        assertEquals(4, hearts(ALICE));
        assertTrue(rift.get(1).revivedNow());
        assertEquals(3, hearts(BOB));
        assertEquals("Alice", rift.get(1).otherName());
    }

    @Test
    void riftsNeedAnEliminatedTargetAndEnoughHearts() throws SQLException {
        assertThrows(RuleException.class, () -> store.openRift(ALICE, BOB, 6, RULES));
        eliminate(BOB);
        store.apply(List.of(HeartRequest.set(ALICE, 6, HeartCause.ADMIN_SET, "op")), RULES);
        assertThrows(RuleException.class, () -> store.openRift(ALICE, BOB, 6, RULES));
        assertEquals(6, hearts(ALICE));
        assertEquals(0, hearts(BOB));
    }

    @Test
    void pledgesReviveOnceEnoughPeopleChipIn() throws SQLException {
        eliminate(DAN);
        PledgeOutcome first = store.pledge(ALICE, DAN, 4, 6, 3, RULES);
        assertNull(first.revive());
        assertEquals(4, first.total().hearts());
        PledgeOutcome second = store.pledge(BOB, DAN, 2, 6, 3, RULES);
        assertNull(second.revive(), "6 hearts but only 2 voters");
        PledgeOutcome third = store.pledge(CARA, DAN, 1, 6, 3, RULES);
        assertNotNull(third.revive());
        assertEquals(3, hearts(DAN));
        assertEquals(List.of(6, 8, 9), List.of(hearts(ALICE), hearts(BOB), hearts(CARA)));
        assertTrue(store.pledges().isEmpty());
        assertTrue(store.drainSideEffects().isEmpty(), "spent pledges aren't refunded");
    }

    @Test
    void pledgesAreRefundedIfTheTargetComesBackAnotherWay() throws SQLException {
        eliminate(DAN);
        store.pledge(ALICE, DAN, 2, 6, 3, RULES);
        store.pledge(BOB, DAN, 1, 6, 3, RULES);
        store.drainSideEffects();
        store.apply(List.of(HeartRequest.set(DAN, 3, HeartCause.REVIVE, "op")), RULES);
        List<HeartChange> refunds = store.drainSideEffects();
        assertEquals(2, refunds.size());
        assertTrue(refunds.stream().allMatch(c -> c.cause() == HeartCause.REVIVAL_REFUND));
        assertEquals(List.of(10, 10), List.of(hearts(ALICE), hearts(BOB)));
    }

    @Test
    void aPledgeCanBeTakenBack() throws SQLException {
        eliminate(DAN);
        store.pledge(ALICE, DAN, 3, 6, 3, RULES);
        store.cancelPledge(ALICE, DAN, RULES);
        assertEquals(10, hearts(ALICE));
        assertTrue(store.pledges().isEmpty());
        assertThrows(RuleException.class, () -> store.cancelPledge(ALICE, DAN, RULES));
    }

    @Test
    void pactPartnersShareKillGains() throws SQLException {
        store.formPact(ALICE, BOB);
        store.kill(CARA, ALICE, RULES);
        List<HeartChange> shared = store.drainSideEffects();
        assertEquals(1, shared.size());
        assertEquals(HeartCause.PACT_SHARE, shared.getFirst().cause());
        assertEquals("Alice", shared.getFirst().otherName());
        assertEquals(List.of(11, 11, 9), List.of(hearts(ALICE), hearts(BOB), hearts(CARA)));
    }

    @Test
    void whenOneDiesBothLoseAHeart() throws SQLException {
        store.formPact(ALICE, BOB);
        store.apply(List.of(HeartRequest.add(ALICE, -1, HeartCause.DEATH, "server")), RULES);
        assertEquals(1, store.drainSideEffects().size());
        assertEquals(List.of(9, 9), List.of(hearts(ALICE), hearts(BOB)));
    }

    @Test
    void killingYourOwnPartnerSharesNothing() throws SQLException {
        store.formPact(ALICE, BOB);
        store.kill(BOB, ALICE, RULES);
        assertTrue(store.drainSideEffects().isEmpty());
        assertEquals(List.of(11, 9), List.of(hearts(ALICE), hearts(BOB)));
    }

    @Test
    void eliminationEndsThePact() throws SQLException {
        store.formPact(ALICE, BOB);
        store.apply(List.of(HeartRequest.set(BOB, 1, HeartCause.ADMIN_SET, "op")), RULES);
        store.apply(List.of(HeartRequest.add(BOB, -1, HeartCause.DEATH, "server")), RULES);
        store.drainSideEffects();
        assertEquals(9, hearts(ALICE));
        assertTrue(store.pactPartner(ALICE).isEmpty());
    }

    @Test
    void breakingAPactStartsACooldown() throws SQLException {
        store.formPact(ALICE, BOB);
        assertThrows(RuleException.class, () -> store.formPact(ALICE, CARA));
        assertEquals("Bob", store.breakPact(ALICE, Duration.ofHours(24)).name());
        assertThrows(RuleException.class, () -> store.formPact(ALICE, CARA));
        store.formPact(BOB, CARA);
        clock.advance(Duration.ofHours(25));
        store.formPact(ALICE, DAN);
        assertEquals("Dan", store.pactPartner(ALICE).orElseThrow().name());
    }

    @Test
    void shieldedPlayersCannotMakePacts() throws SQLException {
        store.setShield(CARA, Duration.ofHours(1));
        assertThrows(RuleException.class, () -> store.formPact(ALICE, CARA));
    }

    @Test
    void aSeasonResetEndsPactsAndPledges() throws SQLException {
        store.formPact(ALICE, BOB);
        eliminate(DAN);
        store.pledge(CARA, DAN, 2, 6, 3, RULES);
        store.archiveSeason("Season 1");
        store.touch(ALICE, "Alice", RULES);
        assertTrue(store.pactPartner(ALICE).isEmpty());
        assertTrue(store.pledges().isEmpty());
    }
}
