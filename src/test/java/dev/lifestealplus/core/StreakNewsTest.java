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

/** Kill streaks, the shutdown bonus, and the news players catch up on when they come back. */
class StreakNewsTest {

    /** No repeat-kill window or vengeance, so the same pair can trade kills freely. */
    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ZERO, Duration.ZERO, 0);
    private static final StreakRules STREAKS = new StreakRules(true, List.of(3, 5), 3, 1);
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
        store.setStreaks(STREAKS);
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

    private List<String> newsKinds() throws SQLException {
        return store.latestNews(50).stream().map(NewsItem::kind).toList();
    }

    @Test
    void killsThatTakeAHeartBuildAStreak() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        store.kill(BOB, ALICE, RULES);
        KillOutcome third = store.kill(BOB, ALICE, RULES);
        assertEquals(3, third.killerStreak());
        assertEquals(new PlayerStreak("Alice", 3, 3), store.streak(ALICE).orElseThrow());
        NewsItem news = store.latestNews(1).getFirst();
        assertEquals(NewsItem.STREAK, news.kind());
        assertEquals("Alice", news.player());
        assertEquals(3, news.amount());
    }

    @Test
    void endingALongStreakTakesTheShutdownBonus() throws SQLException {
        for (int i = 0; i < 3; i++) store.kill(BOB, ALICE, RULES);
        KillOutcome shutdown = store.kill(ALICE, CARA, RULES);
        assertEquals(3, shutdown.endedStreak());
        assertTrue(shutdown.shutdown());
        assertEquals(11, hearts(ALICE), "13 after three kills, minus 1 per kill and 1 shutdown bonus");
        assertEquals(12, hearts(CARA));
        assertEquals("streak shutdown", store.ledger(CARA, 1, 0).getFirst().note());
        assertEquals(new PlayerStreak("Alice", 0, 3), store.streak(ALICE).orElseThrow());
        assertEquals(1, store.streak(CARA).orElseThrow().current());
        assertTrue(newsKinds().contains(NewsItem.STREAK_ENDED));
    }

    @Test
    void shortStreaksEndQuietly() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        store.kill(BOB, ALICE, RULES);
        store.apply(List.of(HeartRequest.add(ALICE, -1, HeartCause.DEATH, "server")), RULES);
        assertEquals(0, store.streak(ALICE).orElseThrow().current());
        KillOutcome next = store.kill(ALICE, CARA, RULES);
        assertEquals(0, next.endedStreak());
        assertFalse(next.shutdown());
        assertFalse(newsKinds().contains(NewsItem.STREAK_ENDED));
    }

    @Test
    void repeatKillsDontCount() throws SQLException {
        HeartRules repeats = new HeartRules(10, 20, 3, 1, Duration.ofHours(6), Duration.ZERO, 0);
        store.kill(BOB, ALICE, repeats);
        assertEquals(1, store.kill(BOB, ALICE, repeats).killerStreak());
    }

    @Test
    void streaksOffCountNothing() throws SQLException {
        store.setStreaks(StreakRules.OFF);
        for (int i = 0; i < 3; i++) assertEquals(0, store.kill(BOB, ALICE, RULES).killerStreak());
        assertFalse(store.kill(ALICE, CARA, RULES).shutdown());
    }

    @Test
    void eliminationsAndRevivalsAreNews() throws SQLException {
        store.apply(List.of(HeartRequest.set(BOB, 1, HeartCause.ADMIN_SET, "op")), RULES);
        store.kill(BOB, CARA, RULES);
        store.apply(List.of(HeartRequest.set(BOB, 3, HeartCause.REVIVE, "op")), RULES);
        List<NewsItem> news = store.latestNews(10);
        assertEquals(List.of(NewsItem.REVIVED, NewsItem.ELIMINATED_BY), news.stream().map(NewsItem::kind).toList());
        assertEquals("Cara", news.get(1).other());
        assertEquals(3, news.get(0).amount());
    }

    @Test
    void theRecapCoversTimeAwayOnly() throws SQLException {
        store.addNews(NewsItem.BLOOD_MOON, null, null, 0);
        assertTrue(store.recap(ALICE, Duration.ofHours(1), 5).isEmpty(), "never left, so nothing to catch up on");

        store.markLeft(ALICE);
        clock.advance(Duration.ofHours(2));
        store.addNews(NewsItem.AIRDROP, "Bob", null, 0);
        store.apply(List.of(HeartRequest.set(BOB, 0, HeartCause.ADMIN_SET, "op")), RULES);

        Recap recap = store.recap(ALICE, Duration.ofHours(1), 1).orElseThrow();
        assertEquals(Duration.ofHours(2), recap.away());
        assertEquals(2, recap.total());
        assertEquals(List.of(NewsItem.ELIMINATED), recap.items().stream().map(NewsItem::kind).toList());
        assertTrue(store.recap(ALICE, Duration.ofHours(3), 5).isEmpty(), "not away long enough");
    }

    @Test
    void oldNewsIsPruned() throws SQLException {
        store.addNews(NewsItem.BLOOD_MOON, null, null, 0);
        clock.advance(Duration.ofDays(15));
        store.addNews(NewsItem.AIRDROP, null, null, 0);
        assertEquals(1, store.pruneNews(Duration.ofDays(14)));
        assertEquals(List.of(NewsItem.AIRDROP), newsKinds());
    }

    @Test
    void importsArentNews() throws SQLException {
        store.importPlayers(List.of(new LifestealZImport.Planned(BOB, "Bob", 0, true, false)), "LifeStealZ", RULES);
        assertEquals(List.of(), newsKinds());
    }
}
