package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** Players stake their own hearts on someone's head; whoever kills them takes the pot. */
class BountyTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-01T12:00:00Z"));
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
    void placingABountyEscrowsThePlacersHearts() throws SQLException {
        BountyPlacement placed = store.placeBounty(CARA, BOB, 3, RULES);
        assertEquals(7, hearts(CARA));
        assertEquals(3, placed.totalOnTarget());
        assertEquals(3, store.bountyOn(BOB));
        LedgerEntry row = store.ledger(CARA, 1, 0).getFirst();
        assertEquals(HeartCause.BOUNTY_PLACE, row.cause());
        assertEquals("Bob", row.otherName());
    }

    @Test
    void placerMustKeepAHeart() throws SQLException {
        BountyException e = assertThrows(BountyException.class, () -> store.placeBounty(CARA, BOB, 10, RULES));
        assertEquals(10, hearts(CARA));
        assertEquals(0, store.bountyOn(BOB));
        assertNotNull(e.getMessage());
    }

    @Test
    void noBountiesOnYourselfOrTheEliminated() throws SQLException {
        assertThrows(BountyException.class, () -> store.placeBounty(CARA, CARA, 1, RULES));
        store.apply(List.of(HeartRequest.set(BOB, 0, HeartCause.ADMIN_SET, "op")), RULES);
        assertThrows(BountyException.class, () -> store.placeBounty(CARA, BOB, 1, RULES));
        assertThrows(BountyException.class, () -> store.placeBounty(CARA, ALICE, 0, RULES));
        assertEquals(10, hearts(CARA));
    }

    @Test
    void theKillerTakesThePotOnTopOfTheKill() throws SQLException {
        store.placeBounty(CARA, BOB, 2, RULES);
        store.placeBounty(CARA, BOB, 1, RULES);
        KillOutcome kill = store.kill(BOB, ALICE, RULES);

        assertNotNull(kill.bounty());
        assertEquals(3, kill.bounty().change().delta());
        assertEquals(0, kill.bounty().remaining());
        assertEquals(14, hearts(ALICE));
        assertEquals(9, hearts(BOB));
        assertEquals(0, store.bountyOn(BOB));
        LedgerEntry row = store.ledger(ALICE, 1, 0).getFirst();
        assertEquals(HeartCause.BOUNTY_CLAIM, row.cause());
        assertEquals("Bob", row.otherName());
        assertEquals(kill.victim().eventId(), row.eventId());
    }

    @Test
    void aCappedKillerLeavesTheRestOnTheTarget() throws SQLException {
        store.apply(List.of(HeartRequest.set(ALICE, 18, HeartCause.ADMIN_SET, "op")), RULES);
        store.placeBounty(CARA, BOB, 4, RULES);
        KillOutcome kill = store.kill(BOB, ALICE, RULES);
        assertEquals(20, hearts(ALICE));
        assertEquals(1, kill.bounty().change().delta());
        assertEquals(3, kill.bounty().remaining());
        assertEquals(3, store.bountyOn(BOB));
    }

    @Test
    void bountiesPayEvenOnARepeatKill() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        store.placeBounty(CARA, BOB, 2, RULES);
        KillOutcome repeat = store.kill(BOB, ALICE, RULES);
        assertEquals(true, repeat.repeat());
        assertEquals(2, repeat.bounty().change().delta());
        assertEquals(13, hearts(ALICE));
    }

    @Test
    void killsWithNoBountyClaimNothing() throws SQLException {
        assertNull(store.kill(BOB, ALICE, RULES).bounty());
    }

    @Test
    void topBountiesAreSummedPerTarget() throws SQLException {
        store.placeBounty(CARA, BOB, 2, RULES);
        store.placeBounty(ALICE, BOB, 1, RULES);
        store.placeBounty(BOB, CARA, 1, RULES);
        List<BountyTotal> top = store.topBounties(10);
        assertEquals(2, top.size());
        assertEquals("Bob", top.getFirst().targetName());
        assertEquals(3, top.getFirst().hearts());
    }

    @Test
    void adminClearRefundsEachPlacer() throws SQLException {
        store.placeBounty(CARA, BOB, 2, RULES);
        store.placeBounty(ALICE, BOB, 1, RULES);
        List<HeartChange> refunds = store.clearBounties(BOB, "op", RULES);
        assertEquals(2, refunds.size());
        assertEquals(10, hearts(CARA));
        assertEquals(10, hearts(ALICE));
        assertEquals(0, store.bountyOn(BOB));
        assertEquals(HeartCause.BOUNTY_REFUND, store.ledger(CARA, 1, 0).getFirst().cause());
    }
}
