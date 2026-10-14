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
- Document submission, private notifications, admin review decisions, and a separate publish action.
- Account-specific notifications for submission/review/publication events.
- Signed-in document downloads, with downloads restricted to the owner's documents or published documents for researchers; admins can access assigned submissions for review.
- Download audit records in the database.

Uploaded files are stored locally under the current Windows user's `.brainhub/documents` directory; the database stores their paths and metadata. WampServer hosts the database only. If BRAIN HUB is later used from multiple computers, move document storage to a shared secured location and configure the app accordingly.
