package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
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

/** The audit log, backups, and CSV exports. */
class RecordsTest {

    private static final HeartRules RULES = new HeartRules(10, 20, 3, 1, Duration.ZERO);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    private final RepeatKillTest.MutableClock clock = new RepeatKillTest.MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
    private HeartStore store;

    @BeforeEach
    void open() throws SQLException {
        store = TestStores.open(dir.resolve("test.db"), clock);
        store.touch(ALICE, "Alice", RULES);
        store.touch(BOB, "Bob", RULES);
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    @Test
    void adminActionsAreKeptNewestFirst() throws SQLException {
        store.addAudit("Alice", "setting", "hearts.max: 20 -> 25");
        clock.advance(Duration.ofMinutes(1));
        store.addAudit("Console", "command", "/lifesteal give Bob 2");
        List<AuditEntry> entries = store.audit(10, 0);
        assertEquals(List.of("Console", "Alice"), entries.stream().map(AuditEntry::actor).toList());
        assertEquals(2, store.auditSize());
        assertEquals(1, store.audit(1, 1).size());
        store.archiveSeason("Season 1");
        assertEquals(2, store.auditSize(), "a season reset keeps the audit log");
    }

    @Test
    void aBackupHoldsTheHeartsAsTheyWere() throws SQLException {
        TestStores.sqliteOnly();
        store.apply(List.of(HeartRequest.set(BOB, 7, HeartCause.ADMIN_SET, "op")), RULES);
        Path backup = dir.resolve("backups").resolve("one.db");
        store.backUpTo(backup);
        store.apply(List.of(HeartRequest.set(BOB, 2, HeartCause.ADMIN_SET, "op")), RULES);
        assertEquals(7, store.heartsInBackup(backup, "bob").orElseThrow().hearts());
        assertTrue(store.heartsInBackup(backup, "Nobody").isEmpty());
    }

    @Test
    void theLedgerExportsAsCsvThatsSafeToOpen() throws Exception {
        store.apply(List.of(HeartRequest.add(BOB, 1, HeartCause.ADMIN_GIVE, "op").withNote("=HYPERLINK(\"x\"), sorry")), RULES);
        Path csv = dir.resolve("exports").resolve("ledger.csv");
        assertEquals(3, store.exportLedger(csv, null), "two first joins and the gift");
        List<String> lines = Files.readAllLines(csv);
        assertEquals("id,event,time,player,uuid,cause,before,after,other,actor,note", lines.getFirst());
        String gift = lines.getLast();
        assertTrue(gift.contains("2026-11-04T12:00:00Z"), gift);
        assertTrue(gift.endsWith("\"'=HYPERLINK(\"\"x\"\"), sorry\""), "quoted, and the formula defused: " + gift);
        assertEquals(1, store.exportLedger(dir.resolve("bob.csv"), "bob") - 1, "Bob's join and the gift");
    }

    @Test
    void theAuditExportsToo() throws Exception {
        store.addAudit("Alice", "preset", "hardcore (22 settings)");
        Path csv = dir.resolve("audit.csv");
        assertEquals(1, store.exportAudit(csv));
        assertEquals(List.of("id,time,actor,action,detail", "1,2026-11-04T12:00:00Z,Alice,preset,hardcore (22 settings)"),
                Files.readAllLines(csv));
    }
}
