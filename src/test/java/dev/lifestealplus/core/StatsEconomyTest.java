package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** /stats and the dashboard's heart economy, both worked out from the ledger. */
class StatsEconomyTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ZERO);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = new HeartStore(dir.resolve("test.db"), clock);
        store.setStreaks(new StreakRules(true, List.of(3), 0, 0));
        store.touch(ALICE, "Alice", RULES);
        store.touch(BOB, "Bob", RULES);
        store.touch(CARA, "Cara", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    /** Alice kills Bob twice, collects a bounty, dies to lava; Cara is taken out by an admin and revived. */
    private void playASeason() throws SQLException {
        store.placeBounty(CARA, BOB, 2, RULES);
        store.kill(BOB, ALICE, RULES);
        store.kill(BOB, ALICE, RULES);
        store.apply(List.of(HeartRequest.add(ALICE, -1, HeartCause.DEATH, "server")), RULES);
        store.apply(List.of(HeartRequest.set(CARA, 0, HeartCause.ADMIN_SET, "op")), RULES);
        store.apply(List.of(HeartRequest.set(CARA, 3, HeartCause.REVIVE, "op")), RULES);
    }

    @Test
    void aPlayersSeasonRecord() throws SQLException {
        playASeason();
        PlayerStats alice = store.stats("alice", null).orElseThrow();
        assertEquals(new PlayerStats("Alice", "Season 1", 2, 1, 2, 1, 2, 0, 0, 2), alice);
        PlayerStats bob = store.stats("Bob", null).orElseThrow();
        assertEquals(0, bob.kills());
        assertEquals(2, bob.deaths());
        assertEquals(2, bob.heartsLost());
        PlayerStats cara = store.stats("Cara", null).orElseThrow();
        assertEquals(1, cara.eliminations());
        assertEquals(1, cara.comebacks());
        assertTrue(store.stats("Nobody", null).isEmpty());
    }

    @Test
    void pastSeasonsKeepTheirRecords() throws SQLException {
        playASeason();
        store.archiveSeason("Season 1", Map.of("season.name", "Season 2"));
        PlayerStats past = store.stats("Alice", "season 1").orElseThrow();
        assertEquals(2, past.kills());
        assertEquals(-1, past.bestStreak(), "streaks aren't kept in the archive");
        assertTrue(store.stats("Alice", null).isEmpty(), "she hasn't joined Season 2 yet");
        assertTrue(store.stats("Alice", "Season 9").isEmpty());
    }

    @Test
    void theEconomyShowsWhereHeartsCameFrom() throws SQLException {
        playASeason();
        clock.advance(Duration.ofDays(8));
        store.apply(List.of(HeartRequest.add(BOB, 2, HeartCause.HEART_ITEM, "Bob")), RULES);
        EconomyReport economy = store.economy(Duration.ofDays(7));
        assertEquals(3, economy.alive());
        assertEquals(0, economy.eliminated());
        assertEquals(13 + 10 + 3, economy.heartsInPlay(), "Alice with Cara's bounty, Bob after his Heart, Cara revived");
        assertEquals(Map.of(HeartCause.HEART_ITEM, 2), economy.gained(), "only the last week counts");
        assertEquals(2, economy.net());
    }
}
