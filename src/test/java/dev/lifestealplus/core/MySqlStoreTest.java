package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mariadb.jdbc.MariaDbDataSource;

/**
 * The store on a real MySQL or MariaDB, through every kind of statement it makes: the schema,
 * upserts, kills, bounties, pledges, seasons, stats, the economy, the audit log. Runs only when
 * LIFESTEAL_MYSQL_URL points at an empty database, e.g.
 * jdbc:mariadb://localhost:3306/lifesteal_test?user=root&amp;password=secret
 */
@EnabledIfEnvironmentVariable(named = "LIFESTEAL_MYSQL_URL", matches = ".+")
class MySqlStoreTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ofHours(6), Duration.ofMinutes(30), 1);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
    private MariaDbDataSource source;
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        source = new MariaDbDataSource(System.getenv("LIFESTEAL_MYSQL_URL"));
        dropEverything();
        store = new HeartStore(source, clock);
        for (Object[] p : new Object[][] {{ALICE, "Alice"}, {BOB, "Bob"}, {CARA, "Cara"}}) store.touch((UUID) p[0], (String) p[1], RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    private void dropEverything() throws SQLException {
        try (Connection c = source.getConnection(); Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            var tables = st.executeQuery("SHOW TABLES");
            List<String> names = new java.util.ArrayList<>();
            while (tables.next()) names.add(tables.getString(1));
            for (String name : names) st.execute("DROP TABLE `" + name + "`");
        }
    }

    private int hearts(UUID uuid) throws SQLException {
        return store.find(uuid).orElseThrow().hearts();
    }

    @Test
    void theSchemaIsBuiltAndKeptUpToDate() throws SQLException {
        store.close();
        store = new HeartStore(source, clock);
        assertEquals(10, hearts(ALICE), "a second start finds the tables already there");
        assertTrue(store.findByName("aLiCe").isPresent(), "names match whatever their case");
    }

    @Test
    void killsVengeanceAndStreaks() throws SQLException {
        store.setStreaks(new StreakRules(true, List.of(3), 0, 0));
        KillOutcome kill = store.kill(BOB, ALICE, RULES);
        assertEquals(11, hearts(ALICE));
        assertEquals(9, hearts(BOB));
        assertTrue(kill.marked());
        store.kill(ALICE, BOB, RULES);
        assertEquals(11, hearts(BOB), "vengeance takes the heart back plus the bonus");
        assertEquals(1, store.streak(BOB).orElseThrow().current());
    }

    @Test
    void bountiesPledgesAndGifts() throws SQLException {
        store.placeBounty(CARA, BOB, 2, RULES);
        store.kill(BOB, ALICE, RULES);
        assertEquals(13, hearts(ALICE), "a heart and the bounty");
        store.apply(List.of(HeartRequest.set(BOB, 0, HeartCause.ADMIN_SET, "op")), RULES);
        store.pledge(ALICE, BOB, 2, 3, 2, RULES);
        PledgeOutcome back = store.pledge(CARA, BOB, 1, 3, 2, RULES);
        assertEquals(3, back.revive().after());
        assertEquals(1, store.giveHearts(ALICE, CARA, 2, 50, RULES).tax());
    }

    @Test
    void upsertsUpdateRatherThanDuplicate() throws SQLException {
        store.putState("season.name", "Season 1");
        store.putState("season.name", "Season 2");
        assertEquals("Season 2", store.state("season.name").orElseThrow());
        store.recordLogin(ALICE, "hash");
        store.recordLogin(ALICE, "hash");
        store.recordLogin(BOB, "hash");
        assertEquals(List.of("Bob"), store.linkedAccounts(ALICE).stream().map(PlayerHearts::name).toList());
        store.recordBodyDeath(ALICE);
        store.recordBodyDeath(ALICE);
        assertTrue(store.takeBodyDeath(ALICE));
    }

    @Test
    void seasonsStatsEconomyAndAudit() throws SQLException {
        store.kill(BOB, ALICE, RULES);
        assertEquals(1, store.stats("alice", null).orElseThrow().kills());
        assertEquals(1, store.economy(Duration.ofDays(1)).gained().get(HeartCause.PVP_KILL));
        store.addAudit("Alice", "setting", "hearts.max: 20 -> 25");
        store.archiveSeason("Season 1", Map.of("season.name", "Season 2"));
        assertEquals(1, store.stats("Alice", "season 1").orElseThrow().kills());
        assertEquals("Alice", store.seasonStandings("Season 1").getFirst().name());
        assertThrows(IllegalArgumentException.class, () -> store.archiveSeason("Season 1"));
        assertEquals(1, store.auditSize(), "kept through the reset");
    }
}
