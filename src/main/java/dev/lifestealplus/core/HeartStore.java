package dev.lifestealplus.core;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite-backed heart state and ledger. Every heart change goes through
 * {@link #apply}, {@link #kill} or {@link #touch}, which update the player and
 * write the ledger row in one transaction, so the two can't disagree.
 *
 * <p>Not thread-safe: {@link HeartService} confines it to a single thread.
 */
public final class HeartStore implements AutoCloseable {

    private static final int SCHEMA_VERSION = 2;

    private final Connection conn;
    private final Clock clock;

    public HeartStore(Path file, Clock clock) throws SQLException {
        this.clock = clock;
        // DataSource rather than DriverManager: the driver lives in Paper's plugin
        // library loader, which DriverManager's service lookup doesn't search.
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        this.conn = ds.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        int version;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("Database schema v" + version + " is newer than this plugin (v" + SCHEMA_VERSION + ")");
        }
        if (version < 1) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                            CREATE TABLE players (
                                uuid       TEXT PRIMARY KEY,
                                name       TEXT    NOT NULL,
                                hearts     INTEGER NOT NULL,
                                first_seen INTEGER NOT NULL,
                                last_seen  INTEGER NOT NULL
                            )""");
                    st.execute("CREATE INDEX players_name ON players(name COLLATE NOCASE)");
                    st.execute("""
                            CREATE TABLE heart_ledger (
                                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                                event_id      INTEGER NOT NULL,
                                at            INTEGER NOT NULL,
                                player_uuid   TEXT    NOT NULL,
                                player_name   TEXT    NOT NULL,
                                cause         TEXT    NOT NULL,
                                op            TEXT    NOT NULL,
                                requested     INTEGER NOT NULL,
                                hearts_before INTEGER NOT NULL,
                                hearts_after  INTEGER NOT NULL,
                                other_uuid    TEXT,
                                other_name    TEXT,
                                actor         TEXT,
                                note          TEXT
                            )""");
                    st.execute("CREATE INDEX ledger_player ON heart_ledger(player_uuid, id)");
                    st.execute("CREATE INDEX ledger_event ON heart_ledger(event_id)");
                    st.execute("PRAGMA user_version = 1");
                }
                return null;
            });
        }
        if (version < 2) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                            CREATE TABLE purgatory_progress (
                                uuid        TEXT PRIMARY KEY,
                                fragments   INTEGER NOT NULL,
                                last_win_at INTEGER
                            )""");
                    st.execute("PRAGMA user_version = 2");
                }
                return null;
            });
        }
    }

    /**
     * Records that a player is online, creating them with starting hearts on their
     * first join. Keeps the stored name current so name lookups follow renames.
     */
    public PlayerHearts touch(UUID uuid, String name, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            long now = clock.millis();
            Optional<PlayerHearts> existing = find(uuid);
            if (existing.isPresent()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET name = ?, last_seen = ? WHERE uuid = ?")) {
                    ps.setString(1, name);
                    ps.setLong(2, now);
                    ps.setString(3, uuid.toString());
                    ps.executeUpdate();
                }
                return new PlayerHearts(uuid, name, existing.get().hearts());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (uuid, name, hearts, first_seen, last_seen) VALUES (?, ?, 0, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            HeartRequest start = HeartRequest.set(uuid, rules.startingHearts(), HeartCause.FIRST_JOIN, "server");
            HeartChange change = applyOne(start, rules, nextEventId(), now);
            return new PlayerHearts(uuid, name, change.after());
        });
    }

    /** Applies all requests as one event: they share an event id and commit together. */
    public List<HeartChange> apply(List<HeartRequest> requests, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            long eventId = nextEventId();
            long now = clock.millis();
            List<HeartChange> changes = new ArrayList<>(requests.size());
            for (HeartRequest request : requests) {
                changes.add(applyOne(request, rules, eventId, now));
            }
            return changes;
        });
    }

    /**
     * A PvP kill: the victim loses {@code heartsPerKill} and the killer gains exactly
     * what the victim lost (less if the killer is at the cap). If this killer already
     * took a heart from this victim within the repeat-kill window, neither changes; both
     * rows are still logged, noted "repeat kill".
     */
    public KillOutcome kill(UUID victim, UUID killer, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            long eventId = nextEventId();
            long now = clock.millis();
            String victimName = require(victim).name();
            String killerName = require(killer).name();
            HeartRequest lossRequest = HeartRequest.add(victim, -rules.heartsPerKill(), HeartCause.PVP_DEATH, "server")
                    .withOther(killer, killerName);

            if (paidKillSince(victim, killer, now - rules.repeatKillWindow().toMillis(), rules)) {
                HeartChange loss = applyOne(lossRequest.withNote(REPEAT_KILL), rules, eventId, now, true);
                HeartChange gain = applyOne(
                        HeartRequest.add(killer, rules.heartsPerKill(), HeartCause.PVP_KILL, "server")
                                .withOther(victim, victimName).withNote(REPEAT_KILL),
                        rules, eventId, now, true);
                return new KillOutcome(loss, gain, true);
            }

            HeartChange loss = applyOne(lossRequest, rules, eventId, now);
            int taken = loss.before() - loss.after();
            HeartChange gain = applyOne(
                    HeartRequest.add(killer, taken, HeartCause.PVP_KILL, "server")
                            .withOther(victim, victimName),
                    rules, eventId, now);
            return new KillOutcome(loss, gain, false);
        });
    }

    private static final String REPEAT_KILL = "repeat kill";

    /**
     * Whether {@code killer} took a heart from {@code victim} after {@code sinceMillis}.
     * Read straight from the ledger: blocked repeat kills moved nothing, so they don't
     * count and can't stretch the window.
     */
    private boolean paidKillSince(UUID victim, UUID killer, long sinceMillis, HeartRules rules) throws SQLException {
        if (rules.repeatKillWindow().isZero()) return false;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM heart_ledger
                WHERE player_uuid = ? AND cause = 'PVP_DEATH' AND other_uuid = ?
                  AND hearts_after < hearts_before AND at > ?
                LIMIT 1""")) {
            ps.setString(1, victim.toString());
            ps.setString(2, killer.toString());
            ps.setLong(3, sinceMillis);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private HeartChange applyOne(HeartRequest request, HeartRules rules, long eventId, long now) throws SQLException {
        return applyOne(request, rules, eventId, now, false);
    }

    /** {@code blocked} logs the request without changing anything (a rule refused it). */
    private HeartChange applyOne(HeartRequest request, HeartRules rules, long eventId, long now, boolean blocked)
            throws SQLException {
        PlayerHearts current = require(request.player());
        int before = current.hearts();
        int after = blocked ? before : rules.resolve(before, request);
        String note = request.note();
        if (note == null) note = autoNote(before, after, request);

        if (after != before) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET hearts = ? WHERE uuid = ?")) {
                ps.setInt(1, after);
                ps.setString(2, request.player().toString());
                ps.executeUpdate();
            }
        }
        // Each elimination is a fresh climb back: drop fragments on the way in and on the way out.
        if ((before > 0) != (after > 0)) clearProgress(request.player());
        long ledgerId;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO heart_ledger (event_id, at, player_uuid, player_name, cause, op, requested,
                                          hearts_before, hearts_after, other_uuid, other_name, actor, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, eventId);
            ps.setLong(2, now);
            ps.setString(3, request.player().toString());
            ps.setString(4, current.name());
            ps.setString(5, request.cause().name());
            ps.setString(6, request.op().name());
            ps.setInt(7, request.amount());
            ps.setInt(8, before);
            ps.setInt(9, after);
            setNullable(ps, 10, request.otherUuid() == null ? null : request.otherUuid().toString());
            setNullable(ps, 11, request.otherName());
            setNullable(ps, 12, request.actor());
            setNullable(ps, 13, note);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                ledgerId = keys.getLong(1);
            }
        }
        return new HeartChange(ledgerId, eventId, request.player(), current.name(), request.cause(), before, after);
    }

    public PurgatoryProgress progress(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT fragments, last_win_at FROM purgatory_progress WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return PurgatoryProgress.NONE;
                long lastWin = rs.getLong(2);
                return new PurgatoryProgress(rs.getInt(1), rs.wasNull() ? null : Instant.ofEpochMilli(lastWin));
            }
        }
    }

    /**
     * Gives an eliminated player one fragment for winning a trial; the fragment that
     * reaches {@code needed} revives them in the same transaction. Players who are no
     * longer eliminated (an admin revived them mid-trial) get nothing.
     */
    public TrialReward awardFragment(UUID player, int needed, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            if (!require(player).eliminated()) return new TrialReward(0, needed, null);
            long now = clock.millis();
            int fragments = progress(player).fragments() + 1;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO purgatory_progress (uuid, fragments, last_win_at) VALUES (?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET fragments = excluded.fragments, last_win_at = excluded.last_win_at""")) {
                ps.setString(1, player.toString());
                ps.setInt(2, fragments);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            HeartChange revive = null;
            if (fragments >= needed) {
                revive = applyOne(
                        HeartRequest.set(player, rules.reviveHearts(), HeartCause.REVIVE, "purgatory")
                                .withNote(fragments + " trial fragments"),
                        rules, nextEventId(), now);
            }
            return new TrialReward(fragments, needed, revive);
        });
    }

    private void clearProgress(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM purgatory_progress WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
    }

    /** Explains, in the ledger, a change that didn't land the way it was asked for. */
    private static String autoNote(int before, int after, HeartRequest request) {
        int wanted = request.op() == HeartRequest.Op.SET ? request.amount() : before + request.amount();
        if (after == wanted) return null;
        if (before <= 0 && after == before) return "eliminated";
        if (after < wanted) return "capped";
        return "floored at 0";
    }

    public Optional<PlayerHearts> find(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, hearts FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new PlayerHearts(uuid, rs.getString(1), rs.getInt(2)));
            }
        }
    }

    /** Case-insensitive; if two records share a name (after renames), the most recently seen wins. */
    public Optional<PlayerHearts> findByName(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, name, hearts FROM players WHERE name = ? COLLATE NOCASE ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new PlayerHearts(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3)));
            }
        }
    }

    /** Newest first. */
    public List<LedgerEntry> ledger(UUID player, int limit, int offset) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, event_id, at, player_name, cause, op, requested, hearts_before, hearts_after,
                       other_uuid, other_name, actor, note
                FROM heart_ledger WHERE player_uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?""")) {
            ps.setString(1, player.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            List<LedgerEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String other = rs.getString(10);
                    entries.add(new LedgerEntry(
                            rs.getLong(1), rs.getLong(2), Instant.ofEpochMilli(rs.getLong(3)), player,
                            rs.getString(4), HeartCause.valueOf(rs.getString(5)),
                            HeartRequest.Op.valueOf(rs.getString(6)), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                            other == null ? null : UUID.fromString(other), rs.getString(11),
                            rs.getString(12), rs.getString(13)));
                }
            }
            return entries;
        }
    }

    public int ledgerSize(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM heart_ledger WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private PlayerHearts require(UUID uuid) throws SQLException {
        return find(uuid).orElseThrow(() -> new UnknownPlayerException(uuid));
    }

    private long nextEventId() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(event_id), 0) + 1 FROM heart_ledger")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        conn.setAutoCommit(false);
        try {
            T result = work.run();
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
