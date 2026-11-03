package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class PurgatoryProgressTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant START = Instant.parse("2026-11-01T12:00:00Z");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(START);
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), clock);
        store.touch(BOB, "Bob", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    private void eliminate() throws SQLException {
        store.apply(List.of(HeartRequest.set(BOB, 0, HeartCause.ADMIN_SET, "op")), RULES);
    }

    @Test
    void newPlayersHaveNoProgress() throws SQLException {
        PurgatoryProgress progress = store.progress(BOB);
        assertEquals(0, progress.fragments());
        assertNull(progress.lastWin());
    }

    @Test
    void winningATrialEarnsAFragment() throws SQLException {
        eliminate();
        TrialReward reward = store.awardFragment(BOB, 3, RULES);
        assertEquals(1, reward.fragments());
        assertNull(reward.revive());
        assertEquals(START, store.progress(BOB).lastWin());
    }

    @Test
    void lastFragmentRevivesAndClearsProgress() throws SQLException {
        eliminate();
        store.awardFragment(BOB, 3, RULES);
        store.awardFragment(BOB, 3, RULES);
        clock.advance(Duration.ofMinutes(30));
        TrialReward reward = store.awardFragment(BOB, 3, RULES);

        assertEquals(3, reward.fragments());
        assertNotNull(reward.revive());
        assertTrue(reward.revive().revivedNow());
        assertEquals(RULES.reviveHearts(), store.find(BOB).orElseThrow().hearts());
        LedgerEntry row = store.ledger(BOB, 1, 0).getFirst();
        assertEquals(HeartCause.REVIVE, row.cause());
        assertEquals("purgatory", row.actor());
        assertEquals(0, store.progress(BOB).fragments());
    }

    @Test
    void livingPlayersEarnNothing() throws SQLException {
        TrialReward reward = store.awardFragment(BOB, 3, RULES);
        assertEquals(0, reward.fragments());
        assertNull(reward.revive());
        assertEquals(10, store.find(BOB).orElseThrow().hearts());
    }

    @Test
    void progressFromAnEarlierEliminationDoesNotCarryOver() throws SQLException {
        eliminate();
        store.awardFragment(BOB, 3, RULES);
        store.awardFragment(BOB, 3, RULES);
        store.apply(List.of(HeartRequest.set(BOB, 3, HeartCause.REVIVE, "op")), RULES);
        eliminate();
        assertEquals(0, store.progress(BOB).fragments());
        assertEquals(1, store.awardFragment(BOB, 3, RULES).fragments());
    }
}
