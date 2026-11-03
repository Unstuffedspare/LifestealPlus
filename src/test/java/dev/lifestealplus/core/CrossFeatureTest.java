package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Features meeting each other: a season reset in the middle of everything, and changes that fail part way. */
class CrossFeatureTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ZERO, Duration.ofMinutes(30), 1);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID DAN = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), clock);
        store.setFeatures(true, Duration.ZERO, true, true);
        for (Object[] p : new Object[][] {{ALICE, "Alice"}, {BOB, "Bob"}, {CARA, "Cara"}, {DAN, "Dan"}}) {
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

    @Test
    void aSeasonResetClearsEverythingInFlight() throws SQLException {
        store.formPact(ALICE, BOB);
        store.placeBounty(CARA, ALICE, 3, RULES);
        store.kill(DAN, ALICE, RULES);
        store.apply(List.of(HeartRequest.set(DAN, 0, HeartCause.ADMIN_SET, "op")), RULES);
        store.pledge(BOB, DAN, 2, 6, 2, RULES);
        store.setPersonalCap(ALICE, 30);

        store.archiveSeason("Season 1", Map.of("season.name", "Season 2"));

        // Nothing carries over: no pact, no bounty, no vengeance mark, no pledge, no cap.
        assertEquals(List.of(), store.allPacts());
        assertEquals(List.of(), store.topBounties(10));
        assertEquals(List.of(), store.pledges());
        for (UUID player : List.of(ALICE, BOB, CARA, DAN)) assertTrue(store.find(player).isEmpty());
        assertEquals(10, store.touch(ALICE, "Alice", RULES).hearts(), "a fresh start");
        store.apply(List.of(HeartRequest.add(ALICE, 30, HeartCause.ADMIN_GIVE, "op")), RULES);
        assertEquals(20, hearts(ALICE), "last season's personal cap is gone until the permission sets it again");
        assertEquals(List.of(), store.liveMarks(), "no vengeance marks either");

        // ...but last season's record stays, with the bounty and pact effects in it.
        List<SeasonStanding> past = store.seasonStandings("Season 1");
        assertEquals(4, past.size());
        assertTrue(store.archivedLedgerSize("Season 1") > 0);
    }

    @Test
    void aChangeThatFailsPartWayLeavesNoTrace() throws SQLException {
        int ledger = store.ledgerSize(ALICE);
        assertThrows(UnknownPlayerException.class, () -> store.apply(List.of(
                HeartRequest.add(ALICE, 5, HeartCause.ADMIN_GIVE, "op"),
                HeartRequest.add(NOBODY, 5, HeartCause.ADMIN_GIVE, "op")), RULES));
        assertEquals(10, hearts(ALICE), "Alice's half of it was rolled back");
        assertEquals(ledger, store.ledgerSize(ALICE), "and so was her ledger entry");
    }

    @Test
    void aRefusedBountyOrGiftTakesNothing() throws SQLException {
        assertThrows(RuleException.class, () -> store.placeBounty(ALICE, BOB, 10, RULES));
        assertThrows(RuleException.class, () -> store.giveHearts(ALICE, BOB, 15, 0, RULES));
        assertEquals(10, hearts(ALICE));
        assertEquals(1, store.ledgerSize(ALICE), "just her first join");
    }

    @Test
    void theDatabaseSurvivesBeingOpenedAgainMidSeason() throws SQLException {
        store.formPact(ALICE, BOB);
        store.placeBounty(CARA, DAN, 2, RULES);
        store.kill(DAN, ALICE, RULES);
        store.close();
        store = TestStores.open(dir.resolve("test.db"), clock);
        assertEquals(13, hearts(ALICE), "the kill's heart and the 2-heart bounty");
        assertEquals("Bob", store.pactPartner(ALICE).orElseThrow().name());
        assertEquals(0, store.bountyOn(DAN), "collected before the restart");
        assertEquals(8, hearts(CARA), "Cara paid for it");
    }
}
