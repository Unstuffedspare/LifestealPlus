package dev.lifestealplus.core;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final int SCHEMA_VERSION = 11;

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
            // FULL: a commit is on disk before it counts, even through a power cut. Heart changes are
            // few enough that the extra sync costs nothing noticeable.
            st.execute("PRAGMA synchronous=FULL");
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
        if (version < 4) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                            CREATE TABLE body_deaths (
                                uuid TEXT PRIMARY KEY,
                                at   INTEGER NOT NULL
                            )""");
                    st.execute("PRAGMA user_version = 4");
                }
                return null;
            });
        }
        if (version < 5) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    // Players who already exist aren't new, so they start unshielded.
                    st.execute("ALTER TABLE players ADD COLUMN shield_ms INTEGER NOT NULL DEFAULT 0");
                    st.execute("PRAGMA user_version = 5");
                }
                return null;
            });
        }
        if (version < 6) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                            CREATE TABLE server_state (
                                key   TEXT PRIMARY KEY,
                                value TEXT NOT NULL
                            )""");
                    st.execute("PRAGMA user_version = 6");
                }
                return null;
            });
        }
        if (version < 7) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                            CREATE TABLE logins (
                                uuid         TEXT    NOT NULL,
                                address_hash TEXT    NOT NULL,
                                first_seen   INTEGER NOT NULL,
                                last_seen    INTEGER NOT NULL,
                                PRIMARY KEY (uuid, address_hash)
                            )""");
                    st.execute("CREATE INDEX logins_address ON logins(address_hash)");
                    st.execute("""
                            CREATE TABLE alt_flags (
                                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                                event_id    INTEGER NOT NULL,
                                at          INTEGER NOT NULL,
                                killer      TEXT    NOT NULL,
                                killer_name TEXT    NOT NULL,
                                victim      TEXT    NOT NULL,
                                victim_name TEXT    NOT NULL
                            )""");
                    st.execute("""
                            CREATE TABLE seasons (
                                name        TEXT PRIMARY KEY COLLATE NOCASE,
                                archived_at INTEGER NOT NULL
                            )""");
                    st.execute("""
                            CREATE TABLE season_standings (
                                season       TEXT    NOT NULL,
                                rank         INTEGER NOT NULL,
                                uuid         TEXT    NOT NULL,
                                name         TEXT    NOT NULL,
                                hearts       INTEGER NOT NULL,
                                kills        INTEGER NOT NULL,
                                deaths       INTEGER NOT NULL,
                                eliminations INTEGER NOT NULL,
                                PRIMARY KEY (season, rank)
                            )""");
                    // Same columns as heart_ledger, plus the season they belonged to.
                    st.execute("""
                            CREATE TABLE heart_ledger_archive (
                                season        TEXT NOT NULL,
                                id            INTEGER,
                                event_id      INTEGER,
                                at            INTEGER,
                                player_uuid   TEXT,
                                player_name   TEXT,
                                cause         TEXT,
                                op            TEXT,
                                requested     INTEGER,
                                hearts_before INTEGER,
                                hearts_after  INTEGER,
                                other_uuid    TEXT,
                                other_name    TEXT,
                                actor         TEXT,
                                note          TEXT
                            )""");
                    st.execute("PRAGMA user_version = 7");
                }
                return null;
            });
        }
        if (version < 8) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("""
                            CREATE TABLE revival_pledges (
                                target  TEXT    NOT NULL,
                                pledger TEXT    NOT NULL,
                                hearts  INTEGER NOT NULL,
                                at      INTEGER NOT NULL,
                                PRIMARY KEY (target, pledger)
                            )""");
                    // Each pact is stored once, with the two players in UUID order.
                    st.execute("""
                            CREATE TABLE pacts (
                                a     TEXT    NOT NULL,
                                b     TEXT    NOT NULL,
                                since INTEGER NOT NULL,
                                PRIMARY KEY (a, b)
                            )""");
                    st.execute("""
                            CREATE TABLE pact_cooldowns (
                                uuid  TEXT PRIMARY KEY,
                                until INTEGER NOT NULL
                            )""");
                    st.execute("PRAGMA user_version = 8");
                }
                return null;
            });
        }
        if (version < 9) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    // When the shield's day limit starts counting; empty means from first join.
                    st.execute("ALTER TABLE players ADD COLUMN shield_since INTEGER");
                    st.execute("PRAGMA user_version = 9");
                }
                return null;
            });
        }
        if (version < 10) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE players ADD COLUMN streak INTEGER NOT NULL DEFAULT 0");
                    st.execute("ALTER TABLE players ADD COLUMN best_streak INTEGER NOT NULL DEFAULT 0");
                    st.execute("ALTER TABLE players ADD COLUMN last_left INTEGER");
                    st.execute("""
                            CREATE TABLE news (
                                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                                at     INTEGER NOT NULL,
                                kind   TEXT    NOT NULL,
                                player TEXT,
                                other  TEXT,
                                amount INTEGER NOT NULL DEFAULT 0
                            )""");
                    st.execute("CREATE INDEX news_at ON news (at)");
                    st.execute("PRAGMA user_version = 10");
                }
                return null;
            });
        }
        if (version < 11) {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    // A player's own cap, from a permission; null means the server's cap.
                    st.execute("ALTER TABLE players ADD COLUMN max_hearts INTEGER");
                    st.execute("PRAGMA user_version = 11");
                }
                return null;
            });
        }
    }

    /**
     * Heart changes that happened because of another one: a pact partner's share, a pledge
     * refund. The service hands them to the plugin after each call so they reach players too.
     */
    private final List<HeartChange> sideEffects = new ArrayList<>();

    /** Feature switches from config.yml. Off means the stored data is kept but has no effect. */
    private boolean shieldsOn = true;
    private Duration shieldExpiry = Duration.ZERO;
    private boolean pactsOn = true;
    private boolean altFlagsOn = true;
    private StreakRules streaks = StreakRules.OFF;

    public void setStreaks(StreakRules streaks) {
        this.streaks = streaks;
    }

    /** @param shieldExpiry a shield ends this long after it began, however little was played; zero for no limit */
    public void setFeatures(boolean shields, Duration shieldExpiry, boolean pacts, boolean altFlags) {
        this.shieldsOn = shields;
        this.shieldExpiry = shieldExpiry;
        this.pactsOn = pacts;
        this.altFlagsOn = altFlags;
    }

    /** Every unexpired vengeance mark, soonest to expire first: what the trackers point at. */
    public List<VengeanceMark> liveMarks() throws SQLException {
        return marks("1 = ?", null);
    }

    public List<HeartChange> drainSideEffects() {
        List<HeartChange> drained = List.copyOf(sideEffects);
        sideEffects.clear();
        return drained;
    }

    /** Turns some of a player's own hearts into items. They keep at least one. */
    public HeartChange withdraw(UUID player, int hearts, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            if (hearts < 1) throw new RuleException("Withdraw at least 1 heart.");
            PlayerHearts current = require(player);
            if (current.hearts() - hearts < 1) {
                throw new RuleException("You have " + current.hearts() + " hearts and must keep at least 1.");
            }
            return applyOne(HeartRequest.add(player, -hearts, HeartCause.HEART_WITHDRAW, current.name()),
                    rules, nextEventId(), clock.millis());
        });
    }

    /**
     * One living player pays {@code cost} of their own hearts to bring an eliminated player
     * back with reviveHearts. Returns the rescuer's change, then the revival.
     */
    public List<HeartChange> openRift(UUID rescuer, UUID target, int cost, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            if (rescuer.equals(target)) throw new RuleException("You can't open a rift for yourself.");
            PlayerHearts from = require(rescuer);
            PlayerHearts to = require(target);
            if (!to.eliminated()) throw new RuleException(to.name() + " isn't eliminated.");
            if (from.hearts() - cost < 1) {
                throw new RuleException("A rift costs " + cost + " hearts and you must keep at least 1 (you have " + from.hearts() + ").");
            }
            long eventId = nextEventId();
            long now = clock.millis();
            HeartChange paid = applyOne(HeartRequest.add(rescuer, -cost, HeartCause.RIFT, from.name()).withOther(target, to.name()),
                    rules, eventId, now);
            HeartChange revive = applyOne(HeartRequest.set(target, rules.reviveHearts(), HeartCause.REVIVE, from.name())
                    .withOther(rescuer, from.name()).withNote("rift"), rules, eventId, now);
            return List.of(paid, revive);
        });
    }

    /**
     * Puts some of the pledger's hearts toward reviving an eliminated player. When the pledges
     * reach {@code needed} hearts from at least {@code minVoters} players, the target comes back
     * and the pledges are spent. Pledging again adds to your earlier pledge.
     */
    public PledgeOutcome pledge(UUID pledger, UUID target, int hearts, int needed, int minVoters, HeartRules rules)
            throws SQLException {
        return inTransaction(() -> {
            if (hearts < 1) throw new RuleException("Pledge at least 1 heart.");
            if (pledger.equals(target)) throw new RuleException("You can't pledge for yourself.");
            PlayerHearts from = require(pledger);
            PlayerHearts to = require(target);
            if (!to.eliminated()) throw new RuleException(to.name() + " isn't eliminated.");
            if (from.hearts() - hearts < 1) {
                throw new RuleException("You have " + from.hearts() + " hearts and must keep at least 1.");
            }
            long eventId = nextEventId();
            long now = clock.millis();
            HeartChange paid = applyOne(HeartRequest.add(pledger, -hearts, HeartCause.REVIVAL_PLEDGE, from.name())
                    .withOther(target, to.name()), rules, eventId, now);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO revival_pledges (target, pledger, hearts, at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(target, pledger) DO UPDATE SET hearts = hearts + excluded.hearts, at = excluded.at""")) {
                ps.setString(1, target.toString());
                ps.setString(2, pledger.toString());
                ps.setInt(3, hearts);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            PledgeTotal total = pledgeTotal(target, to.name());
            HeartChange revive = null;
            if (total.hearts() >= needed && total.voters() >= minVoters) {
                // Spent, not refunded: clear them before the revival so its refund hook finds none.
                deletePledges(target);
                revive = applyOne(HeartRequest.set(target, rules.reviveHearts(), HeartCause.REVIVE, "revival vote")
                        .withNote(total.voters() + " players pledged " + total.hearts() + " hearts"), rules, eventId, now);
            }
            return new PledgeOutcome(paid, total, revive);
        });
    }

    /** Takes back your pledge for a target and refunds it. */
    public HeartChange cancelPledge(UUID pledger, UUID target, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            int pledged;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT hearts FROM revival_pledges WHERE target = ? AND pledger = ?")) {
                ps.setString(1, target.toString());
                ps.setString(2, pledger.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new RuleException("You haven't pledged anything for " + require(target).name() + ".");
                    pledged = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM revival_pledges WHERE target = ? AND pledger = ?")) {
                ps.setString(1, target.toString());
                ps.setString(2, pledger.toString());
                ps.executeUpdate();
            }
            return applyOne(HeartRequest.add(pledger, pledged, HeartCause.REVIVAL_REFUND, require(pledger).name())
                    .withOther(target, require(target).name()), rules, nextEventId(), clock.millis());
        });
    }

    /** Every eliminated player someone has pledged for, most hearts first. */
    public List<PledgeTotal> pledges() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT r.target, p.name, SUM(r.hearts) AS total, COUNT(*) AS voters
                FROM revival_pledges r JOIN players p ON p.uuid = r.target
                GROUP BY r.target, p.name ORDER BY total DESC, p.name""")) {
            List<PledgeTotal> totals = new ArrayList<>();
            while (rs.next()) {
                totals.add(new PledgeTotal(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4)));
            }
            return totals;
        }
    }

    private PledgeTotal pledgeTotal(UUID target, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(hearts), 0), COUNT(*) FROM revival_pledges WHERE target = ?")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new PledgeTotal(target, name, rs.getInt(1), rs.getInt(2));
            }
        }
    }

    private void deletePledges(UUID target) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM revival_pledges WHERE target = ?")) {
            ps.setString(1, target.toString());
            ps.executeUpdate();
        }
    }

    /** The target came back some other way: everyone who pledged gets their hearts back. */
    private void refundPledges(UUID target, String targetName, HeartRules rules, long eventId, long now) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT pledger, hearts FROM revival_pledges WHERE target = ?")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Object[] {rs.getString(1), rs.getInt(2)});
            }
        }
        if (rows.isEmpty()) return;
        deletePledges(target);
        for (Object[] row : rows) {
            sideEffects.add(applyOne(HeartRequest.add(UUID.fromString((String) row[0]), (Integer) row[1], HeartCause.REVIVAL_REFUND,
                    "server").withOther(target, targetName).withNote("came back another way"), rules, eventId, now));
        }
    }

    /** Links two players' fates. Both must be alive, unshielded, unpacted and off cooldown. */
    public void formPact(UUID first, UUID second) throws SQLException {
        inTransaction(() -> {
            if (first.equals(second)) throw new RuleException("You can't make a pact with yourself.");
            PlayerHearts a = require(first), b = require(second);
            long now = clock.millis();
            for (PlayerHearts p : List.of(a, b)) {
                if (p.eliminated()) throw new RuleException(p.name() + " is eliminated.");
                if (!shieldLeft(p.uuid()).isZero()) throw new RuleException(p.name() + " still has a new-player shield.");
                if (pactPartner(p.uuid()).isPresent()) throw new RuleException(p.name() + " is already in a Blood Pact.");
                try (PreparedStatement ps = conn.prepareStatement("SELECT until FROM pact_cooldowns WHERE uuid = ? AND until > ?")) {
                    ps.setString(1, p.uuid().toString());
                    ps.setLong(2, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) throw new RuleException(p.name() + " broke a pact recently and can't make another yet.");
                    }
                }
            }
            boolean ordered = first.toString().compareTo(second.toString()) < 0;
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pacts (a, b, since) VALUES (?, ?, ?)")) {
                ps.setString(1, (ordered ? first : second).toString());
                ps.setString(2, (ordered ? second : first).toString());
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Ends the player's pact; they can't make a new one for {@code cooldown}. Returns who they were bound to. */
    public PlayerHearts breakPact(UUID player, java.time.Duration cooldown) throws SQLException {
        return inTransaction(() -> {
            PlayerHearts partner = pactPartner(player)
                    .orElseThrow(() -> new RuleException("You're not in a Blood Pact."));
            dissolvePact(player);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO pact_cooldowns (uuid, until) VALUES (?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET until = excluded.until""")) {
                ps.setString(1, player.toString());
                ps.setLong(2, clock.millis() + cooldown.toMillis());
                ps.executeUpdate();
            }
            return partner;
        });
    }

    /**
     * Brings players over from another plugin, all in one transaction (one event in the
     * ledger). Players new here are created without a newbie shield, since they aren't new;
     * players already here are set to the imported hearts. Running it again changes nothing.
     */
    public ImportResult importPlayers(List<LifestealZImport.Planned> players, String source, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            long eventId = nextEventId();
            long now = clock.millis();
            int created = 0, updated = 0;
            for (LifestealZImport.Planned p : players) {
                if (find(p.uuid()).isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO players (uuid, name, hearts, first_seen, last_seen) VALUES (?, ?, 0, ?, ?)")) {
                        ps.setString(1, p.uuid().toString());
                        ps.setString(2, p.name());
                        ps.setLong(3, now);
                        ps.setLong(4, now);
                        ps.executeUpdate();
                    }
                    created++;
                } else {
                    updated++;
                }
                applyOne(HeartRequest.set(p.uuid(), p.hearts(), HeartCause.IMPORT, source)
                        .withNote(p.eliminated() ? "eliminated in " + source : p.capped() ? "brought down to the cap" : null),
                        rules, eventId, now);
            }
            return new ImportResult(created, updated);
        });
    }

    /** Every pact on the server, for /pact list and the tab list. */
    public List<PactPair> allPacts() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT k.a, pa.name, k.b, pb.name FROM pacts k
                JOIN players pa ON pa.uuid = k.a JOIN players pb ON pb.uuid = k.b
                ORDER BY k.since""")) {
            List<PactPair> pacts = new ArrayList<>();
            while (rs.next()) {
                pacts.add(new PactPair(UUID.fromString(rs.getString(1)), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4)));
            }
            return pacts;
        }
    }

    public Optional<PlayerHearts> pactPartner(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CASE WHEN a = ? THEN b ELSE a END FROM pacts WHERE a = ? OR b = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, player.toString());
            ps.setString(3, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return find(UUID.fromString(rs.getString(1)));
            }
        }
    }

    private void dissolvePact(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM pacts WHERE a = ? OR b = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    /**
     * A pact partner shares in gains from kills and bounties (the same amount) and in deaths
     * (one heart). Never between the two partners themselves, and never from a share.
     */
    private void sharePact(HeartRequest request, HeartChange change, HeartRules rules, long eventId, long now)
            throws SQLException {
        if (!pactsOn || change.delta() == 0 || request.cause() == HeartCause.PACT_SHARE) return;
        int share = switch (request.cause()) {
            case PVP_KILL, BOUNTY_CLAIM -> Math.max(0, change.delta());
            case PVP_DEATH, DEATH -> change.delta() < 0 ? -1 : 0;
            default -> 0;
        };
        if (share == 0) return;
        Optional<PlayerHearts> partner = pactPartner(request.player());
        if (partner.isEmpty() || partner.get().uuid().equals(request.otherUuid())) return;
        sideEffects.add(applyOne(HeartRequest.add(partner.get().uuid(), share, HeartCause.PACT_SHARE, "server")
                .withOther(request.player(), change.name()), rules, eventId, now));
    }

    /**
     * Remembers that a player logged in from an address. Only a salted hash of the address is
     * ever stored, so admins can see two accounts share one without anyone seeing an IP.
     */
    public void recordLogin(UUID player, String addressHash) throws SQLException {
        long now = clock.millis();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO logins (uuid, address_hash, first_seen, last_seen) VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, address_hash) DO UPDATE SET last_seen = excluded.last_seen""")) {
            ps.setString(1, player.toString());
            ps.setString(2, addressHash);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /** Other accounts that have logged in from any address this one has. Links outlive season resets. */
    public List<PlayerHearts> linkedAccounts(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT p.uuid, p.name, p.hearts
                FROM logins mine
                JOIN logins theirs ON theirs.address_hash = mine.address_hash AND theirs.uuid <> mine.uuid
                JOIN players p ON p.uuid = theirs.uuid
                WHERE mine.uuid = ?
                ORDER BY p.name COLLATE NOCASE""")) {
            ps.setString(1, player.toString());
            List<PlayerHearts> linked = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) linked.add(new PlayerHearts(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3)));
            }
            return linked;
        }
    }

    private boolean areLinked(UUID a, UUID b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM logins x JOIN logins y ON y.address_hash = x.address_hash
                WHERE x.uuid = ? AND y.uuid = ? LIMIT 1""")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Newest first. */
    public List<AltFlag> altFlags(int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT event_id, at, killer, killer_name, victim, victim_name FROM alt_flags ORDER BY id DESC LIMIT ?""")) {
            ps.setInt(1, limit);
            List<AltFlag> flags = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    flags.add(new AltFlag(rs.getLong(1), Instant.ofEpochMilli(rs.getLong(2)), UUID.fromString(rs.getString(3)),
                            rs.getString(4), UUID.fromString(rs.getString(5)), rs.getString(6)));
                }
            }
            return flags;
        }
    }

    /**
     * Ends the season: writes its final standings and full ledger to the archive under
     * {@code name}, then clears every player and everything that hangs off them, so the next
     * join starts fresh. Logins and alt flags are kept; links matter across seasons.
     */
    public List<SeasonStanding> archiveSeason(String name) throws SQLException {
        return archiveSeason(name, Map.of());
    }

    /**
     * Archives the season and clears the live tables, and saves {@code nextState} (the new
     * season's name and start), all in one transaction: a crash part way leaves either the old
     * season untouched or the new one fully begun, never an archived season still in use.
     */
    public List<SeasonStanding> archiveSeason(String name, Map<String, String> nextState) throws SQLException {
        return inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM seasons WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) throw new IllegalArgumentException("A season called \"" + name + "\" has already been archived.");
                }
            }
            List<SeasonStanding> standings = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                    SELECT p.uuid, p.name, p.hearts,
                           (SELECT COUNT(*) FROM heart_ledger l WHERE l.cause = 'PVP_DEATH' AND l.other_uuid = p.uuid
                                AND l.hearts_after < l.hearts_before) AS kills,
                           (SELECT COUNT(*) FROM heart_ledger l WHERE l.player_uuid = p.uuid AND l.cause IN ('PVP_DEATH', 'DEATH')
                                AND l.hearts_after < l.hearts_before) AS deaths,
                           (SELECT COUNT(*) FROM heart_ledger l WHERE l.player_uuid = p.uuid AND l.hearts_before > 0
                                AND l.hearts_after <= 0) AS eliminations
                    FROM players p
                    ORDER BY p.hearts DESC, kills DESC, p.name COLLATE NOCASE""")) {
                int rank = 0;
                while (rs.next()) {
                    standings.add(new SeasonStanding(++rank, UUID.fromString(rs.getString(1)), rs.getString(2),
                            rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO season_standings (season, rank, uuid, name, hearts, kills, deaths, eliminations)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (SeasonStanding s : standings) {
                    ps.setString(1, name);
                    ps.setInt(2, s.rank());
                    ps.setString(3, s.uuid().toString());
                    ps.setString(4, s.name());
                    ps.setInt(5, s.hearts());
                    ps.setInt(6, s.kills());
                    ps.setInt(7, s.deaths());
                    ps.setInt(8, s.eliminations());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO seasons (name, archived_at) VALUES (?, ?)")) {
                ps.setString(1, name);
                ps.setLong(2, clock.millis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO heart_ledger_archive SELECT ?, * FROM heart_ledger")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            try (Statement st = conn.createStatement()) {
                for (String table : List.of("heart_ledger", "players", "purgatory_progress", "vengeance_marks", "bounties",
                        "body_deaths", "revival_pledges", "pacts", "pact_cooldowns")) {
                    st.execute("DELETE FROM " + table);
                }
            }
            for (Map.Entry<String, String> state : nextState.entrySet()) putState(state.getKey(), state.getValue());
            return standings;
        });
    }

    public List<SeasonStanding> seasonStandings(String season) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT rank, uuid, name, hearts, kills, deaths, eliminations FROM season_standings
                WHERE season = ? COLLATE NOCASE ORDER BY rank""")) {
            ps.setString(1, season);
            List<SeasonStanding> standings = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    standings.add(new SeasonStanding(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)));
                }
            }
            return standings;
        }
    }

    /** Archived seasons, oldest first. */
    public List<String> seasons() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM seasons ORDER BY archived_at")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) names.add(rs.getString(1));
            return names;
        }
    }

    public int archivedLedgerSize(String season) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM heart_ledger_archive WHERE season = ? COLLATE NOCASE")) {
            ps.setString(1, season);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The live leaderboard: players still in the game, most hearts first. */
    public List<PlayerHearts> topHearts(int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, name, hearts FROM players WHERE hearts > 0 ORDER BY hearts DESC, name COLLATE NOCASE LIMIT ?")) {
            ps.setInt(1, limit);
            List<PlayerHearts> top = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) top.add(new PlayerHearts(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3)));
            }
            return top;
        }
    }

    /** Small named values that have to survive restarts, like when the next server event is due. */
    public Optional<String> state(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM server_state WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Forgets which addresses players used, for logins not seen in {@code keep}. Returns how many were removed. */
    public int pruneLogins(Duration keep) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM logins WHERE last_seen < ?")) {
            ps.setLong(1, clock.millis() - keep.toMillis());
            return ps.executeUpdate();
        }
    }

    public void putState(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO server_state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public void clearState(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM server_state WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    /** Players with at least one heart, online or not. */
    public int aliveCount() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM players WHERE hearts > 0")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** The only player with hearts left, if exactly one is. */
    public Optional<PlayerHearts> lastStanding() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, hearts FROM players WHERE hearts > 0 LIMIT 2")) {
            if (!rs.next()) return Optional.empty();
            PlayerHearts first = new PlayerHearts(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3));
            return rs.next() ? Optional.empty() : Optional.of(first);
        }
    }

    /**
     * Playtime left on the player's newbie shield; zero once it's worn off, been broken, or
     * passed its day limit (counted from first join, or from when an admin last granted one).
     */
    public Duration shieldLeft(UUID player) throws SQLException {
        if (!shieldsOn) return Duration.ZERO;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT shield_ms, COALESCE(shield_since, first_seen) FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Duration.ZERO;
                boolean expired = !shieldExpiry.isZero() && clock.millis() - rs.getLong(2) >= shieldExpiry.toMillis();
                return expired ? Duration.ZERO : Duration.ofMillis(rs.getLong(1));
            }
        }
    }

    /** Wears the shield down by {@code played}; returns what's left. */
    public Duration spendShield(UUID player, Duration played) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET shield_ms = MAX(0, shield_ms - ?) WHERE uuid = ?")) {
            ps.setLong(1, played.toMillis());
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
        return shieldLeft(player);
    }

    /** Ends the shield for good (the player attacked someone). True if there was one to break. */
    public boolean breakShield(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET shield_ms = 0 WHERE uuid = ? AND shield_ms > 0")) {
            ps.setString(1, player.toString());
            return ps.executeUpdate() > 0;
        }
    }

    /** Admin tool: give or take away a shield. A granted shield gets a fresh day limit. */
    public void setShield(UUID player, Duration left) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET shield_ms = ?, shield_since = ? WHERE uuid = ?")) {
            ps.setLong(1, Math.max(0, left.toMillis()));
            ps.setLong(2, clock.millis());
            ps.setString(3, player.toString());
            ps.executeUpdate();
        }
    }

    /** A combat-logger's body was killed while they were offline; their next login finishes the death. */
    public void recordBodyDeath(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO body_deaths (uuid, at) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET at = excluded.at")) {
            ps.setString(1, player.toString());
            ps.setLong(2, clock.millis());
            ps.executeUpdate();
        }
    }

    /** Whether the player's body died while they were away. Clears the record, so it's only true once. */
    public boolean takeBodyDeath(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM body_deaths WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            return ps.executeUpdate() > 0;
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
            setShield(uuid, rules.newbieShield());
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
            int victimStreak = streaks.enabled() ? streakOf(victim) : 0;
            int ended = victimStreak >= streaks.noteworthy() ? victimStreak : 0;
            if (ended > 0) news(now, NewsItem.STREAK_ENDED, victimName, killerName, ended);
            boolean linked = altFlagsOn && areLinked(victim, killer);
            if (linked) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO alt_flags (event_id, at, killer, killer_name, victim, victim_name)
                        VALUES (?, ?, ?, ?, ?, ?)""")) {
                    ps.setLong(1, eventId);
                    ps.setLong(2, now);
                    ps.setString(3, killer.toString());
                    ps.setString(4, killerName);
                    ps.setString(5, victim.toString());
                    ps.setString(6, victimName);
                    ps.executeUpdate();
                }
            }

            if (!shieldLeft(victim).isZero() || !shieldLeft(killer).isZero()) {
                HeartChange loss = applyOne(
                        HeartRequest.add(victim, -rules.heartsPerKill(), HeartCause.PVP_DEATH, "server")
                                .withOther(killer, killerName).withNote(NEWBIE_SHIELD),
                        rules, eventId, now, true);
                HeartChange gain = applyOne(
                        HeartRequest.add(killer, rules.heartsPerKill(), HeartCause.PVP_KILL, "server")
                                .withOther(victim, victimName).withNote(NEWBIE_SHIELD),
                        rules, eventId, now, true);
                return new KillOutcome(loss, gain, false, false, false, null, true, linked, streakOf(killer), ended, false);
            }

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
                return new KillOutcome(loss, gain, true, false, false, bounty, false, linked, streakOf(killer), ended, false);
            }

            boolean vengeance = consumeMark(victim, killer, now);
            boolean shutdown = streaks.shutdownApplies(victimStreak);
            String note = vengeance && shutdown ? VENGEANCE + ", " + SHUTDOWN : vengeance ? VENGEANCE : shutdown ? SHUTDOWN : null;
            int amount = rules.heartsPerKill() + (vengeance ? rules.vengeanceBonus() : 0) + (shutdown ? streaks.shutdownBonus() : 0);
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
            int killerStreak = streaks.enabled() && taken > 0 ? extendStreak(killer) : streakOf(killer);
            if (taken > 0 && streaks.announceAt().contains(killerStreak)) news(now, NewsItem.STREAK, killerName, null, killerStreak);
            return new KillOutcome(loss, gain, false, vengeance, marked, bounty, false, linked, killerStreak, ended, shutdown);
        });
    }

    private static final String REPEAT_KILL = "repeat kill";
    private static final String VENGEANCE = "vengeance";
    private static final String SHUTDOWN = "streak shutdown";
    private static final String NEWBIE_SHIELD = "newbie shield";

    /** Heart changes a newbie shield stops: deaths, and anything won from other players. Admin changes still apply. */
    private static boolean shieldStops(HeartCause cause) {
        return switch (cause) {
            case DEATH, PVP_DEATH, PVP_KILL, BOUNTY_CLAIM -> true;
            default -> false;
        };
    }

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
            if (who == null) ps.setInt(1, 1);
            else ps.setString(1, who.toString());
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
            if (!shieldLeft(placer).isZero()) {
                throw new BountyException("You can't place bounties while your new-player shield is up.");
            }
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
            int total = bountyOn(target);
            news(now, NewsItem.BOUNTY, on.name(), null, total);
            return new BountyPlacement(paid, total);
        });
    }

    /**
     * Takes back the placer's own unclaimed bounties on {@code target} placed within the last
     * {@code window}, refunded in full. Older ones stay where they are.
     */
    public BountyUndo undoBounty(UUID placer, UUID target, Duration window, HeartRules rules) throws SQLException {
        return inTransaction(() -> {
            PlayerHearts from = require(placer);
            PlayerHearts on = require(target);
            if (from.eliminated()) throw new BountyException("You can't take back a bounty while you're eliminated.");
            long now = clock.millis();
            List<long[]> mine = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, hearts, placed_at FROM bounties
                    WHERE target = ? AND placer = ? AND closed_at IS NULL ORDER BY id""")) {
                ps.setString(1, target.toString());
                ps.setString(2, placer.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) mine.add(new long[] {rs.getLong(1), rs.getInt(2), rs.getLong(3)});
                }
            }
            if (mine.isEmpty()) throw new BountyException("You don't have a bounty on " + on.name() + ".");
            List<long[]> recent = mine.stream().filter(row -> row[2] >= now - window.toMillis()).toList();
            if (recent.isEmpty()) {
                throw new BountyException("A bounty can only be taken back in the first " + window.toMinutes()
                        + " minute(s) after placing it.");
            }
            int refund = recent.stream().mapToInt(row -> (int) row[1]).sum();
            int cap = personalRules(placer, rules).maxHearts();
            if (from.hearts() + refund > cap) {
                throw new BountyException("Taking it back would put you over the cap of " + cap + " hearts.");
            }
            HeartChange back = applyOne(HeartRequest.add(placer, refund, HeartCause.BOUNTY_REFUND, from.name())
                    .withOther(target, on.name()).withNote("taken back"), rules, nextEventId(), now);
            for (long[] row : recent) closeBounty(row[0], placer.toString(), "taken back", now);
            int total = bountyOn(target);
            news(now, total == 0 ? NewsItem.BOUNTY_WITHDRAWN : NewsItem.BOUNTY_CUT, on.name(), null, total);
            return new BountyUndo(back, refund, total);
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

    /** The rules with the player's own cap, if a permission gave them one. */
    private HeartRules personalRules(UUID player, HeartRules rules) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT max_hearts FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return rules;
                int cap = rs.getInt(1);
                return rs.wasNull() ? rules : rules.personalCap(cap);
            }
        }
    }

    /** Sets (or with null clears) a player's own cap. Lowering it never takes hearts away. */
    public void setPersonalCap(UUID player, Integer cap) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET max_hearts = ? WHERE uuid = ?")) {
            if (cap == null) ps.setNull(1, java.sql.Types.INTEGER);
            else ps.setInt(1, cap);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    /** {@code blocked} logs the request without changing anything (a rule refused it). */
    private HeartChange applyOne(HeartRequest request, HeartRules rules, long eventId, long now, boolean blocked)
            throws SQLException {
        PlayerHearts current = require(request.player());
        int before = current.hearts();
        String note = request.note();
        if (!blocked && shieldStops(request.cause()) && !shieldLeft(request.player()).isZero()) {
            blocked = true;
            note = NEWBIE_SHIELD;
        }
        int after = blocked ? before : personalRules(request.player(), rules).resolve(before, request);
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
        HeartChange change = new HeartChange(ledgerId, eventId, request.player(), current.name(), request.cause(), before, after,
                request.otherName());
        if (!blocked) sharePact(request, change, rules, eventId, now);
        HeartCause cause = request.cause();
        if (cause == HeartCause.DEATH || cause == HeartCause.PVP_DEATH || change.eliminatedNow()) endStreak(request.player());
        if (cause != HeartCause.IMPORT && change.eliminatedNow()) {
            boolean byPlayer = cause == HeartCause.PVP_DEATH && request.otherName() != null;
            news(now, byPlayer ? NewsItem.ELIMINATED_BY : NewsItem.ELIMINATED, current.name(), byPlayer ? request.otherName() : null, 0);
        }
        // A first join goes from 0 to the starting hearts, which isn't a comeback.
        if (cause != HeartCause.IMPORT && cause != HeartCause.FIRST_JOIN && change.revivedNow()) {
            news(now, NewsItem.REVIVED, current.name(), null, after);
        }
        if (change.eliminatedNow()) dissolvePact(request.player());
        if (change.revivedNow()) refundPledges(request.player(), current.name(), rules, eventId, now);
        return change;
    }

    private int streakOf(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT streak FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** One more kill in a row; returns the new length. */
    private int extendStreak(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET streak = streak + 1, best_streak = MAX(best_streak, streak + 1) WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
        return streakOf(player);
    }

    /** A death the rules didn't charge for (no hearts lost) still ends the streak. */
    public void endStreak(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET streak = 0 WHERE uuid = ? AND streak > 0")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
    }

    public Optional<PlayerStreak> streak(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, streak, best_streak FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new PlayerStreak(rs.getString(1), rs.getInt(2), rs.getInt(3))) : Optional.empty();
            }
        }
    }

    private void news(long at, String kind, String player, String other, int amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO news (at, kind, player, other, amount) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, at);
            ps.setString(2, kind);
            setNullable(ps, 3, player);
            setNullable(ps, 4, other);
            ps.setInt(5, amount);
            ps.executeUpdate();
        }
    }

    /** News from outside the heart rules: Blood Moons, airdrops, the finale. */
    public void addNews(String kind, String player, String other, int amount) throws SQLException {
        news(clock.millis(), kind, player, other, amount);
    }

    /** The newest news first. */
    /** The news written after row {@code afterId}, oldest first, each with its row id. */
    public List<Map.Entry<Long, NewsItem>> newsAfter(long afterId, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, at, kind, player, other, amount FROM news WHERE id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, afterId);
            ps.setInt(2, limit);
            List<Map.Entry<Long, NewsItem>> items = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(Map.entry(rs.getLong(1), new NewsItem(Instant.ofEpochMilli(rs.getLong(2)), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getInt(6))));
                }
            }
            return items;
        }
    }

    /** The newest news row's id, or 0 if there's none. */
    public long lastNewsId() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM news")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<NewsItem> latestNews(int limit) throws SQLException {
        return newsSince(0, limit);
    }

    private List<NewsItem> newsSince(long since, int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT at, kind, player, other, amount FROM news WHERE at > ? ORDER BY at DESC, id DESC LIMIT ?")) {
            ps.setLong(1, since);
            ps.setInt(2, limit);
            List<NewsItem> items = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new NewsItem(Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5)));
                }
            }
            return items;
        }
    }

    public void markLeft(UUID player) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET last_left = ? WHERE uuid = ?")) {
            ps.setLong(1, clock.millis());
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    /**
     * What happened since the player last left, if they were away at least {@code minAway} and
     * anything happened; empty otherwise (including for players who've never left).
     */
    public Optional<Recap> recap(UUID player, Duration minAway, int limit) throws SQLException {
        long left;
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_left FROM players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                left = rs.getLong(1);
                if (rs.wasNull()) return Optional.empty();
            }
        }
        Duration away = Duration.ofMillis(clock.millis() - left);
        if (away.compareTo(minAway) < 0) return Optional.empty();
        int total;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM news WHERE at > ?")) {
            ps.setLong(1, left);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getInt(1);
            }
        }
        if (total == 0) return Optional.empty();
        return Optional.of(new Recap(away, newsSince(left, limit), total));
    }

    /** Forgets news older than {@code keep}; returns how much went. */
    public int pruneNews(Duration keep) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM news WHERE at < ?")) {
            ps.setLong(1, clock.millis() - keep.toMillis());
            return ps.executeUpdate();
        }
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
    /**
     * A player's record for the live season ({@code season} null) or an archived one, found by
     * name. Empty if there's no such season, or the player has no history in it.
     */
    public Optional<PlayerStats> stats(String name, String season) throws SQLException {
        String table = season == null ? "heart_ledger" : "heart_ledger_archive";
        String inSeason = season == null ? "" : " AND season = ?2 COLLATE NOCASE";
        String uuid = null;
        String shownName = name;
        int bestStreak = -1;
        if (season == null) {
            Optional<PlayerHearts> live = findByName(name);
            if (live.isEmpty()) return Optional.empty();
            uuid = live.get().uuid().toString();
            shownName = live.get().name();
            bestStreak = streak(live.get().uuid()).map(PlayerStreak::best).orElse(0);
        } else {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT player_uuid, player_name FROM heart_ledger_archive
                    WHERE season = ? COLLATE NOCASE AND player_name = ? COLLATE NOCASE LIMIT 1""")) {
                ps.setString(1, season);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    uuid = rs.getString(1);
                    shownName = rs.getString(2);
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT
                  COALESCE(SUM(CASE WHEN cause = 'PVP_DEATH' AND other_uuid = ?1 AND hearts_after < hearts_before THEN 1 END), 0),
                  COALESCE(SUM(CASE WHEN player_uuid = ?1 AND cause IN ('PVP_DEATH', 'DEATH') AND hearts_after < hearts_before
                                    THEN 1 END), 0),
                  COALESCE(SUM(CASE WHEN player_uuid = ?1 AND cause = 'PVP_KILL' AND hearts_after > hearts_before
                                    THEN hearts_after - hearts_before END), 0),
                  COALESCE(SUM(CASE WHEN player_uuid = ?1 AND cause IN ('PVP_DEATH', 'DEATH') AND hearts_after < hearts_before
                                    THEN hearts_before - hearts_after END), 0),
                  COALESCE(SUM(CASE WHEN player_uuid = ?1 AND cause = 'BOUNTY_CLAIM' THEN hearts_after - hearts_before END), 0),
                  COALESCE(SUM(CASE WHEN player_uuid = ?1 AND hearts_before > 0 AND hearts_after <= 0 THEN 1 END), 0),
                  COALESCE(SUM(CASE WHEN player_uuid = ?1 AND hearts_before <= 0 AND hearts_after > 0
                                    AND cause NOT IN ('FIRST_JOIN', 'IMPORT') THEN 1 END), 0)
                FROM %s WHERE (player_uuid = ?1 OR other_uuid = ?1)%s""".formatted(table, inSeason))) {
            ps.setString(1, uuid);
            if (season != null) ps.setString(2, season);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String seasonName = season != null ? season : state("season.name").orElse("Season 1");
                return Optional.of(new PlayerStats(shownName, seasonName, rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), bestStreak));
            }
        }
    }

    /** The heart economy now, and where hearts came from and went over the last {@code window}. */
    public EconomyReport economy(Duration window) throws SQLException {
        int alive = 0, eliminated = 0, inPlay = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT COALESCE(SUM(CASE WHEN hearts > 0 THEN 1 END), 0), COALESCE(SUM(CASE WHEN hearts <= 0 THEN 1 END), 0),
                       COALESCE(SUM(CASE WHEN hearts > 0 THEN hearts END), 0)
                FROM players""")) {
            if (rs.next()) {
                alive = rs.getInt(1);
                eliminated = rs.getInt(2);
                inPlay = rs.getInt(3);
            }
        }
        int bounties;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(hearts), 0) FROM bounties WHERE closed_at IS NULL")) {
            bounties = rs.next() ? rs.getInt(1) : 0;
        }
        Map<HeartCause, Integer> gained = new java.util.LinkedHashMap<>();
        Map<HeartCause, Integer> lost = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT cause, SUM(CASE WHEN hearts_after > hearts_before THEN hearts_after - hearts_before ELSE 0 END) AS up,
                       SUM(CASE WHEN hearts_after < hearts_before THEN hearts_before - hearts_after ELSE 0 END) AS down
                FROM heart_ledger WHERE at >= ? GROUP BY cause""")) {
            ps.setLong(1, clock.millis() - window.toMillis());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HeartCause cause;
                    try {
                        cause = HeartCause.valueOf(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    if (rs.getInt(2) > 0) gained.put(cause, rs.getInt(2));
                    if (rs.getInt(3) > 0) lost.put(cause, rs.getInt(3));
                }
            }
        }
        return new EconomyReport(alive, eliminated, inPlay, bounties, biggestFirst(gained), biggestFirst(lost));
    }

    private static Map<HeartCause, Integer> biggestFirst(Map<HeartCause, Integer> byCause) {
        Map<HeartCause, Integer> sorted = new java.util.LinkedHashMap<>();
        byCause.entrySet().stream().sorted(Map.Entry.<HeartCause, Integer>comparingByValue().reversed())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

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
