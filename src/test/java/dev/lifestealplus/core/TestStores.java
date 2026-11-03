package dev.lifestealplus.core;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.mariadb.jdbc.MariaDbDataSource;

/**
 * Opens the store a test works with: SQLite in the test's folder, or, when LIFESTEAL_MYSQL_URL
 * names an empty MySQL/MariaDB database, that database (emptied first), so every store test
 * also checks the MySQL translation.
 */
final class TestStores {

    private static final String MYSQL = System.getenv("LIFESTEAL_MYSQL_URL");

    private TestStores() {
    }

    static boolean mysql() {
        return MYSQL != null && !MYSQL.isBlank();
    }

    /** A fresh store; reopening the same {@code file} later finds what was saved (in MySQL too). */
    static HeartStore open(Path file, Clock clock) throws SQLException {
        if (!mysql()) return new HeartStore(file, clock);
        MariaDbDataSource source = new MariaDbDataSource(MYSQL);
        if (!opened.contains(file)) {
            opened.add(file);
            try (Connection c = source.getConnection(); Statement st = c.createStatement()) {
                var tables = st.executeQuery("SHOW TABLES");
                List<String> names = new ArrayList<>();
                while (tables.next()) names.add(tables.getString(1));
                for (String name : names) st.execute("DROP TABLE `" + name + "`");
            }
        }
        return new HeartStore(source, clock);
    }

    private static final List<Path> opened = new ArrayList<>();

    /** For tests of SQLite-only things (backup files). */
    static void sqliteOnly() {
        Assumptions.assumeFalse(mysql(), "SQLite only");
    }
}
