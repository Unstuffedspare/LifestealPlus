package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Swapping in a whole backup when the server starts. */
class BackupsTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path folder;

    @Test
    void aStagedRestoreSwapsTheDatabaseAndKeepsTheOldOne() throws Exception {
        Path database = folder.resolve("lifesteal.db");
        Files.writeString(database, "current");
        Files.writeString(folder.resolve("lifesteal.db-wal"), "stale");
        Files.createDirectories(folder.resolve("backups"));
        Files.writeString(folder.resolve("backups").resolve("monday.db"), "monday");
        Files.writeString(folder.resolve("restore-on-start.txt"), "monday.db\n");

        Backups.restorePending(folder, database, LOG);

        assertEquals("monday", Files.readString(database));
        assertFalse(Files.exists(folder.resolve("lifesteal.db-wal")), "a leftover write-ahead log would undo the restore");
        assertFalse(Files.exists(folder.resolve("restore-on-start.txt")), "it happens once");
        try (Stream<Path> files = Files.list(folder.resolve("backups"))) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("before-restore-")));
        }
    }

    @Test
    void aMissingOrEscapingBackupChangesNothing() throws Exception {
        Path database = folder.resolve("lifesteal.db");
        Files.writeString(database, "current");
        Files.writeString(folder.resolve("restore-on-start.txt"), "../lifesteal.db");
        Backups.restorePending(folder, database, LOG);
        assertEquals("current", Files.readString(database));
        assertFalse(Files.exists(folder.resolve("restore-on-start.txt")));
    }
}
