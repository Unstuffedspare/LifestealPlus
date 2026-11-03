package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** SQLite's SQL as MySQL and MariaDB want it. */
class MySqlTranslateTest {

    private static String t(String sql) {
        return MySqlConnection.translate(sql);
    }

    @Test
    void theSchemaVersionLivesInATable() {
        assertEquals("SELECT version FROM schema_info", t("PRAGMA user_version"));
        assertEquals("UPDATE schema_info SET version = 7", t("PRAGMA user_version = 7"));
        assertEquals("DO 0", t("PRAGMA journal_mode=WAL"));
    }

    @Test
    void tablesGetMySqlTypes() {
        assertEquals("CREATE TABLE audit (id BIGINT PRIMARY KEY AUTO_INCREMENT, at BIGINT NOT NULL, actor VARCHAR(191) NOT NULL,"
                        + " detail TEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                t("CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL,"
                        + " detail TEXT NOT NULL)"));
        assertEquals("CREATE TABLE server_state (`key` VARCHAR(191) PRIMARY KEY, `value` TEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                t("CREATE TABLE server_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)"));
        assertEquals("ALTER TABLE players ADD COLUMN streak BIGINT NOT NULL DEFAULT 0",
                t("ALTER TABLE players ADD COLUMN streak INTEGER NOT NULL DEFAULT 0"));
    }

    @Test
    void indexesLoseWhatMySqlDoesntHave() {
        assertEquals("CREATE INDEX players_name ON players(name)", t("CREATE INDEX players_name ON players(name COLLATE NOCASE)"));
        assertEquals("CREATE INDEX bounties_open ON bounties(target)",
                t("CREATE INDEX bounties_open ON bounties(target) WHERE closed_at IS NULL"));
    }

    @Test
    void upsertsAndReservedWords() {
        assertEquals("INSERT INTO server_state (`key`, `value`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)",
                t("INSERT INTO server_state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"));
        assertEquals("INSERT INTO revival_pledges (target, pledger, hearts, at) VALUES (?, ?, ?, ?)\n"
                        + "ON DUPLICATE KEY UPDATE hearts = hearts + VALUES(hearts), at = VALUES(at)",
                t("INSERT INTO revival_pledges (target, pledger, hearts, at) VALUES (?, ?, ?, ?)\n"
                        + "ON CONFLICT(target, pledger) DO UPDATE SET hearts = hearts + excluded.hearts, at = excluded.at"));
        assertEquals("SELECT `rank`, uuid FROM season_standings WHERE season = ? ORDER BY `rank`",
                t("SELECT rank, uuid FROM season_standings WHERE season = ? COLLATE NOCASE ORDER BY rank"));
    }

    @Test
    void theLargerOfTwoIsGreatest() {
        assertEquals("UPDATE players SET best_streak = GREATEST(best_streak, streak + 1) WHERE uuid = ?",
                t("UPDATE players SET best_streak = MAX(best_streak, streak + 1) WHERE uuid = ?"));
        assertEquals("SELECT COALESCE(MAX(event_id), 0) + 1 FROM heart_ledger",
                t("SELECT COALESCE(MAX(event_id), 0) + 1 FROM heart_ledger"), "the aggregate stays");
    }

    @Test
    void ordinaryQueriesPassThrough() {
        String sql = "SELECT uuid, name, hearts FROM players WHERE hearts <= 0 ORDER BY last_seen DESC LIMIT ?";
        assertEquals(sql, t(sql));
    }
}
