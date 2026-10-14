package com.brainhub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DocumentService {
    private static final Path STORAGE_DIRECTORY = Path.of(
            System.getProperty("user.home"), ".brainhub", "documents");

    public record DashboardCounts(int submitted, int underReview, int rejected, int total) {
    }

    public record DocumentRecord(
            long id,
            long researcherId,
            String title,
            String abstractText,
            String researcherName,
            String researcherEmail,
            String originalFileName,
            String mimeType,
            String storagePath,
            String status,
            Timestamp submittedAt) {
    }

    public record NotificationRecord(
            long id,
            long documentId,
            String type,
            String message,
            boolean read,
            Timestamp createdAt) {
    }

    public DashboardCounts getDashboardCounts(User user) throws SQLException {
        String sql = "SELECT "
                + "COALESCE(SUM(status = 'SUBMITTED'), 0) AS submitted_count, "
                + "COALESCE(SUM(status = 'UNDER_REVIEW'), 0) AS review_count, "
                + "COALESCE(SUM(status = 'REJECTED'), 0) AS rejected_count, "
                + "COUNT(*) AS total_count FROM documents";
        if (user.role() == User.Role.RESEARCHER) {
            sql += " WHERE researcher_id = ?";
        }
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (user.role() == User.Role.RESEARCHER) {
                statement.setLong(1, user.id());
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new DashboardCounts(
                        result.getInt("submitted_count"),
                        result.getInt("review_count"),
                        result.getInt("rejected_count"),
                        result.getInt("total_count"));
            }
        }
    }

    public List<DocumentRecord> listDocuments(User user) throws SQLException {
        String sql = "SELECT d.document_id, d.researcher_id, d.title, d.abstract, u.full_name, u.email, "
                + "d.original_file_name, d.file_mime_type, d.storage_path, d.status, d.submitted_at "
                + "FROM documents d JOIN users u ON u.user_id = d.researcher_id ";
        if (user.role() == User.Role.RESEARCHER) {
            sql += "WHERE d.researcher_id = ? OR d.status = 'PUBLISHED' ";
        }
        sql += "ORDER BY COALESCE(d.submitted_at, d.created_at) DESC, d.document_id DESC";

        List<DocumentRecord> documents = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (user.role() == User.Role.RESEARCHER) {
                statement.setLong(1, user.id());
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    documents.add(new DocumentRecord(
                            result.getLong("document_id"),
                            result.getLong("researcher_id"),
                            result.getString("title"),
                            result.getString("abstract"),
                            result.getString("full_name"),
                            result.getString("email"),
                            result.getString("original_file_name"),
                            result.getString("file_mime_type"),
                            result.getString("storage_path"),
                            result.getString("status"),
                            result.getTimestamp("submitted_at")));
                }
            }
        }
        return documents;
    }

    public void submitDocument(User researcher, String title, String abstractText, Path sourceFile)
            throws SQLException, IOException {
        if (researcher.role() != User.Role.RESEARCHER) {
            throw new SecurityException("Only researcher accounts can submit documents.");
        }
        String cleanedTitle = title == null ? "" : title.trim();
        if (cleanedTitle.isBlank()) {
            throw new IllegalArgumentException("Enter a document title.");
        }
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException("Choose a readable document file.");
        }

        String originalName = sourceFile.getFileName().toString();
        String extension = extensionOf(originalName);
        Path storedFile = STORAGE_DIRECTORY.resolve(UUID.randomUUID() + extension);
        String mimeType = Files.probeContentType(sourceFile);
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "application/octet-stream";
        }

        try {
            Files.createDirectories(STORAGE_DIRECTORY);
            Files.copy(sourceFile, storedFile, StandardCopyOption.REPLACE_EXISTING);
            try (Connection connection = Database.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Long adminId = findActiveAdmin(connection);
                    if (adminId == null) {
                        throw new IllegalStateException(
                                "No active admin account exists yet. Create an admin account before accepting submissions.");
                    }
                    String insertDocument = "INSERT INTO documents "
                            + "(researcher_id, assigned_admin_id, title, abstract, original_file_name, "
                            + "file_mime_type, file_size_bytes, storage_path, status, submitted_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?)";
                    long documentId;
                    try (PreparedStatement statement = connection.prepareStatement(
                            insertDocument, Statement.RETURN_GENERATED_KEYS)) {
                        statement.setLong(1, researcher.id());
                        statement.setLong(2, adminId);
                        statement.setString(3, cleanedTitle);
                        statement.setString(4, abstractText == null || abstractText.isBlank()
                                ? null : abstractText.trim());
                        statement.setString(5, originalName);
                        statement.setString(6, mimeType);
                        statement.setLong(7, Files.size(sourceFile));
                        statement.setString(8, storedFile.toAbsolutePath().toString());
                        statement.setTimestamp(9, Timestamp.from(Instant.now()));
                        statement.executeUpdate();
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("The submission ID could not be created.");
                            }
                            documentId = keys.getLong(1);
                        }
                    }
                    insertNotification(connection, adminId, researcher.id(), documentId,
                            "DOCUMENT_SUBMITTED", "A research document was submitted: " + cleanedTitle);
                    connection.commit();
                } catch (SQLException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        } catch (SQLException | IOException | RuntimeException exception) {
            Files.deleteIfExists(storedFile);
            throw exception;
        }
    }

    public void markUnderReview(User admin, long documentId) throws SQLException {
        requireAdmin(admin);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                DocumentRecord document = lockAssignedDocument(connection, admin, documentId);
                if (!document.status().equals("SUBMITTED")) {
                    throw new IllegalStateException("Only submitted documents can be moved under review.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE documents SET status = 'UNDER_REVIEW' WHERE document_id = ?")) {
                    statement.setLong(1, documentId);
                    statement.executeUpdate();
                }
                insertNotification(connection, document.researcherId(), admin.id(), documentId,
                        "DOCUMENT_UNDER_REVIEW", "Your document is now under review: " + document.title());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void decide(User admin, long documentId, boolean approved, String comments) throws SQLException {
        requireAdmin(admin);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                DocumentRecord document = lockAssignedDocument(connection, admin, documentId);
                if (!document.status().equals("SUBMITTED") && !document.status().equals("UNDER_REVIEW")) {
                    throw new IllegalStateException("This document has already received a decision.");
                }
                String decision = approved ? "APPROVED" : "REJECTED";
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE documents SET status = ? WHERE document_id = ?")) {
                    statement.setString(1, decision);
                    statement.setLong(2, documentId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO document_reviews (document_id, admin_id, decision, comments) "
                                + "VALUES (?, ?, ?, ?)")) {
                    statement.setLong(1, documentId);
                    statement.setLong(2, admin.id());
                    statement.setString(3, decision);
                    statement.setString(4, comments == null || comments.isBlank() ? null : comments.trim());
                    statement.executeUpdate();
                }
                String type = approved ? "DOCUMENT_APPROVED" : "DOCUMENT_REJECTED";
                String message = approved
                        ? "Your document was approved and is awaiting publication: " + document.title()
                        : "Your document was rejected: " + document.title()
                                + (comments == null || comments.isBlank() ? "" : " — " + comments.trim());
                insertNotification(connection, document.researcherId(), admin.id(), documentId, type, message);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void publish(User admin, long documentId) throws SQLException {
        requireAdmin(admin);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                DocumentRecord document = lockAssignedDocument(connection, admin, documentId);
                if (!document.status().equals("APPROVED")) {
                    throw new IllegalStateException("Only approved documents can be published.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE documents SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP "
                                + "WHERE document_id = ?")) {
                    statement.setLong(1, documentId);
                    statement.executeUpdate();
                }
                insertNotification(connection, document.researcherId(), admin.id(), documentId,
                        "DOCUMENT_PUBLISHED", "Your document has been published: " + document.title());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<NotificationRecord> listNotifications(User user) throws SQLException {
        String sql = "SELECT notification_id, document_id, notification_type, message, is_read, created_at "
                + "FROM notifications WHERE recipient_user_id = ? ORDER BY created_at DESC LIMIT 100";
        List<NotificationRecord> notifications = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, user.id());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    notifications.add(new NotificationRecord(
                            result.getLong("notification_id"),
                            result.getLong("document_id"),
                            result.getString("notification_type"),
                            result.getString("message"),
                            result.getBoolean("is_read"),
                            result.getTimestamp("created_at")));
                }
            }
        }
        return notifications;
    }

    public void markNotificationRead(User user, long notificationId) throws SQLException {
        String sql = "UPDATE notifications SET is_read = TRUE, read_at = CURRENT_TIMESTAMP "
                + "WHERE notification_id = ? AND recipient_user_id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, user.id());
            statement.executeUpdate();
        }
    }

    public void download(User user, long documentId, Path destination) throws SQLException, IOException {
        if (destination == null) {
            throw new IllegalArgumentException("Choose where to save the document.");
        }
        String sql = "SELECT researcher_id, status, storage_path FROM documents WHERE document_id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, documentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("The document no longer exists.");
                }
                long ownerId = result.getLong("researcher_id");
                String status = result.getString("status");
                boolean permitted = user.role() == User.Role.ADMIN
                        || ownerId == user.id()
                        || status.equals("PUBLISHED");
                if (!permitted) {
                    throw new SecurityException("You do not have permission to download this document.");
                }
                Path source = Path.of(result.getString("storage_path")).normalize();
                if (!Files.isRegularFile(source)) {
                    throw new IOException("The stored document file is missing.");
                }
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO document_downloads (document_id, user_id) VALUES (?, ?)")) {
            statement.setLong(1, documentId);
            statement.setLong(2, user.id());
            statement.executeUpdate();
        }
    }

    private DocumentRecord lockAssignedDocument(Connection connection, User admin, long documentId)
            throws SQLException {
        String sql = "SELECT d.document_id, d.researcher_id, d.title, d.abstract, d.original_file_name, "
                + "d.file_mime_type, d.storage_path, d.status, d.submitted_at, u.full_name, u.email "
                + "FROM documents d JOIN users u ON u.user_id = d.researcher_id "
                + "WHERE d.document_id = ? AND d.assigned_admin_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, documentId);
            statement.setLong(2, admin.id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SecurityException("This document is not assigned to your admin account.");
                }
                return new DocumentRecord(
                        result.getLong("document_id"),
                        result.getLong("researcher_id"),
                        result.getString("title"),
                        result.getString("abstract"),
                        result.getString("full_name"),
                        result.getString("email"),
                        result.getString("original_file_name"),
                        result.getString("file_mime_type"),
                        result.getString("storage_path"),
                        result.getString("status"),
                        result.getTimestamp("submitted_at"));
            }
        }
    }

    private Long findActiveAdmin(Connection connection) throws SQLException {
        String sql = "SELECT user_id FROM users WHERE role = 'ADMIN' AND account_status = 'ACTIVE' "
                + "ORDER BY user_id LIMIT 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : null;
        }
    }

    private void insertNotification(Connection connection, long recipientId, long actorId,
                                    long documentId, String type, String message) throws SQLException {
        String sql = "INSERT INTO notifications "
                + "(recipient_user_id, actor_user_id, document_id, notification_type, message) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId);
            statement.setLong(2, actorId);
            statement.setLong(3, documentId);
            statement.setString(4, type);
            statement.setString(5, message);
            statement.executeUpdate();
        }
    }

    private void requireAdmin(User user) {
        if (user.role() != User.Role.ADMIN) {
            throw new SecurityException("This action requires an admin account.");
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot).replaceAll("[^.A-Za-z0-9_-]", "");
    }
}
