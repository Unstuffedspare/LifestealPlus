package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

/** Players' own heart caps (from permissions), and reading news in the order it was written. */
class PersonalCapTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ZERO);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = new HeartStore(dir.resolve("test.db"), clock);
        store.touch(ALICE, "Alice", RULES);
        store.touch(BOB, "Bob", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    private int hearts(UUID uuid) throws SQLException {
        return store.find(uuid).orElseThrow().hearts();
    }

    private void give(UUID player, int amount) throws SQLException {
        HeartCause cause = amount >= 0 ? HeartCause.ADMIN_GIVE : HeartCause.ADMIN_TAKE;
        store.apply(List.of(HeartRequest.add(player, amount, cause, "op")), RULES);
    }

    @Test
    void aPlayerWithTheirOwnCapCanGoPastTheServerCap() throws SQLException {
        store.setPersonalCap(ALICE, 25);
        give(ALICE, 30);
        give(BOB, 30);
        assertEquals(25, hearts(ALICE));
        assertEquals(20, hearts(BOB));
    }

    @Test
    void killsRespectTheKillersOwnCap() throws SQLException {
        store.setPersonalCap(ALICE, 21);
        give(ALICE, 10);
        store.kill(BOB, ALICE, RULES);
        assertEquals(21, hearts(ALICE));
        store.kill(BOB, ALICE, RULES);
        assertEquals(21, hearts(ALICE), "at her own cap, she takes the heart but doesn't gain it");
        assertEquals(8, hearts(BOB));
    }

    @Test
    void losingTheCapNeverTakesHeartsAway() throws SQLException {
        store.setPersonalCap(ALICE, 25);
        give(ALICE, 15);
        store.setPersonalCap(ALICE, null);
        assertEquals(25, hearts(ALICE));
        give(ALICE, 1);
        assertEquals(25, hearts(ALICE), "above the server cap, she gains nothing more");
    }

    @Test
    void aCapBelowTheStartingHeartsIsRaisedToThem() throws SQLException {
        store.setPersonalCap(ALICE, 2);
        give(ALICE, -5);
        give(ALICE, 20);
        assertEquals(10, hearts(ALICE));
    }

    @Test
    void newsCanBeReadOnFromWhereItWasLeft() throws SQLException {
        assertEquals(0, store.lastNewsId());
        store.addNews(NewsItem.BLOOD_MOON, null, null, 0);
        long first = store.lastNewsId();
        store.addNews(NewsItem.AIRDROP, "Bob", null, 0);
        store.addNews(NewsItem.FINALE, null, null, 0);
        var after = store.newsAfter(first, 10);
        assertEquals(List.of(NewsItem.AIRDROP, NewsItem.FINALE), after.stream().map(e -> e.getValue().kind()).toList());
        assertEquals(store.lastNewsId(), after.getLast().getKey());
        assertEquals(1, store.newsAfter(first, 1).size());
    }
}
