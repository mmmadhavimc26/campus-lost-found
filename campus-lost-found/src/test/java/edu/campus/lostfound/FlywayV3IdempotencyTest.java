package edu.campus.lostfound;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayV3IdempotencyTest {

    @Test
    void executesV3SuccessfullyWhenSubtypeAlreadyExistsManually() throws Exception {
        String dbUrl = "jdbc:h2:mem:v3_idempotent_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        // Step 1: Run Flyway up to V2
        Flyway flywayToV2 = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .target("2")
            .load();
        flywayToV2.migrate();

        // Step 2: Simulate manual SQL execution in Supabase before V3 is recorded in Flyway
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            // Create a user for owner_id foreign key
            stmt.execute("INSERT INTO app_users (name, email, password_hash, enabled) VALUES ('Test Student', 'test@example.com', 'UNUSED', true)");

            // Insert reports with V1/V2 schema (before subtype column exists)
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) " +
                         "VALUES (1, 'LOST', 'Work Laptop', 'ELECTRONICS', 'LIBRARY', CURRENT_DATE, 'A black laptop', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) " +
                         "VALUES (1, 'FOUND', 'Gym Backpack', 'BAGS', 'SPORTS_GROUND', CURRENT_DATE, 'A blue backpack', CURRENT_TIMESTAMP, 'OPEN')");

            // Manually execute V3 SQL (simulating what the user ran in Supabase SQL Editor)
            stmt.execute("ALTER TABLE reports ADD COLUMN IF NOT EXISTS subtype VARCHAR(40);");
            stmt.execute("UPDATE reports SET subtype = 'LAPTOP' WHERE title = 'Work Laptop';");
            stmt.execute("UPDATE reports SET subtype = 'BACKPACK' WHERE title = 'Gym Backpack';");
            stmt.execute("ALTER TABLE reports ALTER COLUMN subtype SET NOT NULL;");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_reports_matching_v2 ON reports(category, subtype, kind, event_date);");

            // Verify flyway_schema_history only contains V1 and V2
            try (ResultSet rs = stmt.executeQuery("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("version")).isEqualTo("1");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("version")).isEqualTo("2");
                assertThat(rs.next()).isFalse();
            }
        }

        // Step 3: Run Flyway to migrate to latest (V3)
        Flyway flywayToLatest = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .load();
        var migrationResult = flywayToLatest.migrate();

        // Verify Flyway executed V3 successfully
        assertThat(migrationResult.success).isTrue();
        assertThat(migrationResult.migrationsExecuted).isEqualTo(1);

        // Step 4: Verify V3 is recorded in Flyway history and existing subtype values were NOT overwritten
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("version")).isEqualTo("1");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("version")).isEqualTo("2");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("version")).isEqualTo("3");
                assertThat(rs.getBoolean("success")).isTrue();
                assertThat(rs.next()).isFalse();
            }

            // Verify existing subtype values were preserved (NOT overwritten with generic defaults)
            try (ResultSet rs = stmt.executeQuery("SELECT title, subtype FROM reports ORDER BY title")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("title")).isEqualTo("Gym Backpack");
                assertThat(rs.getString("subtype")).isEqualTo("BACKPACK"); // Not overwritten with OTHER_BAG

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("title")).isEqualTo("Work Laptop");
                assertThat(rs.getString("subtype")).isEqualTo("LAPTOP"); // Not overwritten with OTHER_ELECTRONICS

                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void failsMigrationWhenEncounteringUnexpectedCategory() throws Exception {
        String dbUrl = "jdbc:h2:mem:v3_fail_invalid_cat;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        // Step 1: Run Flyway up to V2
        Flyway flywayToV2 = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .target("2")
            .load();
        flywayToV2.migrate();

        // Step 2: Insert a user and a report with an unexpected/invalid category
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO app_users (name, email, password_hash, enabled) VALUES ('Test Student', 'test@example.com', 'UNUSED', true)");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) " +
                         "VALUES (1, 'LOST', 'Mystery item', 'UNEXPECTED_CATEGORY', 'LIBRARY', CURRENT_DATE, 'An unknown item', CURRENT_TIMESTAMP, 'OPEN')");
        }

        // Step 3: Run Flyway to execute V3 - must fail because UNEXPECTED_CATEGORY yields NULL subtype, violating NOT NULL
        Flyway flywayToLatest = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .load();

        assertThatThrownBy(flywayToLatest::migrate)
            .isInstanceOf(FlywayException.class);
    }

    @Test
    void failsMigrationWhenEncounteringNullCategory() throws Exception {
        String dbUrl = "jdbc:h2:mem:v3_fail_null_cat;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        // Step 1: Run Flyway up to V2
        Flyway flywayToV2 = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .target("2")
            .load();
        flywayToV2.migrate();

        // Step 2: Allow category to be NULL for test and insert a report with NULL category
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO app_users (name, email, password_hash, enabled) VALUES ('Test Student', 'test@example.com', 'UNUSED', true)");
            stmt.execute("ALTER TABLE reports ALTER COLUMN category DROP NOT NULL;");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) " +
                         "VALUES (1, 'LOST', 'Null category item', NULL, 'LIBRARY', CURRENT_DATE, 'A report without category', CURRENT_TIMESTAMP, 'OPEN')");
        }

        // Step 3: Run Flyway to execute V3 - must fail because NULL category yields NULL subtype, violating NOT NULL
        Flyway flywayToLatest = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .load();

        assertThatThrownBy(flywayToLatest::migrate)
            .isInstanceOf(FlywayException.class);
    }

    @Test
    void failsMigrationWhenUnexpectedCategoryHasPopulatedSubtype() throws Exception {
        String dbUrl = "jdbc:h2:mem:v3_fail_unexpected_populated_sub;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway flywayToV2 = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .target("2")
            .load();
        flywayToV2.migrate();

        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO app_users (name, email, password_hash, enabled) VALUES ('Test Student', 'test@example.com', 'UNUSED', true)");
            stmt.execute("ALTER TABLE reports ADD COLUMN IF NOT EXISTS subtype VARCHAR(40);");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, subtype, location, event_date, description, created_at, status) " +
                         "VALUES (1, 'LOST', 'Invalid Item', 'UNEXPECTED_CATEGORY', 'BACKPACK', 'LIBRARY', CURRENT_DATE, 'Description', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("ALTER TABLE reports ALTER COLUMN subtype SET NOT NULL;");
        }

        Flyway flywayToLatest = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .load();

        assertThatThrownBy(flywayToLatest::migrate)
            .isInstanceOf(FlywayException.class);
    }

    @Test
    void failsMigrationWhenNullCategoryHasPopulatedSubtype() throws Exception {
        String dbUrl = "jdbc:h2:mem:v3_fail_null_populated_sub;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway flywayToV2 = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .target("2")
            .load();
        flywayToV2.migrate();

        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO app_users (name, email, password_hash, enabled) VALUES ('Test Student', 'test@example.com', 'UNUSED', true)");
            stmt.execute("ALTER TABLE reports ALTER COLUMN category DROP NOT NULL;");
            stmt.execute("ALTER TABLE reports ADD COLUMN IF NOT EXISTS subtype VARCHAR(40);");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, subtype, location, event_date, description, created_at, status) " +
                         "VALUES (1, 'LOST', 'Null Cat Item', NULL, 'LAPTOP', 'LIBRARY', CURRENT_DATE, 'Description', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("ALTER TABLE reports ALTER COLUMN subtype SET NOT NULL;");
        }

        Flyway flywayToLatest = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .load();

        assertThatThrownBy(flywayToLatest::migrate)
            .isInstanceOf(FlywayException.class);
    }

    @Test
    void backfillsValidSubtypesForNullSubtypeRows() throws Exception {
        String dbUrl = "jdbc:h2:mem:v3_backfill_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        // Step 1: Run Flyway up to V2
        Flyway flywayToV2 = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .target("2")
            .load();
        flywayToV2.migrate();

        // Step 2: Insert historical reports covering all categories
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO app_users (name, email, password_hash, enabled) VALUES ('Student', 'student@example.com', 'UNUSED', true)");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) VALUES (1, 'LOST', 'Phone', 'ELECTRONICS', 'LIBRARY', CURRENT_DATE, 'Phone', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) VALUES (1, 'LOST', 'Purse', 'BAGS', 'LIBRARY', CURRENT_DATE, 'Purse', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) VALUES (1, 'LOST', 'Book', 'BOOKS', 'LIBRARY', CURRENT_DATE, 'Book', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) VALUES (1, 'LOST', 'Key', 'KEYS', 'LIBRARY', CURRENT_DATE, 'Key', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) VALUES (1, 'LOST', 'Coat', 'CLOTHING', 'LIBRARY', CURRENT_DATE, 'Coat', CURRENT_TIMESTAMP, 'OPEN')");
            stmt.execute("INSERT INTO reports (owner_id, kind, title, category, location, event_date, description, created_at, status) VALUES (1, 'LOST', 'Other item', 'OTHER', 'LIBRARY', CURRENT_DATE, 'Other', CURRENT_TIMESTAMP, 'OPEN')");
        }

        // Step 3: Run Flyway to execute V3
        Flyway flywayToLatest = Flyway.configure()
            .dataSource(dbUrl, "sa", "")
            .load();
        var result = flywayToLatest.migrate();
        assertThat(result.success).isTrue();

        // Step 4: Verify historical rows received the correct category-specific fallback subtype
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT category, subtype FROM reports ORDER BY id")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).isEqualTo("ELECTRONICS");
            assertThat(rs.getString("subtype")).isEqualTo("OTHER_ELECTRONICS");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).isEqualTo("BAGS");
            assertThat(rs.getString("subtype")).isEqualTo("OTHER_BAG");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).isEqualTo("BOOKS");
            assertThat(rs.getString("subtype")).isEqualTo("OTHER_BOOK");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).isEqualTo("KEYS");
            assertThat(rs.getString("subtype")).isEqualTo("OTHER_KEY");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).isEqualTo("CLOTHING");
            assertThat(rs.getString("subtype")).isEqualTo("OTHER_CLOTHING");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).isEqualTo("OTHER");
            assertThat(rs.getString("subtype")).isEqualTo("MISC");

            assertThat(rs.next()).isFalse();
        }
    }
}
