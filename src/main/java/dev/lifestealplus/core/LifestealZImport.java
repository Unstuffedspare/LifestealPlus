package dev.lifestealplus.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Reads the player data LifeStealZ leaves behind and turns it into hearts for this plugin.
 * LifeStealZ keeps one row per player in a "hearts" table (userData.db when it uses SQLite),
 * with max health in half-hearts; /lifestealz data export writes the same rows to a CSV.
 */
public final class LifestealZImport {

    /** One player as LifeStealZ recorded them. {@code maxHp} is in half-hearts (20 = 10 hearts). */
    public record Row(UUID uuid, String name, double maxHp) {
    }

    /** What was read, and how many lines couldn't be understood. */
    public record Source(List<Row> rows, int skipped) {
    }

    /**
     * One player as they'll arrive here.
     *
     * @param eliminated LifeStealZ had them at or below its minimum, so they arrive with no hearts
     * @param capped they had more than this server's cap allows, so they arrive at the cap
     */
    public record Planned(UUID uuid, String name, int hearts, boolean eliminated, boolean capped) {
    }

    private LifestealZImport() {
    }

    /** A .csv file from /lifestealz data export, or LifeStealZ's SQLite database (anything else). */
    public static Source read(Path file) throws IOException, SQLException {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv") ? readCsv(file) : readSqlite(file);
    }

    private static Source readCsv(Path file) throws IOException {
        List<Row> rows = new ArrayList<>();
        int skipped = 0;
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            String[] fields = line.split(",", -1);
            try {
                rows.add(new Row(UUID.fromString(fields[0].trim()), fields[1].trim(), Double.parseDouble(fields[2].trim())));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        return new Source(rows, skipped);
    }

    private static Source readSqlite(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        List<Row> rows = new ArrayList<>();
        int skipped = 0;
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, maxhp FROM hearts")) {
            while (rs.next()) {
                try {
                    rows.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getDouble(3)));
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
        }
        return new Source(rows, skipped);
    }

    /**
     * Works out each player's hearts here: half of LifeStealZ's max health, rounded down
     * (this plugin has no half hearts). At or below LifeStealZ's {@code minHearts} they're
     * eliminated; above this server's cap they're brought down to it.
     */
    public static List<Planned> plan(List<Row> rows, int lifestealzMinHearts, HeartRules rules) {
        List<Planned> planned = new ArrayList<>(rows.size());
        for (Row row : rows) {
            int hearts = (int) Math.floor(row.maxHp() / 2.0);
            boolean eliminated = hearts <= lifestealzMinHearts;
            boolean capped = !eliminated && hearts > rules.maxHearts();
            int arriving = eliminated ? 0 : Math.min(hearts, rules.maxHearts());
            planned.add(new Planned(row.uuid(), row.name(), arriving, eliminated, capped));
        }
        return planned;
    }
}
