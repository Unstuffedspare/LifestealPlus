package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Event multipliers and cap boosts, playtime, revival rules, and hearts changing hands. */
class EventsEconomyTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ZERO);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
    private HeartStore store;
    private HeartService service;

    @BeforeEach
    void open() throws SQLException {
        store = new HeartStore(dir.resolve("test.db"), clock);
        service = new HeartService(store, RULES);
        store.touch(ALICE, "Alice", RULES);
        store.touch(BOB, "Bob", RULES);
        store.touch(CARA, "Cara", RULES);
    }

    @AfterEach
    void close() throws Exception {
        service.close();
    }

    private int hearts(UUID uuid) throws SQLException {
        return store.find(uuid).orElseThrow().hearts();
    }

    private void set(UUID player, int hearts) throws SQLException {
        store.apply(List.of(HeartRequest.set(player, hearts, HeartCause.ADMIN_SET, "op")), RULES);
    }

    // ---- Events

    @Test
    void theBiggestMultiplierWinsAndEachEventCleansUpItsOwn() {
        service.setKillMultiplier("blood-moon", 2);
        service.setKillMultiplier("double-hearts", 3);
        assertEquals(3, service.killMultiplier());
        service.setKillMultiplier("double-hearts", 1);
        assertEquals(2, service.killMultiplier(), "the Blood Moon is still up");
        service.setKillMultiplier("blood-moon", 1);
        assertEquals(1, service.killMultiplier());
    }

    @Test
    void aCapBoostRaisesEveryCapAndLeavesTheHeartsBehind() throws SQLException {
        store.setPersonalCap(BOB, 25);
        service.setCapBonus("cap-boost", 5);
        assertEquals(25, service.rules().maxHearts());
        store.apply(List.of(HeartRequest.add(ALICE, 30, HeartCause.ADMIN_GIVE, "op")), service.rules());
        store.apply(List.of(HeartRequest.add(BOB, 30, HeartCause.ADMIN_GIVE, "op")), service.rules());
        assertEquals(25, hearts(ALICE));
        assertEquals(30, hearts(BOB), "his own cap is boosted too");
        service.setCapBonus("cap-boost", 0);
        assertEquals(20, service.rules().maxHearts());
        assertEquals(25, hearts(ALICE), "nobody loses what they gained");
    }

    // ---- Milestones

    @Test
    void playtimeRewardsComeEachTimeAMultipleIsPassed() throws SQLException {
        Duration every = Duration.ofHours(2);
        assertEquals(0, store.addPlaytime(ALICE, Duration.ofMinutes(119), every));
        assertEquals(1, store.addPlaytime(ALICE, Duration.ofMinutes(1), every));
        assertEquals(0, store.addPlaytime(ALICE, Duration.ofMinutes(60), every));
        assertEquals(2, store.addPlaytime(ALICE, Duration.ofHours(4), every), "a long gap pays out what it crossed");
        assertEquals(0, store.addPlaytime(ALICE, Duration.ofMinutes(1), Duration.ZERO));
    }

    // ---- Revival

    @Test
    void aFreshEliminationWaitsOutTheCooldown() throws SQLException {
        store.setRevivalRules(Duration.ofMinutes(10), 0);
        set(BOB, 0);
        assertThrows(RuleException.class, () -> store.openRift(ALICE, BOB, 6, RULES));
        assertThrows(RuleException.class, () -> store.pledge(ALICE, BOB, 1, 6, 1, RULES));
        assertThrows(RuleException.class, () -> store.reviveWithItem(ALICE, BOB, RULES));
        clock.advance(Duration.ofMinutes(11));
        assertEquals(3, store.reviveWithItem(ALICE, BOB, RULES).after());
        assertEquals(HeartCause.REVIVAL_ITEM, store.ledger(BOB, 1, 0).getFirst().cause());
    }

    @Test
    void eachEliminationMakesComingBackDearer() throws SQLException {
        store.setRevivalRules(Duration.ZERO, 2);
        set(BOB, 0);
        assertEquals(6, store.revivalCost(BOB, 6), "the first time costs the usual");
        set(BOB, 3);
        set(BOB, 0);
        assertEquals(8, store.revivalCost(BOB, 6));
        store.openRift(ALICE, BOB, 6, RULES);
        assertEquals(2, hearts(ALICE), "10 less the 8-heart rift");
        assertEquals(List.of(), store.eliminatedPlayers(10));
    }

    @Test
    void theCharmListsWhoIsOut() throws SQLException {
        set(BOB, 0);
        assertEquals(List.of("Bob"), store.eliminatedPlayers(10).stream().map(PlayerHearts::name).toList());
        assertThrows(RuleException.class, () -> store.reviveWithItem(ALICE, CARA, RULES), "Cara isn't out");
    }

    // ---- Trading

    @Test
    void heartsChangeHandsLessTheTax() throws SQLException {
        TradeOutcome gift = store.giveHearts(ALICE, BOB, 4, 25, RULES);
        assertEquals(1, gift.tax());
        assertEquals(6, hearts(ALICE));
        assertEquals(13, hearts(BOB));
        assertEquals(HeartCause.TRADE_RECEIVED, store.ledger(BOB, 1, 0).getFirst().cause());
        assertFalse(gift.linked());
    }

    @Test
    void giftsFollowTheRules() throws SQLException {
        assertThrows(RuleException.class, () -> store.giveHearts(ALICE, BOB, 10, 0, RULES), "Alice must keep one");
        assertThrows(RuleException.class, () -> store.giveHearts(ALICE, ALICE, 1, 0, RULES));
        set(BOB, 19);
        assertThrows(RuleException.class, () -> store.giveHearts(ALICE, BOB, 2, 0, RULES), "Bob has room for one");
        assertEquals(10, hearts(ALICE), "a refused gift takes nothing");
        set(CARA, 0);
        assertThrows(RuleException.class, () -> store.giveHearts(ALICE, CARA, 1, 0, RULES), "gifts don't revive");
    }

    @Test
    void altsTradingIsFlagged() throws SQLException {
        store.setFeatures(true, Duration.ZERO, true, true);
        store.recordLogin(ALICE, "hash-1");
        store.recordLogin(BOB, "hash-1");
        assertTrue(store.giveHearts(ALICE, BOB, 1, 0, RULES).linked());
    }
}
