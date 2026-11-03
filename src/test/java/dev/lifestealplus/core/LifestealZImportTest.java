package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Bringing players over from LifeStealZ. */
class LifestealZImportTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6), Duration.ZERO, 0, Duration.ofHours(3));
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID DAN = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @TempDir
    Path dir;

    /** A userData.db laid out the way LifeStealZ creates it. */
    private Path lifestealzDatabase() throws SQLException {
        Path file = dir.resolve("userData.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE hearts (uuid CHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, maxhp FLOAT NOT NULL DEFAULT 20.0,
                    hasbeenRevived SMALLINT NOT NULL DEFAULT 0, craftedHearts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
                    craftedRevives SMALLINT UNSIGNED NOT NULL DEFAULT 0, killedOtherPlayers MEDIUMINT UNSIGNED NOT NULL DEFAULT 0,
                    firstJoin BIGINT UNSIGNED NOT NULL)""");
            st.execute("INSERT INTO hearts VALUES ('" + ALICE + "', 'Alice', 30.0, 0, 0, 0, 4, 0)");
            st.execute("INSERT INTO hearts VALUES ('" + BOB + "', 'Bob', 0.0, 1, 0, 0, 0, 0)");
            st.execute("INSERT INTO hearts VALUES ('" + CARA + "', 'Cara', 51.0, 0, 2, 0, 9, 0)");
            st.execute("INSERT INTO hearts VALUES ('not-a-uuid', 'Broken', 20.0, 0, 0, 0, 0, 0)");
        }
        return file;
    }

    @Test
    void readsLifestealZsDatabase() throws Exception {
        LifestealZImport.Source source = LifestealZImport.read(lifestealzDatabase());
        assertEquals(3, source.rows().size());
        assertEquals(1, source.skipped());
        assertEquals(30.0, source.rows().getFirst().maxHp());
    }

    @Test
    void readsLifestealZsCsvExport() throws Exception {
        Path csv = dir.resolve("export.csv");
        Files.writeString(csv, ALICE + ",Alice,30.0,0,0,0,4,0\n\n" + BOB + ",Bob,0.0,1,0,0,0,0\ngarbage line\n");
        LifestealZImport.Source source = LifestealZImport.read(csv);
        assertEquals(List.of("Alice", "Bob"), source.rows().stream().map(LifestealZImport.Row::name).toList());
        assertEquals(1, source.skipped());
    }

    @Test
    void planHalvesRoundsDownEliminatesAndCaps() {
        List<LifestealZImport.Planned> plan = LifestealZImport.plan(List.of(
                new LifestealZImport.Row(ALICE, "Alice", 30.0),
                new LifestealZImport.Row(BOB, "Bob", 0.0),
                new LifestealZImport.Row(CARA, "Cara", 51.0),
                new LifestealZImport.Row(DAN, "Dan", 7.0)), 0, RULES);
        assertEquals(15, plan.get(0).hearts());
        assertTrue(plan.get(1).eliminated());
        assertEquals(0, plan.get(1).hearts());
        assertEquals(20, plan.get(2).hearts());
        assertTrue(plan.get(2).capped());
        assertEquals(3, plan.get(3).hearts(), "3.5 hearts rounds down");
        assertFalse(plan.get(3).eliminated());
    }

    @Test
    void lifestealZsOwnMinimumDecidesWhoIsEliminated() {
        List<LifestealZImport.Planned> plan = LifestealZImport.plan(List.of(new LifestealZImport.Row(ALICE, "Alice", 6.0)), 3, RULES);
        assertTrue(plan.getFirst().eliminated());
    }

    @Test
    void importingCreatesAndUpdatesPlayersInOneGo() throws Exception {
        try (HeartStore store = TestStores.open(dir.resolve("ours.db"), Clock.systemUTC())) {
            store.touch(ALICE, "Alice", RULES);
            List<LifestealZImport.Planned> plan = LifestealZImport.plan(LifestealZImport.read(lifestealzDatabase()).rows(), 0, RULES);

            ImportResult result = store.importPlayers(plan, "LifeStealZ", RULES);

            assertEquals(2, result.created());
            assertEquals(1, result.updated());
            assertEquals(15, store.find(ALICE).orElseThrow().hearts());
            assertTrue(store.find(BOB).orElseThrow().eliminated());
            assertEquals(20, store.find(CARA).orElseThrow().hearts());
            assertEquals(Duration.ZERO, store.shieldLeft(CARA), "imported players aren't new, so no shield");
            LedgerEntry row = store.ledger(ALICE, 1, 0).getFirst();
            assertEquals(HeartCause.IMPORT, row.cause());
            assertEquals("LifeStealZ", row.actor());
        }
    }

    @Test
    void importingTwiceChangesNothingTheSecondTime() throws Exception {
        try (HeartStore store = TestStores.open(dir.resolve("ours.db"), Clock.systemUTC())) {
            List<LifestealZImport.Planned> plan = LifestealZImport.plan(LifestealZImport.read(lifestealzDatabase()).rows(), 0, RULES);
            store.importPlayers(plan, "LifeStealZ", RULES);
            ImportResult again = store.importPlayers(plan, "LifeStealZ", RULES);
            assertEquals(0, again.created());
            assertEquals(15, store.find(ALICE).orElseThrow().hearts());
            assertEquals(20, store.find(CARA).orElseThrow().hearts());
        }
    }
}
