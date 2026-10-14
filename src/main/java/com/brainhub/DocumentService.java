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
            String coauthors,
            String researchDomain,
            String keywords,
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
        return listDocuments(user, "");
    }

    public List<DocumentRecord> listDocuments(User user, String searchTerm) throws SQLException {
        String sql = "SELECT d.document_id, d.researcher_id, d.title, d.abstract, d.coauthors, "
                + "d.research_domain, d.keywords, u.full_name, u.email, "
                + "d.original_file_name, d.file_mime_type, d.storage_path, d.status, d.submitted_at "
                + "FROM documents d JOIN users u ON u.user_id = d.researcher_id ";
        if (user.role() == User.Role.RESEARCHER) {
            sql += "WHERE (d.researcher_id = ? OR d.status = 'PUBLISHED') ";
        } else {
            sql += "WHERE (d.assigned_admin_id = ? OR d.status = 'PUBLISHED') ";
        }
        if (searchTerm != null && !searchTerm.isBlank()) {
            sql += "AND (d.title LIKE ? OR d.abstract LIKE ? OR d.research_domain LIKE ? "
                    + "OR d.keywords LIKE ? OR d.coauthors LIKE ?) ";
        }
        sql += "ORDER BY COALESCE(d.submitted_at, d.created_at) DESC, d.document_id DESC";

        List<DocumentRecord> documents = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setLong(parameter++, user.id());
            if (searchTerm != null && !searchTerm.isBlank()) {
                String pattern = "%" + searchTerm.trim() + "%";
                for (int index = 0; index < 5; index++) {
                    statement.setString(parameter++, pattern);
                }
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    documents.add(new DocumentRecord(
                            result.getLong("document_id"),
                            result.getLong("researcher_id"),
                            result.getString("title"),
                            result.getString("abstract"),
                            result.getString("coauthors"),
                            result.getString("research_domain"),
                            result.getString("keywords"),
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

    public DocumentRecord getDraft(User researcher, long documentId) throws SQLException {
        String sql = "SELECT d.document_id, d.researcher_id, d.title, d.abstract, d.coauthors, "
                + "d.research_domain, d.keywords, u.full_name, u.email, d.original_file_name, "
                + "d.file_mime_type, d.storage_path, d.status, d.submitted_at "
                + "FROM documents d JOIN users u ON u.user_id = d.researcher_id "
                + "WHERE d.document_id = ? AND d.researcher_id = ? AND d.status = 'DRAFT'";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, documentId);
            statement.setLong(2, researcher.id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SecurityException("Draft not found or it does not belong to your account.");
                }
                return documentFrom(result);
            }
        }
    }

    public long saveDocument(User researcher, long draftId, String title, String abstractText,
                             String coauthors, String researchDomain, String keywords,
                             Path sourceFile, boolean submit) throws SQLException, IOException {
        if (researcher.role() != User.Role.RESEARCHER) {
            throw new SecurityException("Only researcher accounts can save documents.");
        }
        String cleanedTitle = title == null ? "" : title.trim();
        if (cleanedTitle.isBlank()) {
            throw new IllegalArgumentException("Enter a document title.");
        }
        if (submit && sourceFile == null && draftId == 0) {
            throw new IllegalArgumentException("Choose a readable paper file before submitting for review.");
        }

        if (sourceFile != null && !Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException("Choose a readable document file.");
        }
        String originalName = sourceFile == null ? null : sourceFile.getFileName().toString();
        Path storedFile = sourceFile == null ? null
                : STORAGE_DIRECTORY.resolve(UUID.randomUUID() + extensionOf(originalName));
        String mimeType = sourceFile == null ? null : Files.probeContentType(sourceFile);
        if (sourceFile != null && (mimeType == null || mimeType.isBlank())) {
            mimeType = "application/octet-stream";
        }
        long savedDocumentId = draftId;

        try {
            if (sourceFile != null) {
                Files.createDirectories(STORAGE_DIRECTORY);
                Files.copy(sourceFile, storedFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try (Connection connection = Database.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    long documentId;
                    Long adminId = submit ? findActiveAdmin(connection) : null;
                    if (submit && adminId == null) {
                        throw new IllegalStateException(
                                "No active admin account exists yet. Create an admin account before submitting.");
                    }
                    if (draftId == 0) {
                        String sql = "INSERT INTO documents (researcher_id, assigned_admin_id, title, abstract, "
                                + "coauthors, research_domain, keywords, original_file_name, file_mime_type, "
                                + "file_size_bytes, storage_path, status, submitted_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                            statement.setLong(1, researcher.id());
                            if (adminId == null) statement.setNull(2, java.sql.Types.BIGINT); else statement.setLong(2, adminId);
                            statement.setString(3, cleanedTitle);
                            statement.setString(4, optional(abstractText));
                            statement.setString(5, optional(coauthors));
                            statement.setString(6, optional(researchDomain));
                            statement.setString(7, optional(keywords));
                            statement.setString(8, originalName);
                            statement.setString(9, mimeType);
                            if (sourceFile == null) statement.setNull(10, java.sql.Types.BIGINT);
                            else statement.setLong(10, Files.size(sourceFile));
                            statement.setString(11, storedFile == null ? null : storedFile.toAbsolutePath().toString());
                            statement.setString(12, submit ? "SUBMITTED" : "DRAFT");
                            if (submit) statement.setTimestamp(13, Timestamp.from(Instant.now()));
                            else statement.setNull(13, java.sql.Types.TIMESTAMP);
                            statement.executeUpdate();
                            try (ResultSet keys = statement.getGeneratedKeys()) {
                                if (!keys.next()) throw new SQLException("The document ID could not be created.");
                                documentId = keys.getLong(1);
                            }
                        }
                    } else {
                        DocumentRecord existing = getDraftForUpdate(connection, researcher.id(), draftId);
                        if (submit && sourceFile == null
                                && (existing.storagePath() == null || !Files.isRegularFile(Path.of(existing.storagePath())))) {
                            throw new IllegalArgumentException("Choose a readable paper file before submitting for review.");
                        }
                        String sql = "UPDATE documents SET title = ?, abstract = ?, coauthors = ?, research_domain = ?, "
                                + "keywords = ?, status = ?, assigned_admin_id = ?, submitted_at = ?, updated_at = CURRENT_TIMESTAMP";
                        if (sourceFile != null) sql += ", original_file_name = ?, file_mime_type = ?, file_size_bytes = ?, storage_path = ?";
                        sql += " WHERE document_id = ? AND researcher_id = ? AND status = 'DRAFT'";
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            int p = 1;
                            statement.setString(p++, cleanedTitle);
                            statement.setString(p++, optional(abstractText));
                            statement.setString(p++, optional(coauthors));
                            statement.setString(p++, optional(researchDomain));
                            statement.setString(p++, optional(keywords));
                            statement.setString(p++, submit ? "SUBMITTED" : "DRAFT");
                            if (adminId == null) statement.setNull(p++, java.sql.Types.BIGINT); else statement.setLong(p++, adminId);
                            if (submit) statement.setTimestamp(p++, Timestamp.from(Instant.now())); else statement.setNull(p++, java.sql.Types.TIMESTAMP);
                            if (sourceFile != null) {
                                statement.setString(p++, originalName);
                                statement.setString(p++, mimeType);
                                statement.setLong(p++, Files.size(sourceFile));
                                statement.setString(p++, storedFile.toAbsolutePath().toString());
                            }
                            statement.setLong(p++, draftId);
                            statement.setLong(p, researcher.id());
                            if (statement.executeUpdate() != 1) throw new SQLException("Draft could not be saved.");
                        }
                        documentId = existing.id();
                    }
                    if (submit) {
                        insertNotification(connection, adminId, researcher.id(), documentId,
                                "DOCUMENT_SUBMITTED", "A research document was submitted: " + cleanedTitle);
                    }
                    connection.commit();
                    savedDocumentId = documentId;
                } catch (SQLException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        } catch (SQLException | IOException | RuntimeException exception) {
            if (storedFile != null) Files.deleteIfExists(storedFile);
            throw exception;
        }
        return savedDocumentId;
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
                        "UPDATE documents SET status = 'UNDER_REVIEW', updated_at = CURRENT_TIMESTAMP "
                            + "WHERE document_id = ?")) {
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
                        "UPDATE documents SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE document_id = ?")) {
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
                        "UPDATE documents SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, "
                            + "updated_at = CURRENT_TIMESTAMP "
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
        String sql = "SELECT researcher_id, assigned_admin_id, status, storage_path "
            + "FROM documents WHERE document_id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, documentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("The document no longer exists.");
                }
                long ownerId = result.getLong("researcher_id");
                long assignedAdminId = result.getLong("assigned_admin_id");
                boolean noAssignedAdmin = result.wasNull();
                String status = result.getString("status");
                boolean permitted = ownerId == user.id() || status.equals("PUBLISHED")
                    || (user.role() == User.Role.ADMIN && !noAssignedAdmin && assignedAdminId == user.id());
                if (!permitted) {
                    throw new SecurityException("You do not have permission to download this document.");
                }
                String filePath = result.getString("storage_path");
                if (filePath == null) throw new IllegalStateException("This draft has no paper file yet.");
                Path source = Path.of(filePath).normalize();
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
        String sql = "SELECT d.document_id, d.researcher_id, d.title, d.abstract, d.coauthors, "
            + "d.research_domain, d.keywords, d.original_file_name, d.file_mime_type, d.storage_path, "
            + "d.status, d.submitted_at, u.full_name, u.email "
                + "FROM documents d JOIN users u ON u.user_id = d.researcher_id "
                + "WHERE d.document_id = ? AND d.assigned_admin_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, documentId);
            statement.setLong(2, admin.id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SecurityException("This document is not assigned to your admin account.");
                }
                return documentFrom(result);
            }
        }
    }

    private DocumentRecord getDraftForUpdate(Connection connection, long researcherId, long documentId)
            throws SQLException {
        String sql = "SELECT d.document_id, d.researcher_id, d.title, d.abstract, d.coauthors, "
                + "d.research_domain, d.keywords, d.original_file_name, d.file_mime_type, d.storage_path, "
                + "d.status, d.submitted_at, u.full_name, u.email FROM documents d "
                + "JOIN users u ON u.user_id = d.researcher_id "
                + "WHERE d.document_id = ? AND d.researcher_id = ? AND d.status = 'DRAFT' FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, documentId);
            statement.setLong(2, researcherId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecurityException("Draft not found or not owned by this account.");
                return documentFrom(result);
            }
        }
    }

    private DocumentRecord documentFrom(ResultSet result) throws SQLException {
        return new DocumentRecord(
                result.getLong("document_id"),
                result.getLong("researcher_id"),
                result.getString("title"),
                result.getString("abstract"),
                result.getString("coauthors"),
                result.getString("research_domain"),
                result.getString("keywords"),
                result.getString("full_name"),
                result.getString("email"),
                result.getString("original_file_name"),
                result.getString("file_mime_type"),
                result.getString("storage_path"),
                result.getString("status"),
                result.getTimestamp("submitted_at"));
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public Path getPaperPath(User user, long documentId) throws SQLException, IOException {
        String sql = "SELECT researcher_id, assigned_admin_id, status, storage_path FROM documents WHERE document_id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, documentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("The document no longer exists.");
                long ownerId = result.getLong("researcher_id");
                long assignedAdminId = result.getLong("assigned_admin_id");
                boolean assignedAdminIsNull = result.wasNull();
                String status = result.getString("status");
                boolean permitted = ownerId == user.id()
                        || status.equals("PUBLISHED")
                        || (user.role() == User.Role.ADMIN && !assignedAdminIsNull && assignedAdminId == user.id());
                if (!permitted) throw new SecurityException("You do not have permission to open this paper.");
                String storedPath = result.getString("storage_path");
                if (storedPath == null) throw new IllegalStateException("This draft does not have a paper file yet.");
                Path paper = Path.of(storedPath).normalize();
                if (!Files.isRegularFile(paper)) throw new IOException("The stored paper file is missing.");
                return paper;
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
