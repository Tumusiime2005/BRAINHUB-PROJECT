package com.brainhub;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Locale;

public final class AuthService {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    public User signIn(String email, String password) throws SQLException {
        String sql = "SELECT user_id, full_name, email, role, password_hash "
                + "FROM users WHERE email = ? AND account_status = 'ACTIVE'";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizeEmail(email));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !verifyPassword(password, result.getString("password_hash"))) {
                    throw new SecurityException("Email or password is incorrect.");
                }
                return new User(
                        result.getLong("user_id"),
                        result.getString("full_name"),
                        result.getString("email"),
                        User.Role.valueOf(result.getString("role"))
                );
            }
        }
    }

    public User registerResearcher(String fullName, String email, String password) throws SQLException {
        return register(fullName, email, password, User.Role.RESEARCHER);
    }

    public User registerAdmin(String fullName, String email, String password, String setupCode)
            throws SQLException {
        String expectedCode = System.getenv("BRAINHUB_ADMIN_SETUP_CODE");
        if (expectedCode == null || expectedCode.isBlank()) {
            throw new IllegalStateException(
                    "Admin registration is locked. Set the BRAINHUB_ADMIN_SETUP_CODE environment variable first.");
        }
        if (setupCode == null || !MessageDigest.isEqual(
                expectedCode.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                setupCode.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new SecurityException("The admin setup code is incorrect.");
        }
        return register(fullName, email, password, User.Role.ADMIN);
    }

    private User register(String fullName, String email, String password, User.Role role) throws SQLException {
        String cleanedName = fullName == null ? "" : fullName.trim();
        String cleanedEmail = normalizeEmail(email);
        if (cleanedName.isBlank()) {
            throw new IllegalArgumentException("Enter your full name.");
        }
        if (cleanedEmail.isBlank() || !cleanedEmail.contains("@")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        if (cleanedEmail.length() > 191) {
            throw new IllegalArgumentException("Email addresses must be 191 characters or fewer.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Use a password with at least 8 characters.");
        }

        String sql = "INSERT INTO users (full_name, email, password_hash, role) VALUES (?, ?, ?, ?)";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, cleanedName);
            statement.setString(2, cleanedEmail);
            statement.setString(3, hashPassword(password));
            statement.setString(4, role.name());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The account was created but its ID could not be read.");
                }
                return new User(keys.getLong(1), cleanedName, cleanedEmail, role);
            }
        }
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }
        try {
            String[] parts = storedHash.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 2_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("The password hashing algorithm is unavailable.", exception);
        } finally {
            specification.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
