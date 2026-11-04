package dev.lifestealplus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** MySQL closes idle connections; the store has to pick up a new one rather than fail for good. */
class MySqlConnectionTest {

    /** Hands out connections that can be "dropped", the way MySQL drops an idle one. */
    private static final class FakeServer implements DataSource {

        final List<boolean[]> connections = new ArrayList<>();

        void dropCurrent() {
            connections.getLast()[0] = true;
        }

        @Override
        public Connection getConnection() {
            boolean[] dropped = {false};
            connections.add(dropped);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isClosed" -> dropped[0];
                        case "isValid" -> !dropped[0];
                        case "close" -> {
                            dropped[0] = true;
                            yield null;
                        }
                        case "createStatement" -> Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                                (p, m, a) -> m.getName().equals("execute") ? Boolean.FALSE : null);
                        case "setAutoCommit", "commit", "rollback" -> {
                            if (dropped[0]) throw new SQLException("Connection is closed");
                            yield null;
                        }
                        default -> null;
                    });
        }

        @Override
        public Connection getConnection(String user, String password) {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    @Test
    void aTransactionStartingOnADroppedConnectionGetsANewOne() throws SQLException {
        FakeServer server = new FakeServer();
        Connection conn = MySqlConnection.open(server);
        server.dropCurrent();
        conn.setAutoCommit(false);
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(2, server.connections.size(), "reconnected as the transaction began");
    }

    @Test
    void aConnectionDroppedMidTransactionFailsThatOneButNotTheNext() throws SQLException {
        FakeServer server = new FakeServer();
        Connection conn = MySqlConnection.open(server);
        conn.setAutoCommit(false);
        server.dropCurrent();
        assertThrows(SQLException.class, conn::commit, "never swapped mid-transaction");
        assertThrows(SQLException.class, () -> conn.setAutoCommit(true));
        // The failed switch still counts as the end of the transaction, so the next one checks and reconnects.
        conn.setAutoCommit(false);
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(2, server.connections.size());
    }

    @Test
    void lostRacesAreRecognised() {
        assertEquals(true, HeartStore.lostToAnotherServer(new SQLException("Deadlock found", "40001", 1213)));
        assertEquals(true, HeartStore.lostToAnotherServer(new SQLException("Lock wait timeout exceeded", "HY000", 1205)));
        assertEquals(true, HeartStore.lostToAnotherServer(new SQLException("Record has changed since last read", "HY000", 1020)));
        assertEquals(true, HeartStore.lostToAnotherServer(new SQLException("wrapped", new SQLException("x", "40001"))));
        assertEquals(false, HeartStore.lostToAnotherServer(new SQLException("Duplicate entry", "23000", 1062)));
    }
}
