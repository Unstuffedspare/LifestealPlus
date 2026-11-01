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

    private static final int SCHEMA_VERSION = 3;

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
        if (version < 3) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                            CREATE TABLE vengeance_marks (
                                marked     TEXT    NOT NULL,
                                avenger    TEXT    NOT NULL,
                                expires_at INTEGER NOT NULL,
                                PRIMARY KEY (marked, avenger)
                            )""");
                    st.execute("""
                            CREATE TABLE bounties (
                                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                                target        TEXT    NOT NULL,
                                placer        TEXT    NOT NULL,
                                hearts        INTEGER NOT NULL,
                                placed_at     INTEGER NOT NULL,
                                closed_at     INTEGER,
                                closed_by     TEXT,
                                closed_reason TEXT
                            )""");
                    st.execute("CREATE INDEX bounties_open ON bounties(target) WHERE closed_at IS NULL");
                    st.execute("PRAGMA user_version = 3");
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
     * A PvP kill, as one event:
     * <ul>
     *   <li>The victim loses {@code heartsPerKill} and the killer gains exactly what the victim
     *       lost (less if the killer is at the cap).</li>
     *   <li>If this killer already took a heart from this victim within the repeat-kill window,
     *       neither changes; both rows are still logged, noted "repeat kill".</li>
     *   <li>If the victim had taken the killer's heart within the vengeance window, the killer
     *       takes {@code vengeanceBonus} extra hearts back and the feud ends there. Otherwise a
     *       kill that moved hearts marks the killer for the victim's vengeance.</li>
     *   <li>Whatever the hearts did, the killer collects any bounties on the victim.</li>
     * </ul>
     */
    public KillOutcome kill(UUID victim, UUID killer, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            long eventId = nextEventId();
            long now = clock.millis();
            String victimName = require(victim).name();
            String killerName = require(killer).name();

            if (paidKillSince(victim, killer, now - rules.repeatKillWindow().toMillis(), rules)) {
                HeartChange loss = applyOne(
                        HeartRequest.add(victim, -rules.heartsPerKill(), HeartCause.PVP_DEATH, "server")
                                .withOther(killer, killerName).withNote(REPEAT_KILL),
                        rules, eventId, now, true);
                HeartChange gain = applyOne(
                        HeartRequest.add(killer, rules.heartsPerKill(), HeartCause.PVP_KILL, "server")
                                .withOther(victim, victimName).withNote(REPEAT_KILL),
                        rules, eventId, now, true);
                BountyClaim bounty = claimBounties(victim, victimName, killer, rules, eventId, now);
                return new KillOutcome(loss, gain, true, false, false, bounty);
            }

            boolean vengeance = consumeMark(victim, killer, now);
            String note = vengeance ? VENGEANCE : null;
            int amount = rules.heartsPerKill() + (vengeance ? rules.vengeanceBonus() : 0);
            HeartChange loss = applyOne(
                    HeartRequest.add(victim, -amount, HeartCause.PVP_DEATH, "server")
                            .withOther(killer, killerName).withNote(note),
                    rules, eventId, now);
            int taken = loss.before() - loss.after();
            HeartChange gain = applyOne(
                    HeartRequest.add(killer, taken, HeartCause.PVP_KILL, "server")
                            .withOther(victim, victimName).withNote(note),
                    rules, eventId, now);

            boolean marked = !vengeance && taken > 0 && !rules.vengeanceWindow().isZero();
            if (marked) mark(killer, victim, now + rules.vengeanceWindow().toMillis());
            BountyClaim bounty = claimBounties(victim, victimName, killer, rules, eventId, now);
            return new KillOutcome(loss, gain, false, vengeance, marked, bounty);
        });
    }

    private static final String REPEAT_KILL = "repeat kill";
    private static final String VENGEANCE = "vengeance";

    /** Marks {@code marked} for {@code avenger}'s vengeance, replacing any older mark between the two. */
    private void mark(UUID marked, UUID avenger, long expiresAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO vengeance_marks (marked, avenger, expires_at) VALUES (?, ?, ?)
                ON CONFLICT(marked, avenger) DO UPDATE SET expires_at = excluded.expires_at""")) {
            ps.setString(1, marked.toString());
            ps.setString(2, avenger.toString());
            ps.setLong(3, expiresAt);
            ps.executeUpdate();
        }
    }

    /** Uses up {@code avenger}'s live mark on {@code marked}, if there is one. */
    private boolean consumeMark(UUID marked, UUID avenger, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM vengeance_marks WHERE marked = ? AND avenger = ? AND expires_at > ?")) {
            ps.setString(1, marked.toString());
            ps.setString(2, avenger.toString());
            ps.setLong(3, now);
            return ps.executeUpdate() > 0;
        }
    }

    /** Live marks {@code avenger} holds: the players they can take vengeance on. Soonest to expire first. */
    public List<VengeanceMark> marksHeldBy(UUID avenger) throws SQLException {
        return marks("m.avenger = ?", avenger);
    }

    /** Live marks on {@code marked}: the players who can take vengeance on them. */
    public List<VengeanceMark> marksOn(UUID marked) throws SQLException {
        return marks("m.marked = ?", marked);
    }

    private List<VengeanceMark> marks(String where, UUID who) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT m.marked, pm.name, m.avenger, pa.name, m.expires_at
                FROM vengeance_marks m
                JOIN players pm ON pm.uuid = m.marked
                JOIN players pa ON pa.uuid = m.avenger
                WHERE %s AND m.expires_at > ?
                ORDER BY m.expires_at""".formatted(where))) {
            ps.setString(1, who.toString());
            ps.setLong(2, clock.millis());
            List<VengeanceMark> marks = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    marks.add(new VengeanceMark(UUID.fromString(rs.getString(1)), rs.getString(2),
                            UUID.fromString(rs.getString(3)), rs.getString(4), Instant.ofEpochMilli(rs.getLong(5))));
                }
            }
            return marks;
        }
    }

    /**
     * Stakes {@code hearts} of the placer's own hearts on {@code target}. The hearts leave the
     * placer now and sit on the target until someone kills them (or an admin clears it).
     */
    public BountyPlacement placeBounty(UUID placer, UUID target, int hearts, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            if (hearts < 1) throw new BountyException("A bounty has to be at least 1 heart.");
            if (placer.equals(target)) throw new BountyException("You can't put a bounty on yourself.");
            PlayerHearts from = require(placer);
            PlayerHearts on = require(target);
            if (on.eliminated()) throw new BountyException(on.name() + " is eliminated.");
            if (from.hearts() - hearts < 1) {
                throw new BountyException("You have " + from.hearts() + " hearts and must keep at least 1.");
            }
            long now = clock.millis();
            HeartChange paid = applyOne(
                    HeartRequest.add(placer, -hearts, HeartCause.BOUNTY_PLACE, from.name()).withOther(target, on.name()),
                    rules, nextEventId(), now);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO bounties (target, placer, hearts, placed_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, target.toString());
                ps.setString(2, placer.toString());
                ps.setInt(3, hearts);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return new BountyPlacement(paid, bountyOn(target));
        });
    }

    /** Pays the killer every open bounty on the victim, as far as the cap allows; the rest stays open. */
    private BountyClaim claimBounties(UUID victim, String victimName, UUID killer, HeartRules rules, long eventId, long now)
            throws SQLException {
        List<long[]> open = openBounties(victim);
        if (open.isEmpty()) return null;
        int pot = 0;
        for (long[] row : open) pot += (int) row[1];
        HeartChange paid = applyOne(
                HeartRequest.add(killer, pot, HeartCause.BOUNTY_CLAIM, "server").withOther(victim, victimName),
                rules, eventId, now);
        int unpaid = pot - paid.delta();
        // Settle oldest first; whatever the cap didn't let through stays on the newest bounties.
        int left = paid.delta();
        for (long[] row : open) {
            int hearts = (int) row[1];
            if (left >= hearts) {
                closeBounty(row[0], killer.toString(), "claimed", now);
                left -= hearts;
            } else {
                if (left > 0) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE bounties SET hearts = ? WHERE id = ?")) {
                        ps.setInt(1, hearts - left);
                        ps.setLong(2, row[0]);
                        ps.executeUpdate();
                    }
                }
                left = 0;
            }
        }
        return new BountyClaim(paid, unpaid);
    }

    /** Admin tool: closes every open bounty on {@code target} and gives each placer their hearts back. */
    public List<HeartChange> clearBounties(UUID target, String actor, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            String targetName = require(target).name();
            long eventId = nextEventId();
            long now = clock.millis();
            List<HeartChange> refunds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, placer, hearts FROM bounties WHERE target = ? AND closed_at IS NULL ORDER BY id")) {
                ps.setString(1, target.toString());
                List<Object[]> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[] {rs.getLong(1), rs.getString(2), rs.getInt(3)});
                }
                for (Object[] row : rows) {
                    refunds.add(applyOne(
                            HeartRequest.add(UUID.fromString((String) row[1]), (Integer) row[2], HeartCause.BOUNTY_REFUND, actor)
                                    .withOther(target, targetName),
                            rules, eventId, now));
                    closeBounty((Long) row[0], null, "refunded", now);
                }
            }
            return refunds;
        });
    }

    /** Total hearts in open bounties on {@code target}. */
    public int bountyOn(UUID target) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(hearts), 0) FROM bounties WHERE target = ? AND closed_at IS NULL")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The biggest open bounties, one total per target. */
    public List<BountyTotal> topBounties(int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT b.target, p.name, SUM(b.hearts) AS total
                FROM bounties b JOIN players p ON p.uuid = b.target
                WHERE b.closed_at IS NULL
                GROUP BY b.target, p.name
                ORDER BY total DESC, p.name
                LIMIT ?""")) {
            ps.setInt(1, limit);
            List<BountyTotal> totals = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) totals.add(new BountyTotal(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3)));
            }
            return totals;
        }
    }

    /** Open bounties on a target, oldest first, as {id, hearts}. */
    private List<long[]> openBounties(UUID target) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, hearts FROM bounties WHERE target = ? AND closed_at IS NULL ORDER BY id")) {
            ps.setString(1, target.toString());
            List<long[]> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new long[] {rs.getLong(1), rs.getInt(2)});
            }
            return rows;
        }
    }

    private void closeBounty(long id, String closedBy, String reason, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE bounties SET closed_at = ?, closed_by = ?, closed_reason = ? WHERE id = ?")) {
            ps.setLong(1, now);
            setNullable(ps, 2, closedBy);
            ps.setString(3, reason);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

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
