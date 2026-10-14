package com.brainhub;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private static final String DEFAULT_URL =
            "jdbc:mariadb://127.0.0.1:3306/brain_hub?connectTimeout=3000";

    private Database() {
    }

    public static Connection getConnection() throws SQLException {
        String url = setting("BRAINHUB_DB_URL", DEFAULT_URL);
        String user = setting("BRAINHUB_DB_USER", "root");
        String password = setting("BRAINHUB_DB_PASSWORD", "");
        return DriverManager.getConnection(url, user, password);
    }

    public static boolean isAvailable() {
        try (Connection ignored = getConnection()) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }
}
