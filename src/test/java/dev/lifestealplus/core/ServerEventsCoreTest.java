package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The store-side pieces the server events lean on. */
class ServerEventsCoreTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), Clock.systemUTC());
        store.touch(ALICE, "Alice", RULES);
        store.touch(BOB, "Bob", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    @Test
    void serverStateIsKeptAcrossRestarts() throws SQLException {
        assertEquals(Optional.empty(), store.state("blood_moon.next"));
        store.putState("blood_moon.next", "123");
        store.putState("blood_moon.next", "456");
        store.close();
        store = TestStores.open(dir.resolve("test.db"), Clock.systemUTC());
        assertEquals(Optional.of("456"), store.state("blood_moon.next"));
        store.clearState("blood_moon.next");
        assertEquals(Optional.empty(), store.state("blood_moon.next"));
    }

    @Test
    void aHeartItemAddsAHeartButNotPastTheCap() throws SQLException {
        HeartChange used = store.apply(List.of(HeartRequest.add(ALICE, 1, HeartCause.HEART_ITEM, "Alice")), RULES).getFirst();
        assertEquals(1, used.delta());
        store.apply(List.of(HeartRequest.set(ALICE, 20, HeartCause.ADMIN_SET, "op")), RULES);
        HeartChange capped = store.apply(List.of(HeartRequest.add(ALICE, 1, HeartCause.HEART_ITEM, "Alice")), RULES).getFirst();
        assertEquals(0, capped.delta());
    }

    @Test
    void aHeartItemCannotReviveTheEliminated() throws SQLException {
        store.apply(List.of(HeartRequest.set(BOB, 0, HeartCause.ADMIN_SET, "op")), RULES);
        HeartChange used = store.apply(List.of(HeartRequest.add(BOB, 1, HeartCause.HEART_ITEM, "Bob")), RULES).getFirst();
        assertEquals(0, used.after());
    }

    @Test
    void doubledRulesMoveTwoHeartsPerKill() throws SQLException {
        KillOutcome kill = store.kill(BOB, ALICE, RULES.withHeartsPerKill(RULES.heartsPerKill() * 2));
        assertEquals(-2, kill.victim().delta());
        assertEquals(2, kill.killer().delta());
    }

    @Test
    void theLastPlayerStandingCanBeFound() throws SQLException {
        assertEquals(2, store.aliveCount());
        store.apply(List.of(HeartRequest.set(BOB, 0, HeartCause.ADMIN_SET, "op")), RULES);
        assertEquals(1, store.aliveCount());
        Optional<PlayerHearts> last = store.lastStanding();
        assertTrue(last.isPresent());
        assertEquals("Alice", last.get().name());
    }
}
