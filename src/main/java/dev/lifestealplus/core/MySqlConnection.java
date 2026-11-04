package dev.lifestealplus.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Lets the store, which is written for SQLite, run on MySQL or MariaDB: SQL is rewritten as
 * it's prepared (see {@link #translate}), and a dropped connection is replaced between
 * transactions, since MySQL closes connections that sit idle.
 *
 * <p>Transactions are SERIALIZABLE: every row a transaction reads stays locked until it ends, so
 * two servers changing the same player can't both read the old value and overwrite each other.
 * One of them waits, or is rolled back as a deadlock and tried again by the store.
 */
final class MySqlConnection implements InvocationHandler {

    private static final long CHECK_EVERY_MS = 30_000;
    /** Columns whose values can be long; every other SQLite TEXT column becomes a VARCHAR. */
    private static final Set<String> LONG_TEXT = Set.of("note", "detail", "value");
    private static final Pattern UPSERT = Pattern.compile("ON CONFLICT\\s*\\([^)]*\\)\\s*DO UPDATE SET");
    private static final Pattern EXCLUDED = Pattern.compile("excluded\\.(\\w+)");
    private static final Pattern TEXT_COLUMN = Pattern.compile("(\\w+)(\\s+)TEXT\\b");
    /** Column names MySQL reserves, quoted wherever they're used. */
    private static final Pattern RESERVED = Pattern.compile("(?<![`\\w.])(key|rank|until|value)(?![`\\w(])");
    private static final Pattern VERSION_SET = Pattern.compile("PRAGMA user_version\\s*=\\s*(\\d+)");
    /** SQLite's two-argument MAX and MIN (the larger or smaller of two values); the aggregates are left alone. */
    private static final Pattern SCALAR_MAX = Pattern.compile("\\bMAX\\(([^(),]+),([^()]+)\\)");
    private static final Pattern SCALAR_MIN = Pattern.compile("\\bMIN\\(([^(),]+),([^()]+)\\)");

    private final DataSource source;
    private Connection real;
    private boolean autoCommit = true;
    private long lastChecked;

    private MySqlConnection(DataSource source) throws SQLException {
        this.source = source;
        this.real = connect();
        this.lastChecked = System.currentTimeMillis();
    }

    private Connection connect() throws SQLException {
        Connection fresh = source.getConnection();
        fresh.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        return fresh;
    }

    static Connection open(DataSource source) throws SQLException {
        MySqlConnection handler = new MySqlConnection(source);
        Connection proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
        try (Statement st = proxy.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_info (version INT NOT NULL)");
            st.execute("INSERT INTO schema_info (version) SELECT 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM schema_info)");
        }
        return proxy;
    }

    /** The live connection, replaced if MySQL dropped it. Never mid-transaction: that has to fail, not half-commit. */
    private Connection real() throws SQLException {
        long now = System.currentTimeMillis();
        if (autoCommit && now - lastChecked > CHECK_EVERY_MS) {
            lastChecked = now;
            if (real.isClosed() || !real.isValid(2)) reconnect();
        }
        return real;
    }

    private void reconnect() throws SQLException {
        try {
            real.close();
        } catch (SQLException ignored) {
            // Already gone.
        }
        real = connect();
        lastChecked = System.currentTimeMillis();
    }

    /**
     * Starting or ending a transaction. The connection is checked before a transaction starts
     * (while it's still safe to swap), and the state only changes once the switch worked.
     */
    private Object switchAutoCommit(Method method, Object[] args) throws Throwable {
        boolean on = (Boolean) args[0];
        try {
            Object result = method.invoke(real(), args);
            autoCommit = on;
            return result;
        } catch (InvocationTargetException e) {
            if (!on && (real.isClosed() || !real.isValid(2))) {
                // Dropped since the last check: nothing has happened in this transaction yet, so start it on a new one.
                reconnect();
                Object result = method.invoke(real, args);
                autoCommit = false;
                return result;
            }
            // Either way we're no longer in a transaction; check the connection before the next one.
            autoCommit = true;
            lastChecked = 0;
            throw e.getCause();
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getName().equals("setAutoCommit")) return switchAutoCommit(method, args);
        if (args != null && args.length > 0 && args[0] instanceof String sql
                && (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))) {
            args[0] = translate(sql);
        }
        try {
            Object result = method.invoke(real(), args);
            if (result instanceof Statement statement && method.getName().equals("createStatement")) {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                        (p, m, a) -> {
                            if (a != null && a.length > 0 && a[0] instanceof String sql) a[0] = translate(sql);
                            try {
                                return m.invoke(statement, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
            return result;
        } catch (InvocationTargetException e) {
            // Outside a transaction a failure may be a dropped connection: look before the next statement.
            if (autoCommit) lastChecked = 0;
            throw e.getCause();
        }
    }

    /** SQLite's SQL, as MySQL and MariaDB want it. */
    static String translate(String sql) {
        String s = sql.strip();
        Matcher version = VERSION_SET.matcher(s);
        if (version.matches()) return "UPDATE schema_info SET version = " + version.group(1);
        if (s.equals("PRAGMA user_version")) return "SELECT version FROM schema_info";
        if (s.startsWith("PRAGMA")) return "DO 0";

        s = s.replace(" COLLATE NOCASE", "");
        s = UPSERT.matcher(s).replaceAll("ON DUPLICATE KEY UPDATE");
        s = EXCLUDED.matcher(s).replaceAll("VALUES($1)");
        s = SCALAR_MAX.matcher(s).replaceAll("GREATEST($1,$2)");
        s = SCALAR_MIN.matcher(s).replaceAll("LEAST($1,$2)");
        if (s.startsWith("CREATE TABLE") || s.startsWith("ALTER TABLE")) {
            s = s.replace("INTEGER PRIMARY KEY AUTOINCREMENT", "BIGINT PRIMARY KEY AUTO_INCREMENT");
            s = s.replace("INTEGER", "BIGINT");
            Matcher text = TEXT_COLUMN.matcher(s);
            StringBuilder out = new StringBuilder();
            while (text.find()) {
                String type = LONG_TEXT.contains(text.group(1)) ? "TEXT" : "VARCHAR(191)";
                text.appendReplacement(out, text.group(1) + text.group(2) + type);
            }
            text.appendTail(out);
            s = out.toString();
            if (s.startsWith("CREATE TABLE")) s += " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        }
        if (s.startsWith("CREATE INDEX")) {
            int where = s.indexOf(" WHERE ");
            if (where > 0) s = s.substring(0, where);
        }
        return RESERVED.matcher(s).replaceAll("`$1`");
    }
}
