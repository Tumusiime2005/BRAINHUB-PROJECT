-- BRAIN HUB research publication management database
-- Target: MySQL 8.0+ (also suitable for current MariaDB releases)
-- Run this script in MySQL Workbench or the MySQL command-line client.

CREATE DATABASE IF NOT EXISTS brain_hub
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE brain_hub;

-- A user's role is stored here. The application must restrict who can create
-- ADMIN accounts; never trust a role supplied by a public sign-up form.
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    full_name VARCHAR(150) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('RESEARCHER', 'ADMIN') NOT NULL DEFAULT 'RESEARCHER',
    account_status ENUM('ACTIVE', 'DISABLED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_users_email (email),
    KEY idx_users_role_status (role, account_status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS documents (
    document_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    researcher_id BIGINT UNSIGNED NOT NULL,
    assigned_admin_id BIGINT UNSIGNED NULL,
    title VARCHAR(250) NOT NULL,
    abstract TEXT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    file_mime_type VARCHAR(127) NOT NULL,
    file_size_bytes BIGINT UNSIGNED NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    status ENUM(
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'APPROVED',
        'REJECTED',
        'PUBLISHED'
    ) NOT NULL DEFAULT 'DRAFT',
    submitted_at DATETIME NULL,
    published_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id),
    KEY idx_documents_researcher_status (researcher_id, status),
    KEY idx_documents_status_submitted (status, submitted_at),
    KEY idx_documents_assigned_admin (assigned_admin_id, status),
    CONSTRAINT fk_documents_researcher
        FOREIGN KEY (researcher_id) REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_documents_assigned_admin
        FOREIGN KEY (assigned_admin_id) REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT chk_documents_file_size CHECK (file_size_bytes >= 0)
) ENGINE=InnoDB;

-- Keeps a history of each admin decision instead of overwriting past reviews.
CREATE TABLE IF NOT EXISTS document_reviews (
    review_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    document_id BIGINT UNSIGNED NOT NULL,
    admin_id BIGINT UNSIGNED NOT NULL,
    decision ENUM('APPROVED', 'REJECTED') NOT NULL,
    comments TEXT NULL,
    reviewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (review_id),
    KEY idx_reviews_document_time (document_id, reviewed_at),
    KEY idx_reviews_admin_time (admin_id, reviewed_at),
    CONSTRAINT fk_reviews_document
        FOREIGN KEY (document_id) REFERENCES documents (document_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_reviews_admin
        FOREIGN KEY (admin_id) REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Notifications are addressed to one specific account. For example, on
-- submission the app creates a notification for the assigned admin; on
-- approval, rejection, or publication it creates one for the researcher.
CREATE TABLE IF NOT EXISTS notifications (
    notification_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_user_id BIGINT UNSIGNED NOT NULL,
    actor_user_id BIGINT UNSIGNED NULL,
    document_id BIGINT UNSIGNED NULL,
    notification_type ENUM(
        'DOCUMENT_SUBMITTED',
        'DOCUMENT_UNDER_REVIEW',
        'DOCUMENT_APPROVED',
        'DOCUMENT_REJECTED',
        'DOCUMENT_PUBLISHED'
    ) NOT NULL,
    message VARCHAR(500) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at DATETIME NULL,
    PRIMARY KEY (notification_id),
    KEY idx_notifications_recipient_read_time (recipient_user_id, is_read, created_at),
    CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_notifications_document
        FOREIGN KEY (document_id) REFERENCES documents (document_id)
        ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB;

-- Audit trail for downloads. The Java backend must require an authenticated
-- user and verify publication/access rules before serving a file.
CREATE TABLE IF NOT EXISTS document_downloads (
    download_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    document_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    downloaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (download_id),
    KEY idx_downloads_user_time (user_id, downloaded_at),
    KEY idx_downloads_document_time (document_id, downloaded_at),
    CONSTRAINT fk_downloads_document
        FOREIGN KEY (document_id) REFERENCES documents (document_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_downloads_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;
