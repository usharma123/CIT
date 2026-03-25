package com.cit.retro;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * Verifies trade status directly in the H2 database.
 */
public class DbVerifier {

    private static final Set<String> TERMINAL_STATUSES = Set.of("NETTED", "SETTLED", "REJECTED");
    private static final Set<String> PASS_STATUSES = Set.of("NETTED", "SETTLED");

    private final String jdbcUrl;
    private final boolean available;

    public DbVerifier(String dbPath) {
        // Strip .mv.db extension if present
        if (dbPath.endsWith(".mv.db")) {
            dbPath = dbPath.substring(0, dbPath.length() - 6);
        }

        this.available = Files.exists(Path.of(dbPath + ".mv.db"));
        this.jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE;AUTO_SERVER=TRUE;IFEXISTS=TRUE";

        if (available) {
            try {
                Class.forName("org.h2.Driver");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("H2 driver not found on classpath", e);
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Query the current status of a trade by tradeId.
     * Returns null if not found or on error.
     */
    public String queryTradeStatus(String tradeId) {
        if (!available) return null;

        String sql = "SELECT STATUS FROM TRADES WHERE TRADE_ID = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tradeId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("STATUS");
                }
            }
        } catch (SQLException e) {
            // Silently return null on error
        }
        return null;
    }

    /**
     * Poll for a terminal trade status within the given timeout.
     * Returns the terminal status, or "TIMEOUT" if deadline exceeded.
     */
    public String pollTradeStatus(String tradeId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            String status = queryTradeStatus(tradeId);
            if (status != null && TERMINAL_STATUSES.contains(status)) {
                return status;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "TIMEOUT";
            }
        }
        return "TIMEOUT";
    }

    public static boolean isPass(String dbStatus) {
        return PASS_STATUSES.contains(dbStatus);
    }
}
