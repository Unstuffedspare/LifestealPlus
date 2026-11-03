package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeartStoreTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));
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

    @Test
    void firstJoinGivesStartingHeartsAndLogsIt() throws SQLException {
        assertEquals(10, hearts(ALICE));
        List<LedgerEntry> log = store.ledger(ALICE, 10, 0);
        assertEquals(1, log.size());
        assertEquals(HeartCause.FIRST_JOIN, log.getFirst().cause());
        assertEquals(0, log.getFirst().before());
        assertEquals(10, log.getFirst().after());
    }

    @Test
    void rejoinKeepsHeartsAndUpdatesName() throws SQLException {
        store.apply(List.of(HeartRequest.add(ALICE, -4, HeartCause.ADMIN_TAKE, "op")), RULES);
        store.touch(ALICE, "Alice2", RULES);
        assertEquals(6, hearts(ALICE));
        assertEquals(ALICE, store.findByName("alice2").orElseThrow().uuid());
        assertEquals(2, store.ledgerSize(ALICE));
    }

    @Test
    void killMovesOneHeartAsOneEvent() throws SQLException {
        KillOutcome kill = store.kill(BOB, ALICE, RULES);
        assertEquals(9, hearts(BOB));
        assertEquals(11, hearts(ALICE));
        assertEquals(kill.victim().eventId(), kill.killer().eventId());

        LedgerEntry loss = store.ledger(BOB, 1, 0).getFirst();
        assertEquals(HeartCause.PVP_DEATH, loss.cause());
        assertEquals(ALICE, loss.otherUuid());
        assertEquals("Alice", loss.otherName());
        LedgerEntry gain = store.ledger(ALICE, 1, 0).getFirst();
        assertEquals(HeartCause.PVP_KILL, gain.cause());
        assertEquals("Bob", gain.otherName());
    }

    @Test
    void killerAtCapGainsNothingButVictimStillLoses() throws SQLException {
        store.apply(List.of(HeartRequest.set(ALICE, 20, HeartCause.ADMIN_SET, "op")), RULES);
        store.kill(BOB, ALICE, RULES);
        assertEquals(20, hearts(ALICE));
        assertEquals(9, hearts(BOB));
        LedgerEntry gain = store.ledger(ALICE, 1, 0).getFirst();
        assertEquals(1, gain.requested());
        assertEquals("capped", gain.note());
    }

    @Test
    void killerGainsOnlyWhatVictimActuallyHad() throws SQLException {
        HeartRules twoPerKill = new HeartRules(10, 20, 3, 2, Duration.ofHours(6));
        store.apply(List.of(HeartRequest.set(BOB, 1, HeartCause.ADMIN_SET, "op")), twoPerKill);
        KillOutcome kill = store.kill(BOB, ALICE, twoPerKill);
        assertTrue(kill.victim().eliminatedNow());
        assertEquals(0, hearts(BOB));
        assertEquals(11, hearts(ALICE));
    }

    @Test
    void eliminatedPlayersIgnoreLossesAndOnlyReviveCausesBringThemBack() throws SQLException {
        store.apply(List.of(HeartRequest.set(BOB, 0, HeartCause.ADMIN_SET, "op")), RULES);
        HeartChange death = store.apply(List.of(HeartRequest.add(BOB, -1, HeartCause.DEATH, "server")), RULES).getFirst();
        assertEquals(0, death.after());
        assertEquals("eliminated", store.ledger(BOB, 1, 0).getFirst().note());

        HeartChange revive = store.apply(List.of(HeartRequest.set(BOB, RULES.reviveHearts(), HeartCause.REVIVE, "op")), RULES).getFirst();
        assertTrue(revive.revivedNow());
        assertEquals(3, hearts(BOB));
    }

    @Test
    void setIsClampedToTheCap() throws SQLException {
        store.apply(List.of(HeartRequest.set(ALICE, 99, HeartCause.ADMIN_SET, "op")), RULES);
        assertEquals(20, hearts(ALICE));
    }

    @Test
    void loweringTheCapDoesNotStripHeartsOnTheNextGain() throws SQLException {
        store.apply(List.of(HeartRequest.set(ALICE, 20, HeartCause.ADMIN_SET, "op")), RULES);
        HeartRules lowerCap = new HeartRules(10, 15, 3, 1, Duration.ofHours(6));
        store.kill(BOB, ALICE, lowerCap);
        assertEquals(20, hearts(ALICE));
        store.apply(List.of(HeartRequest.add(ALICE, -1, HeartCause.DEATH, "server")), lowerCap);
        assertEquals(19, hearts(ALICE));
    }

    @Test
    void ledgerIsNewestFirstAndPaged() throws SQLException {
        for (int i = 0; i < 5; i++) {
            store.apply(List.of(HeartRequest.add(ALICE, -1, HeartCause.DEATH, "server")), RULES);
        }
        assertEquals(6, store.ledgerSize(ALICE));
        List<LedgerEntry> page1 = store.ledger(ALICE, 2, 0);
        assertEquals(5, page1.get(0).after());
        assertEquals(6, page1.get(1).after());
        List<LedgerEntry> page3 = store.ledger(ALICE, 2, 4);
        assertEquals(HeartCause.FIRST_JOIN, page3.get(1).cause());
        assertNull(page1.get(0).note());
    }

    @Test
    void unknownPlayerIsRejectedAndNothingIsWritten() throws SQLException {
        UUID stranger = UUID.randomUUID();
        assertThrows(UnknownPlayerException.class, () -> store.apply(List.of(
                HeartRequest.add(ALICE, -1, HeartCause.ADMIN_TAKE, "op"),
                HeartRequest.add(stranger, 1, HeartCause.ADMIN_GIVE, "op")), RULES));
        assertEquals(10, hearts(ALICE));
        assertEquals(1, store.ledgerSize(ALICE));
    }

    @Test
    void dataSurvivesReopening() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        store.close();
        store = TestStores.open(dir.resolve("test.db"), Clock.systemUTC());
        assertEquals(11, hearts(ALICE));
        assertEquals(2, store.ledgerSize(ALICE));
    }
}
