# BRAIN HUB

BRAIN HUB is a JavaFX desktop application. WampServer provides the local MySQL/MariaDB database; Apache and PHP are not used by the desktop app.

## Requirements

- JDK 27, matching the Maven compiler release in `pom.xml`
- IntelliJ IDEA with Maven support
- WampServer with its MySQL or MariaDB service enabled

Maven downloads JavaFX and the MariaDB JDBC driver. MariaDB Connector/J can connect to MariaDB and compatible MySQL servers.

## Set up the WampServer database

1. Start WampServer and make sure its database service is running.
2. Open phpMyAdmin from the WampServer menu.
3. Import `src/main/resources/database/brainhub.sql`.
4. Confirm the database service port. The app defaults to `127.0.0.1:3306`, database `brain_hub`, user `root`, and an empty password, which are common local WampServer defaults. Change the environment variables below if your installation differs.

If `brain_hub` was already created with the earlier BRAIN HUB schema, back it up and import `src/main/resources/database/brainhub_upgrade.sql` once instead. That migration adds co-author/domain/keyword metadata and makes the paper file optional for drafts. Do not run the one-time migration twice. The full `brainhub.sql` file is for a new/empty installation.

For safer use, create a dedicated database user instead of using the database root account. Run SQL like this in phpMyAdmin, choosing your own strong password:

```sql
CREATE USER 'brainhub_app'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON brain_hub.* TO 'brainhub_app'@'localhost';
```

## Configure and run in IntelliJ IDEA

1. Open this folder as a Maven project and wait for Maven to reload the dependencies.
2. In IntelliJ, create a run configuration for `com.brainhub.BrainHubApp` (or run the `javafx:run` Maven goal).
3. If your database uses non-default settings, define these environment variables in the run configuration:
   - `BRAINHUB_DB_URL` — for example `jdbc:mariadb://127.0.0.1:3306/brain_hub`
   - `BRAINHUB_DB_USER` — for example `brainhub_app`
   - `BRAINHUB_DB_PASSWORD` — the password for that database user
4. Set `BRAINHUB_ADMIN_SETUP_CODE` to a private one-time setup code in the run configuration. Use it to create the initial admin through **Set up an admin account**. Do not use the public researcher sign-up form to create admins.
5. Run the application. Researchers can create accounts from the sign-in screen.

The default JDBC URL and local credentials are configured in `src/main/java/com/brainhub/Database.java`.

## Included workflows

- Researcher registration, sign-in, and log-out; passwords are salted and hashed in Java.
- Admin registration gated by the local setup code.
- Researcher dashboards with submitted, under-review, rejected, and total counts.
- Create and update unfinished drafts without uploading a file; saved drafts appear only to their researcher and can be edited later.
- Add co-author names, a research domain, and comma-separated keywords to each paper.
- Search accessible documents by title, abstract, domain, keyword, or co-author name.
- Submit a completed paper with its file; only submission triggers a notification for the assigned admin.
- Admin dashboard totals cover every document in the database. Admin document lists show their assigned submissions and published papers.
- Admins can open the paper in the computer's associated PDF/word-processing application before starting review or approving/rejecting it. This is a desktop file open, not an in-app PDF renderer.
- Admin review decisions, researcher notifications, and a separate publication step for approved work.
- Account-specific notifications for submission/review/publication events.
- Signed-in document downloads, with downloads restricted to the owner's documents or published documents for researchers; admins can access assigned submissions for review.
- Download audit records in the database.

Uploaded files are stored locally under the current Windows user's `.brainhub/documents` directory; the database stores their paths and metadata. WampServer hosts the database only. If BRAIN HUB is later used from multiple computers, move document storage to a shared secured location and configure the app accordingly.

## How the parts work together

1. **JavaFX user interface:** `BrainHubApp` displays sign-in/sign-up, researcher and admin dashboards, document forms, search results, notifications, and review actions. It validates inputs at the UI boundary and calls Java service classes.
2. **Java services:** `AuthService` registers and authenticates accounts using parameterized JDBC statements and salted PBKDF2 password hashes. `DocumentService` enforces roles/ownership, saves document state, performs searches, changes workflow status, and creates notifications in the same database transaction as related actions.
3. **JDBC connection:** `Database` reads the WampServer host, port, database, and credentials from environment variables (or its local defaults), then opens JDBC connections using MariaDB Connector/J.
4. **WampServer SQL database:** the `brain_hub` schema holds users, documents and their metadata/status, review history, notifications, and download audit entries. The paper contents stay in the user's local `.brainhub/documents` directory; only the generated storage path and file metadata are in SQL.
5. **Typical submission flow:** a researcher saves a draft (no admin notification), edits it as needed, and submits it with a file. Java stores the file locally and, in one SQL transaction, marks the document submitted, assigns an active admin, and inserts that admin's notification.
6. **Typical review/publication flow:** the assigned admin opens the stored paper with the operating system's associated reader, starts review, approves or rejects with optional comments, and can publish an approved paper. Each status transition and its account-specific notification is written through JDBC. Researchers can see their notifications and download their own work or published papers while signed in.

The interface uses powder blue, green, and orange as its color palette. File opening depends on the user's computer having an application associated with the submitted file type.
This project is now complete and can be used, ready for collaborations, and updates.
