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

/** Alt-account links, flagged kills between them, and archiving a season before a fresh start. */
class AdminToolsTest {

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
        store = TestStores.open(dir.resolve("test.db"), clock);
        store.touch(ALICE, "Alice", RULES);
        store.touch(BOB, "Bob", RULES);
        store.touch(CARA, "Cara", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    @Test
    void accountsFromTheSameAddressAreLinked() throws SQLException {
        store.recordLogin(ALICE, "hash-1");
        store.recordLogin(BOB, "hash-1");
        store.recordLogin(BOB, "hash-2");
        store.recordLogin(CARA, "hash-3");
        assertEquals(List.of("Bob"), store.linkedAccounts(ALICE).stream().map(PlayerHearts::name).toList());
        assertEquals(List.of("Alice"), store.linkedAccounts(BOB).stream().map(PlayerHearts::name).toList());
        assertTrue(store.linkedAccounts(CARA).isEmpty());
    }

    @Test
    void killsBetweenLinkedAccountsAreFlagged() throws SQLException {
        store.recordLogin(ALICE, "hash-1");
        store.recordLogin(BOB, "hash-1");
        KillOutcome kill = store.kill(BOB, ALICE, RULES);
        assertTrue(kill.linked());
        List<AltFlag> flags = store.altFlags(10);
        assertEquals(1, flags.size());
        assertEquals("Alice", flags.getFirst().killerName());
        assertEquals("Bob", flags.getFirst().victimName());
        assertEquals(kill.victim().eventId(), flags.getFirst().eventId());
    }

    @Test
    void killsBetweenStrangersAreNot() throws SQLException {
        store.recordLogin(ALICE, "hash-1");
        store.recordLogin(CARA, "hash-3");
        assertFalse(store.kill(CARA, ALICE, RULES).linked());
        assertTrue(store.altFlags(10).isEmpty());
    }

    @Test
    void archivingASeasonRecordsStandingsThenStartsEveryoneFresh() throws SQLException {
        store.recordLogin(ALICE, "hash-1");
        store.kill(BOB, ALICE, RULES);
        store.apply(List.of(HeartRequest.set(CARA, 0, HeartCause.ADMIN_SET, "op")), RULES);
        store.placeBounty(ALICE, BOB, 2, RULES);

        List<SeasonStanding> standings = store.archiveSeason("Season 1");

        assertEquals(List.of("Alice", "Bob", "Cara"), standings.stream().map(SeasonStanding::name).toList());
        SeasonStanding alice = standings.get(0), bob = standings.get(1), cara = standings.get(2);
        assertEquals(1, alice.rank());
        assertEquals(9, alice.hearts());
        assertEquals(1, alice.kills());
        assertEquals(0, alice.deaths());
        assertEquals(1, bob.deaths());
        assertEquals(1, cara.eliminations());
        assertEquals(standings, store.seasonStandings("Season 1"));

        assertTrue(store.find(ALICE).isEmpty());
        assertEquals(0, store.ledgerSize(ALICE));
        assertEquals(0, store.bountyOn(BOB));
        assertTrue(store.archivedLedgerSize("Season 1") > 0);
        assertEquals(List.of("Season 1"), store.seasons());

        assertEquals(10, store.touch(ALICE, "Alice", RULES).hearts());
        store.recordLogin(BOB, "hash-1");
        assertEquals(List.of("Alice"), store.linkedAccounts(BOB).stream().map(PlayerHearts::name).toList());
    }

    @Test
    void seasonNamesAreUnique() throws SQLException {
        store.archiveSeason("Season 1");
        assertThrows(IllegalArgumentException.class, () -> store.archiveSeason("season 1"));
    }

    @Test
    void theLiveLeaderboardRanksByHearts() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        List<PlayerHearts> top = store.topHearts(2);
        assertEquals(List.of("Alice", "Cara"), top.stream().map(PlayerHearts::name).toList());
    }

    @Test
    void theNewSeasonBeginsInTheSameStepAsTheOldOneIsArchived() throws SQLException {
        store.archiveSeason("Season 1", java.util.Map.of("season.name", "Season 2", "season.started", "123"));
        assertEquals("Season 2", store.state("season.name").orElseThrow());
        assertEquals("123", store.state("season.started").orElseThrow());

        // A reset that fails part way changes nothing at all, the new name included.
        store.touch(ALICE, "Alice", RULES);
        store.putState("season.name", "Season 2");
        assertThrows(IllegalArgumentException.class,
                () -> store.archiveSeason("Season 1", java.util.Map.of("season.name", "Season 3")));
        assertEquals("Season 2", store.state("season.name").orElseThrow());
        assertTrue(store.find(ALICE).isPresent());
    }

    @Test
    void addressesAreForgottenAfterTheyStopBeingUsed() throws SQLException {
        store.recordLogin(ALICE, "hash-1");
        store.recordLogin(BOB, "hash-1");
        clock.advance(Duration.ofDays(60));
        store.recordLogin(BOB, "hash-1");
        clock.advance(Duration.ofDays(40));
        assertEquals(1, store.pruneLogins(Duration.ofDays(90)), "only Alice's, unused for 100 days");
        assertEquals(List.of(), store.linkedAccounts(BOB));
    }
}
