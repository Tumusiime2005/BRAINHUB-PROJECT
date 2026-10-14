-- Run ONCE in phpMyAdmin for an existing BRAIN HUB database.
-- Select brain_hub first, then import this file. Back up the database first.
-- This script is intentionally a one-time migration (do not import twice).

USE brain_hub;

ALTER TABLE documents
    ADD COLUMN coauthors TEXT NULL AFTER abstract,
    ADD COLUMN research_domain VARCHAR(150) NULL AFTER coauthors,
    ADD COLUMN keywords VARCHAR(1000) NULL AFTER research_domain,
    MODIFY COLUMN original_file_name VARCHAR(255) NULL,
    MODIFY COLUMN file_mime_type VARCHAR(127) NULL,
    MODIFY COLUMN file_size_bytes BIGINT UNSIGNED NULL,
    MODIFY COLUMN storage_path VARCHAR(1024) NULL;
