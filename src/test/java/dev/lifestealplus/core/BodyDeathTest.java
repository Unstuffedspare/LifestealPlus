package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A combat-logger's body was killed while they were offline: their next login has to finish the death. */
class BodyDeathTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6));
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), Clock.systemUTC());
        store.touch(BOB, "Bob", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    @Test
    void aRecordedBodyDeathIsTakenExactlyOnce() throws SQLException {
        assertFalse(store.takeBodyDeath(BOB));
        store.recordBodyDeath(BOB);
        store.recordBodyDeath(BOB);
        assertTrue(store.takeBodyDeath(BOB));
        assertFalse(store.takeBodyDeath(BOB));
    }

    @Test
    void itSurvivesARestart() throws SQLException {
        store.recordBodyDeath(BOB);
        store.close();
        store = TestStores.open(dir.resolve("test.db"), Clock.systemUTC());
        assertTrue(store.takeBodyDeath(BOB));
    }
}
