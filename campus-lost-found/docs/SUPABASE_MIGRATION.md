# Production Database Migration Guide: Render PostgreSQL to Supabase

This guide provides the complete, production-ready operational runbook for migrating the **Campus Lost and Found System** database from Render PostgreSQL to Supabase PostgreSQL, while keeping the application web service hosted on Render.

---

## 1. Overview and Core Principles

* **Hosting Architecture:**
  * Application service: Hosted on **Render** as a Spring Boot container.
  * Production database: Moving to **Supabase** (PostgreSQL).
* **Spring Boot Connection:**
  * Persistent application connections use the **Supabase Shared Pooler in Session Mode** on port `5432`.
  * Hibernate, Spring Data JPA, HikariCP, and Flyway remain the data access and migration layers.
  * Spring Security continues to handle authentication, demo logins, and CSRF protection.
  * **No Supabase Auth** is introduced.
* **Email Storage & Data Preservation:**
  * The `app_users.email` column, its unique constraint, and all existing email records are preserved without alteration or replacement.
  * All user IDs, item reports, private claims, foreign keys, and indexes are preserved.
  * The Flyway schema history (`flyway_schema_history`) is preserved so that migrations `V1` and `V2` remain marked as applied and are not re-executed.
* **Operational Safety & Backup Security:**
  * Credentials and secrets are never committed to version control or hardcoded.
  * Both `DB_USER` and `DB_PASSWORD` are required environment variables; missing credentials cause an immediate startup failure rather than an unauthenticated connection attempt.
  * **Backup files containing unencrypted private claim details and email records must strictly be stored OUTSIDE the Git repository** (e.g. in `D:\ProjectBackups\CampusLostFound`) on an encrypted volume with strict filesystem permissions to prevent unauthorized access or accidental commits to GitHub.
  * The existing Render database (`campus-lost-found-db`) remains completely untouched and is preserved as an offline fallback.
  * If a rollback is required after cutover, an **application-scoped backup** of the `public` schema is taken from Supabase and restored into a **newly created, separate replacement Render database**. The original Render database is never overwritten or dropped.

---

## 2. Supabase Connection Architecture

### A. Shared Pooler — Session Mode (For Render Application Web Service)
For persistent application connections from Render, use the Supabase Shared Pooler (Supavisor) configured in **Session Mode**:
* **Host:** `aws-0-ap-southeast-2.pooler.supabase.com`
* **Port:** `5432`
* **Database:** `postgres`
* **Username:** `postgres.tbdnzwdridzxqfvkrtwj`
* **Password:** Store securely as environment secret (`SUPABASE_DB_PASSWORD`).
* **SSL:** Required (`?sslmode=require`).
* **JDBC URL Template:**
  ```
  jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require
  ```

> [!CRITICAL]
> Do **NOT** use Transaction Pooler mode on port `6543` for the persistent Spring Boot / Flyway connection. Flyway relies on session-level advisory locks (`pg_advisory_lock`), which fail in transaction poolers. Spring Boot with Flyway requires **Session Mode on port 5432**.

### B. Direct Connection (Preferred for `pg_dump` and `pg_restore`)
Supabase documentation strongly recommends using the **Direct PostgreSQL connection** for native backup and restore operations (`pg_dump` and `pg_restore`) to avoid pooler transaction wrapping, timeouts, or command restrictions:
* **Direct Host:** Retrieve from **Supabase Dashboard** $\to$ **Project Settings** $\to$ **Database** $\to$ **Connection string** (Format: `db.tbdnzwdridzxqfvkrtwj.supabase.co`).
* **Direct Port:** `5432`
* **Direct User:** `postgres`
* **IPv6 / IPv4 Connectivity Notice:**
  * Direct Supabase database connections resolve to **IPv6** addresses by default.
  * If your local migration environment does not have native IPv6 network connectivity, you have two options:
    1. Enable the Supabase **IPv4 Add-on** (available under Project Settings $\to$ Database).
    2. Execute the backup/restore commands from an IPv6-capable environment (such as a cloud VM, an IPv6-enabled runner, or a cloud shell).
  * If the Direct connection is unavailable due to network constraints, the Session Pooler endpoint on port `5432` may be used with `--no-owner --no-privileges`, provided individual command timeouts are not exceeded.

---

## 3. PostgreSQL Client Tools and Version Checking

Do **not** assume the Render or Supabase major versions. Check the actual server versions before proceeding:

### Version Inspection Query
Run in both databases via your client tool or web console:
```sql
SHOW server_version;
```

### Client Tool Requirements
* `pg_dump` must be **at least as new as the source server's major version**.
* `pg_restore` and `psql` must be compatible with the destination server version.
* Do **not** use an older client tool against a newer PostgreSQL server.

Check your local installed client versions:

#### Windows PowerShell:
```powershell
pg_dump --version
psql --version
```

#### Git Bash:
```bash
pg_dump --version
psql --version
```

---

## 4. Maintenance Window Strategy and Baseline Capture

To ensure zero data loss and eliminate the risk of writes occurring between backup and cutover:
1. **Schedule a Maintenance Window:**
   * Do not promise a fixed duration; the window depends on dataset size, network throughput, and verification thoroughness.
   * Notify stakeholders and temporarily pause student report and claim submissions.
2. **Read-Only / Maintenance State:**
   * Suspend write traffic before taking the final production backup.
   * The GitHub Actions keep-alive workflow ([keep-site-awake.yml](file:///D:/campus-lost-found-system/campus-lost-found/.github/workflows/keep-site-awake.yml)) can remain active since `/api/health` performs only non-mutating liveness checks.
3. **Capture Pre-Migration Baseline Record IDs:**
   * Execute the following command block to capture and record the maximum primary keys in the Render database before any cutover operations begin.

#### Windows PowerShell:
```powershell
$env:PGHOST = "<RENDER_EXTERNAL_HOST>"
$env:PGPORT = "<RENDER_EXTERNAL_PORT>"
$env:PGDATABASE = "<RENDER_DB_NAME>"
$env:PGUSER = "<RENDER_DB_USER>"

$PRE_MIG_USER_ID = (psql -h $env:PGHOST -p $env:PGPORT -U $env:PGUSER -d $env:PGDATABASE -t -A -c "SELECT COALESCE(MAX(id), 0) FROM app_users;")
$PRE_MIG_REPORT_ID = (psql -h $env:PGHOST -p $env:PGPORT -U $env:PGUSER -d $env:PGDATABASE -t -A -c "SELECT COALESCE(MAX(id), 0) FROM reports;")
$PRE_MIG_CLAIM_ID = (psql -h $env:PGHOST -p $env:PGPORT -U $env:PGUSER -d $env:PGDATABASE -t -A -c "SELECT COALESCE(MAX(id), 0) FROM claims;")

Write-Host "Baseline Pre-Migration IDs:"
Write-Host "  Users:   $PRE_MIG_USER_ID"
Write-Host "  Reports: $PRE_MIG_REPORT_ID"
Write-Host "  Claims:  $PRE_MIG_CLAIM_ID"
```

#### Git Bash:
```bash
export PGHOST="<RENDER_EXTERNAL_HOST>"
export PGPORT="<RENDER_EXTERNAL_PORT>"
export PGDATABASE="<RENDER_DB_NAME>"
export PGUSER="<RENDER_DB_USER>"

PRE_MIG_USER_ID=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "SELECT COALESCE(MAX(id), 0) FROM app_users;")
PRE_MIG_REPORT_ID=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "SELECT COALESCE(MAX(id), 0) FROM reports;")
PRE_MIG_CLAIM_ID=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "SELECT COALESCE(MAX(id), 0) FROM claims;")

echo "Baseline Pre-Migration IDs:"
echo "  Users:   $PRE_MIG_USER_ID"
echo "  Reports: $PRE_MIG_REPORT_ID"
echo "  Claims:  $PRE_MIG_CLAIM_ID"
```

---

## 5. Render PostgreSQL Backup Procedure

### Step 5.1: Prepare External Backup Directory (Security & Encryption Requirements)
The database dump contains sensitive, unencrypted student personal details (`app_users.email` and `claims.private_details`).

> [!WARNING]
> **Never store production backups inside the Git repository directory.** Backups stored inside the repository risk accidental staging and pushing to GitHub. Always store backups in an external, protected folder.

1. **Location & Access Restrictions:** Create an external directory restricted to administrators/current user only:

#### Windows PowerShell:
```powershell
New-Item -ItemType Directory -Force -Path "D:\ProjectBackups\CampusLostFound"
icacls "D:\ProjectBackups\CampusLostFound" /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" "Administrators:(OI)(CI)F"
$backupFile = "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump"
```

#### Git Bash:
```bash
mkdir -p /d/ProjectBackups/CampusLostFound
chmod 700 /d/ProjectBackups/CampusLostFound
BACKUP_FILE="/d/ProjectBackups/CampusLostFound/campus_production_backup.dump"
```

2. **Encryption at Rest:** Ensure the backup destination drive uses volume encryption (such as Windows BitLocker or macOS FileVault). Alternatively, enable Windows NTFS file encryption on the folder:

#### Windows PowerShell:
```powershell
cipher /e "D:\ProjectBackups\CampusLostFound"
```

---

### Step 5.2: Set Render Connection Parameters
1. Go to **Render Dashboard** $\to$ **campus-lost-found-db** (PostgreSQL).
2. Under **Connections**, locate the **External Database URL** or external parameters (`Host`, `Port`, `Database`, `User`).

#### Windows PowerShell:
```powershell
$env:PGHOST = "<RENDER_EXTERNAL_HOST>"
$env:PGPORT = "<RENDER_EXTERNAL_PORT>"
$env:PGDATABASE = "<RENDER_DB_NAME>"
$env:PGUSER = "<RENDER_DB_USER>"
```

#### Git Bash:
```bash
export PGHOST="<RENDER_EXTERNAL_HOST>"
export PGPORT="<RENDER_EXTERNAL_PORT>"
export PGDATABASE="<RENDER_DB_NAME>"
export PGUSER="<RENDER_DB_USER>"
```

---

### Step 5.3: Run Application-Scoped `pg_dump`
Execute `pg_dump` scoped to the application's `public` schema with interactive password prompt (`-W`) so passwords are never saved in shell command history:

#### Windows PowerShell:
```powershell
$env:PGHOST = "<RENDER_EXTERNAL_HOST>"
$env:PGPORT = "<RENDER_EXTERNAL_PORT>"
$env:PGDATABASE = "<RENDER_DB_NAME>"
$env:PGUSER = "<RENDER_DB_USER>"
$backupFile = "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump"

pg_dump -h $env:PGHOST -p $env:PGPORT -U $env:PGUSER -d $env:PGDATABASE -W `
  --format=custom `
  --schema=public `
  --no-owner `
  --no-privileges `
  --verbose `
  --file="$backupFile"
```

#### Git Bash:
```bash
export PGHOST="<RENDER_EXTERNAL_HOST>"
export PGPORT="<RENDER_EXTERNAL_PORT>"
export PGDATABASE="<RENDER_DB_NAME>"
export PGUSER="<RENDER_DB_USER>"
BACKUP_FILE="/d/ProjectBackups/CampusLostFound/campus_production_backup.dump"

pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -W \
  --format=custom \
  --schema=public \
  --no-owner \
  --no-privileges \
  --verbose \
  --file="$BACKUP_FILE"
```
*(Enter your Render database password when prompted).*

---

### Step 5.4: Validate Backup File Integrity
Verify that all critical tables and Flyway migration records exist in the archive:

#### Windows PowerShell:
```powershell
$backupFile = "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump"
pg_restore --list "$backupFile" | Select-String -Pattern "app_users|reports|claims|flyway_schema_history"
```

#### Git Bash:
```bash
BACKUP_FILE="/d/ProjectBackups/CampusLostFound/campus_production_backup.dump"
pg_restore --list "$BACKUP_FILE" | grep -E "app_users|reports|claims|flyway_schema_history"
```

Confirm that the output lists `flyway_schema_history`, `app_users`, `reports`, and `claims`.

---

## 6. Supabase Database Restore Procedure

### Step 6.1: Set Target Connection Parameters
Target the Direct connection host obtained from the Supabase dashboard (or Session Pooler if using IPv4 fallback):

#### Windows PowerShell:
```powershell
$env:SUPABASE_HOST = "<SUPABASE_DIRECT_OR_POOLER_HOST>"
$env:SUPABASE_PORT = "5432"
$env:SUPABASE_DB = "postgres"
$env:SUPABASE_USER = "<SUPABASE_USER>"
```

#### Git Bash:
```bash
export SUPABASE_HOST="<SUPABASE_DIRECT_OR_POOLER_HOST>"
export SUPABASE_PORT="5432"
export SUPABASE_DB="postgres"
export SUPABASE_USER="<SUPABASE_USER>"
```

---

### Step 6.2: Pre-Restore Target-State, Schema TOC, and Data API Safety Inspection

Before restoring the backup archive into Supabase, perform three critical pre-restore safety checks:

#### 1. Inspect Target `public` Schema Objects & Decision Gate
Execute `psql` to check whether any application tables or Flyway history already exist in the destination `postgres` database:

##### Windows PowerShell:
```powershell
$env:SUPABASE_HOST = "<SUPABASE_DIRECT_OR_POOLER_HOST>"
$env:SUPABASE_PORT = "5432"
$env:SUPABASE_DB = "postgres"
$env:SUPABASE_USER = "<SUPABASE_USER>"

$checkQuery = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('app_users', 'reports', 'claims', 'flyway_schema_history');"
psql -h $env:SUPABASE_HOST -p $env:SUPABASE_PORT -U $env:SUPABASE_USER -d $env:SUPABASE_DB -W -c $checkQuery
```

##### Git Bash:
```bash
export SUPABASE_HOST="<SUPABASE_DIRECT_OR_POOLER_HOST>"
export SUPABASE_PORT="5432"
export SUPABASE_DB="postgres"
export SUPABASE_USER="<SUPABASE_USER>"

CHECK_QUERY="SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('app_users', 'reports', 'claims', 'flyway_schema_history');"
psql -h "$SUPABASE_HOST" -p "$SUPABASE_PORT" -U "$SUPABASE_USER" -d "$SUPABASE_DB" -W -c "$CHECK_QUERY"
```

* **Target is Clean (0 rows returned — Expected):**
  * The target Supabase database has no conflicting application tables in the `public` schema. Proceed safely.
* **Conflicting Objects Exist (1 or more rows returned):**
  * **STOP AND INVESTIGATE.** Do **NOT** proceed with `pg_restore`.
  * Do **NOT** automatically drop tables or schemas.
  * Inspect existing rows to confirm whether unneeded test data exists:
    ```sql
    SELECT 'app_users' AS tbl, COUNT(*) FROM app_users
    UNION ALL SELECT 'reports', COUNT(*) FROM reports
    UNION ALL SELECT 'claims', COUNT(*) FROM claims
    UNION ALL SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history;
    ```
  * Only after explicit human review and authorization, drop specific application tables:
    ```sql
    DROP TABLE IF EXISTS claims, reports, app_users, flyway_schema_history CASCADE;
    ```

> [!CAUTION] Destructive Action Warning
> **DO NOT** execute `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`.
> In Supabase, dropping the entire `public` schema deletes default role grants (`anon`, `authenticated`, `service_role`), deletes platform extensions, and breaks internal service routing. Always target application tables only.

---

#### 2. Existing Public Schema & Archive TOC Inspection (Safe Schema Exclusion)
Supabase already initializes and owns the `public` schema. The custom `pg_dump` archive contains a `CREATE SCHEMA public` definition.
* Do **NOT** use `pg_restore --clean`, as it will attempt to drop the existing Supabase `public` schema.
* Inspect the archive TOC for the `SCHEMA public` entry:

##### Windows PowerShell:
```powershell
$backupFile = "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump"
pg_restore --list "$backupFile" | Select-String -Pattern "SCHEMA\s+-\s+public"
```

##### Git Bash:
```bash
BACKUP_FILE="/d/ProjectBackups/CampusLostFound/campus_production_backup.dump"
pg_restore --list "$BACKUP_FILE" | grep -E "SCHEMA[[:space:]]+-[[:space:]]+public"
```

* **Safe TOC Filtering (`--use-list`):**
  To avoid any `ERROR: schema "public" already exists` collision or attempting to recreate the pre-existing schema, generate a filtered TOC list excluding the schema creation entry while retaining all application tables, data, constraints, indexes, sequences, and Flyway history.

  In PowerShell, the file is written strictly as **UTF-8 without BOM** using `[System.IO.File]::WriteAllLines()` to prevent `pg_restore` line 1 parsing errors:

##### Windows PowerShell:
```powershell
$backupFile = "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump"
$tocFile = "D:\ProjectBackups\CampusLostFound\restore.toc"
$filteredToc = "D:\ProjectBackups\CampusLostFound\filtered_restore.toc"

# Generate full TOC
pg_restore --list "$backupFile" > "$tocFile"

# Filter out the SCHEMA - public creation entry and save strictly as UTF-8 without BOM
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$filteredLines = Get-Content -Path "$tocFile" | Where-Object { $_ -notmatch 'SCHEMA\s+-\s+public' }
[System.IO.File]::WriteAllLines($filteredToc, $filteredLines, $utf8NoBom)
```

##### Git Bash:
```bash
BACKUP_FILE="/d/ProjectBackups/CampusLostFound/campus_production_backup.dump"
TOC_FILE="/d/ProjectBackups/CampusLostFound/restore.toc"
FILTERED_TOC="/d/ProjectBackups/CampusLostFound/filtered_restore.toc"

# Generate full TOC
pg_restore --list "$BACKUP_FILE" > "$TOC_FILE"

# Filter out the SCHEMA - public creation entry
grep -v -E "SCHEMA[[:space:]]+-[[:space:]]+public" "$TOC_FILE" > "$FILTERED_TOC"
```

---

#### 3. Supabase Data API (PostgREST & GraphQL) Pre-Restore Hardening
The Campus Lost and Found application communicates exclusively via Spring Boot JDBC (`postgres` administrative role) and does **not** use the Supabase Data API (PostgREST REST endpoints) or GraphQL.
* Navigate to **Supabase Dashboard** $\to$ **Project Settings** $\to$ **API**.
* Under **Data API Settings**:
  * **Disable the Data API entirely** if no other project services depend on it.
  * If the Data API cannot be completely disabled, ensure `public` is removed from **Exposed Schemas** or disable automatic exposure of new tables.
* Under **GraphQL**, verify pg_graphql is disabled or unexposed.
* Note that private student email and claim data must never be accessible via public endpoints. Section 7 Step 6 enforces explicit privilege revocation from `anon`, `authenticated`, and `service_role`, with function-based verification.

---

### Step 6.3: Execute `pg_restore` (Using Filtered TOC)
Restore the application objects into Supabase using the filtered TOC list to bypass redundant `public` schema creation:

#### Windows PowerShell:
```powershell
$env:SUPABASE_HOST = "<SUPABASE_DIRECT_OR_POOLER_HOST>"
$env:SUPABASE_PORT = "5432"
$env:SUPABASE_DB = "postgres"
$env:SUPABASE_USER = "<SUPABASE_USER>"
$backupFile = "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump"
$filteredToc = "D:\ProjectBackups\CampusLostFound\filtered_restore.toc"

pg_restore -h $env:SUPABASE_HOST -p $env:SUPABASE_PORT -U $env:SUPABASE_USER -d $env:SUPABASE_DB -W `
  --use-list="$filteredToc" `
  --no-owner `
  --no-privileges `
  --verbose `
  "$backupFile"
```

#### Git Bash:
```bash
export SUPABASE_HOST="<SUPABASE_DIRECT_OR_POOLER_HOST>"
export SUPABASE_PORT="5432"
export SUPABASE_DB="postgres"
export SUPABASE_USER="<SUPABASE_USER>"
BACKUP_FILE="/d/ProjectBackups/CampusLostFound/campus_production_backup.dump"
FILTERED_TOC="/d/ProjectBackups/CampusLostFound/filtered_restore.toc"

pg_restore -h "$SUPABASE_HOST" -p "$SUPABASE_PORT" -U "$SUPABASE_USER" -d "$SUPABASE_DB" -W \
  --use-list="$FILTERED_TOC" \
  --no-owner \
  --no-privileges \
  --verbose \
  "$BACKUP_FILE"
```
*(Enter the `SUPABASE_DB_PASSWORD` when prompted).*

---

## 7. Post-Restore Data and Schema Verification

Connect to Supabase using `psql` to verify data integrity before switching the application:

#### Windows PowerShell:
```powershell
$env:SUPABASE_HOST = "<SUPABASE_DIRECT_OR_POOLER_HOST>"
$env:SUPABASE_PORT = "5432"
$env:SUPABASE_DB = "postgres"
$env:SUPABASE_USER = "<SUPABASE_USER>"

psql -h $env:SUPABASE_HOST -p $env:SUPABASE_PORT -U $env:SUPABASE_USER -d $env:SUPABASE_DB -W
```

#### Git Bash:
```bash
export SUPABASE_HOST="<SUPABASE_DIRECT_OR_POOLER_HOST>"
export SUPABASE_PORT="5432"
export SUPABASE_DB="postgres"
export SUPABASE_USER="<SUPABASE_USER>"

psql -h "$SUPABASE_HOST" -p "$SUPABASE_PORT" -U "$SUPABASE_USER" -d "$SUPABASE_DB" -W
```

### 1. Flyway Migration History Verification
Verify that Flyway recognizes `V1` and `V2` as already applied:
```sql
SELECT installed_rank, version, description, type, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```
*Expected Result:* Exactly 2 rows (`V1__initial_schema` and `V2__rename_demo_student`), both with `success = true`.

### 2. Table Row Count Verification
Verify that row counts in Supabase match the source Render database exactly:
```sql
SELECT 'app_users' AS table_name, COUNT(*) AS total_rows FROM app_users
UNION ALL
SELECT 'reports', COUNT(*) FROM reports
UNION ALL
SELECT 'claims', COUNT(*) FROM claims
UNION ALL
SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history;
```

### 3. Email Record and Constraint Verification
Ensure all student and demo user email addresses were preserved without loss or replacement:
```sql
-- Verify email population and uniqueness
SELECT
    COUNT(*) AS total_users,
    COUNT(email) AS populated_emails,
    COUNT(DISTINCT email) AS unique_emails
FROM app_users;

-- Verify UNIQUE constraint definition
SELECT conname, contype
FROM pg_constraint
WHERE conrelid = 'app_users'::regclass AND contype = 'u';

-- Verify standard demo user records are intact
SELECT id, name, email, enabled FROM app_users WHERE email LIKE '%@demo.invalid' ORDER BY id;
```

### 4. Primary Keys, Foreign Keys, and Indexes
Verify key database integrity constraints:
```sql
-- Check primary and foreign key constraints
SELECT conname, contype, confrelid::regclass AS references_table
FROM pg_constraint
WHERE conrelid IN ('app_users'::regclass, 'reports'::regclass, 'claims'::regclass);

-- Check composite unique constraint on claims
SELECT conname FROM pg_constraint WHERE conname = 'uq_claim';

-- Check custom performance indexes
SELECT indexname, tablename, indexdef
FROM pg_indexes
WHERE tablename IN ('reports', 'claims') AND schemaname = 'public';
```

### 5. PostgreSQL Identity Sequence Synchronization
When restoring tables with existing identity columns (`BIGINT GENERATED BY DEFAULT AS IDENTITY`), sequences must be synchronized.

The following query correctly sets the sequence value:
* If the table contains records: sets the sequence to `MAX(id)` and marks `is_called = true`, so the next insert receives `MAX(id) + 1`.
* If the table is empty: sets the sequence to `1` and marks `is_called = false`, so the first insert receives `1`.

```sql
SELECT setval(
    pg_get_serial_sequence('app_users', 'id'),
    COALESCE((SELECT MAX(id) FROM app_users), 1),
    (SELECT MAX(id) IS NOT NULL FROM app_users)
);

SELECT setval(
    pg_get_serial_sequence('reports', 'id'),
    COALESCE((SELECT MAX(id) FROM reports), 1),
    (SELECT MAX(id) IS NOT NULL FROM reports)
);

SELECT setval(
    pg_get_serial_sequence('claims', 'id'),
    COALESCE((SELECT MAX(id) FROM claims), 1),
    (SELECT MAX(id) IS NOT NULL FROM claims)
);
```

### 6. Supabase Data API Privilege Hardening & Effective Privilege Verification

The Spring Boot application connects as the database user `postgres.tbdnzwdridzxqfvkrtwj` (inheriting the `postgres` role). It does not use the Supabase PostgREST API roles (`anon`, `authenticated`, `service_role`).

To ensure private student data (`app_users.email`, `claims.private_details`) is completely inaccessible via the Supabase Data API:

#### A. Explicit Privilege Revocation on Application Objects
Revoke all table and sequence permissions from `anon`, `authenticated`, and `service_role`:
```sql
-- Revoke all table-level access from Data API roles
REVOKE ALL ON TABLE public.app_users, public.reports, public.claims, public.flyway_schema_history FROM anon, authenticated, service_role;

-- Revoke sequence usage from Data API roles
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated, service_role;
```

#### B. Restrict Default Privileges for Future Public-Schema Objects
Ensure future migrations or tables do not inherit automatic grants to Data API roles:
```sql
-- Inspect current default privileges in public schema
SELECT defaclrole::regrole AS grantor, defaclnamespace::regnamespace AS schema_name, defaclobjtype AS object_type, defaclacl
FROM pg_default_acl
WHERE defaclnamespace = 'public'::regnamespace;

-- Revoke automatic default privileges on future tables and sequences
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon, authenticated, service_role;
```

#### C. Effective Privilege Verification (Using PostgreSQL Functions)
Verify that PostgreSQL's built-in privilege-checking functions confirm zero access for `anon`, `authenticated`, and `service_role`:

##### 1. Table Privilege Verification:
```sql
SELECT
    r.role_name,
    t.table_name,
    has_table_privilege(r.role_name, 'public.' || t.table_name, 'SELECT') AS can_select,
    has_table_privilege(r.role_name, 'public.' || t.table_name, 'INSERT') AS can_insert,
    has_table_privilege(r.role_name, 'public.' || t.table_name, 'UPDATE') AS can_update,
    has_table_privilege(r.role_name, 'public.' || t.table_name, 'DELETE') AS can_delete
FROM (VALUES ('anon'), ('authenticated'), ('service_role')) AS r(role_name)
CROSS JOIN (VALUES ('app_users'), ('reports'), ('claims'), ('flyway_schema_history')) AS t(table_name)
ORDER BY r.role_name, t.table_name;
```
*Expected Result:* Every column (`can_select`, `can_insert`, `can_update`, `can_delete`) must return **`false`** for all three roles on all tables.

##### 2. Sequence Privilege Verification:
```sql
SELECT
    r.role_name,
    s.sequence_name,
    has_sequence_privilege(r.role_name, s.sequence_name, 'USAGE') AS can_use_sequence,
    has_sequence_privilege(r.role_name, s.sequence_name, 'SELECT') AS can_select_sequence
FROM (VALUES ('anon'), ('authenticated'), ('service_role')) AS r(role_name)
CROSS JOIN (
    SELECT sequence_schema || '.' || sequence_name AS sequence_name
    FROM information_schema.sequences
    WHERE sequence_schema = 'public'
) AS s
ORDER BY r.role_name, s.sequence_name;
```
*Expected Result:* `can_use_sequence` and `can_select_sequence` must return **`false`** for all three roles.

#### D. Verify Spring Boot Database User (`postgres`) Privileges
Confirm that the Spring Boot administrative connection role (`postgres`) retains complete, effective access:
```sql
SELECT
    t.table_name,
    has_table_privilege('postgres', 'public.' || t.table_name, 'SELECT') AS can_select,
    has_table_privilege('postgres', 'public.' || t.table_name, 'INSERT') AS can_insert,
    has_table_privilege('postgres', 'public.' || t.table_name, 'UPDATE') AS can_update,
    has_table_privilege('postgres', 'public.' || t.table_name, 'DELETE') AS can_delete
FROM (VALUES ('app_users'), ('reports'), ('claims'), ('flyway_schema_history')) AS t(table_name)
ORDER BY t.table_name;
```
*Expected Result:* Every column must return **`true`** for `postgres`.

---

## 8. Render Web Service Configuration (Cutover)

Once the Supabase database is verified, update the environment variables in the Render Dashboard:

1. Navigate to **Render Dashboard** $\to$ **campus-lost-found** (Web Service) $\to$ **Environment**.
2. Configure the following environment variables:

| Variable | Value | Notes |
|---|---|---|
| `DB_HOST` | `aws-0-ap-southeast-2.pooler.supabase.com` | Shared Pooler host |
| `DB_PORT` | `5432` | Session Mode port |
| `DB_NAME` | `postgres` | Supabase database name |
| `DB_USER` | `postgres.tbdnzwdridzxqfvkrtwj` | Pooler username (Required) |
| `DB_PASSWORD` | `<SUPABASE_DB_PASSWORD>` | Store as a secret in Render (Required) |
| `DB_PARAMS` | `?sslmode=require` | Enforces SSL |
| `SPRING_PROFILES_ACTIVE` | `prod` | Activates production profile |

> [!IMPORTANT]
> Both `DB_USER` and `DB_PASSWORD` are strictly required environment variables in `application-prod.properties`. If either is missing, Spring Boot will intentionally throw an error and fail fast during startup rather than attempting an unauthenticated connection.

> [!NOTE]
> **Preserve the existing value of `SEED_DEMO`** currently set in Render. Do not alter `SEED_DEMO` as part of this database migration.

*(Optional alternative: set `DB_URL=jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require` with `DB_USER` and `DB_PASSWORD` supplied as Render secrets).*

3. Click **Save Changes** and trigger **Manual Deploy** $\to$ **Deploy latest commit**.

---

## 9. Live Application Verification

1. **Deployment Log Monitoring:**
   * Verify Flyway schema validation completes with 0 pending migrations:
     ```
     o.f.core.internal.command.DbValidate : Successfully validated 2 migrations
     o.f.core.internal.command.DbMigrate  : Current version of schema "public": 2
     o.f.core.internal.command.DbMigrate  : Schema "public" is up to date. No migration necessary.
     ```
   * Confirm HikariCP connects successfully to Supabase.
2. **Health Check:**
   ```bash
curl -s -f https://<RENDER_APP_URL>/api/health
   ```
   *Must return:* `{"status":"UP","milestone":"1"}`
3. **End-to-End Application Checks:**
   * Open the live site in browser.
   * Verify all existing reports appear on the noticeboard.
   * Sign in as demo student `Mayank` and verify personal reports and claims appear under **My reports** and **My claims**.
   * Submit a new test report and verify that the identity sequence generates a sequential ID without unique constraint violation.

---

## 10. Rollback and Application-Scoped State Reconciliation Procedures

### Crucial Data Consistency Notice
Once the application begins accepting production traffic on Supabase, **the two databases diverge**. New reports, claims, account creations, status updates, and record deletions performed on Supabase do **not** exist in the old Render database.

> [!CAUTION]
> Reverting Render environment variables back to the un-reconciled Render database will cause **permanent data loss** for all writes submitted since cutover.
>
> Follow the appropriate procedure below based on whether the application has started processing live writes on Supabase:

---

### Procedure A: Immediate Pre-Traffic Rollback (Zero Divergence)
* **When to Use:** Rollback is initiated during the maintenance window *before* user traffic is admitted (e.g. application startup crashes, Flyway validation error, or `/api/health` fails).
* **Why Safe:** No writes occurred on Supabase; the original Render database remains 100% current.
* **Steps:**
  1. In **Render Dashboard**, revert the environment variables back to the Render PostgreSQL instance:
     * `DB_HOST`: `<RENDER_DB_HOST>`
     * `DB_PORT`: `5432`
     * `DB_NAME`: `campus`
     * `DB_USER`: `campus`
     * `DB_PASSWORD`: `<RENDER_DB_PASSWORD>`
     * `DB_PARAMS`: `""`
  2. Redeploy the Render web service.
  3. Verify `/api/health` returns `UP`.

---

### Procedure B: Post-Cutover Rollback via Application-Scoped State Restoration (Databases Have Diverged)
* **When to Use:** Rollback is required *after* live users have submitted new reports, filed claims, or modified records on Supabase.
* **Architecture Strategy:**
  1. **Application-Scoped Dump:** Do **NOT** dump the entire Supabase database blindly. Supabase contains internal platform schemas (`auth`, `storage`, `realtime`, `vault`, `graphql`, etc.) that cannot and must not be restored to Render. Dump **only** the application's `public` schema.
  2. **Dedicated Replacement Database:** Create a **new, separate replacement Render PostgreSQL database** (e.g. `campus-lost-found-db-replacement`).
  3. **Preserve Original Database:** **NEVER overwrite, drop, or run `--clean` against the original Render production database (`campus-lost-found-db`).** It remains an untouched pre-migration safety baseline.
  4. **Verify and Cutover:** Validate the replacement database completely before updating Render web service connection variables.

#### Step 1: Immediate Write Freeze on Supabase
Halt further writes to prevent compounding data divergence while taking the rollback dump:
* In the Render Dashboard, suspend the web service or enable maintenance mode.

#### Step 2: Verify Application Objects and Dump Application-Scoped State (`--schema=public`)
Confirm that Supabase's `public` schema contains the required tables, then take an application-scoped dump:

##### Windows PowerShell:
```powershell
$env:SUPABASE_HOST = "<SUPABASE_DIRECT_OR_POOLER_HOST>"
$env:SUPABASE_PORT = "5432"
$env:SUPABASE_DB = "postgres"
$env:SUPABASE_USER = "<SUPABASE_USER>"
$rollbackDump = "D:\ProjectBackups\CampusLostFound\supabase_rollback_state.dump"

pg_dump -h $env:SUPABASE_HOST -p $env:SUPABASE_PORT -U $env:SUPABASE_USER -d $env:SUPABASE_DB -W `
  --format=custom `
  --schema=public `
  --no-owner `
  --no-privileges `
  --verbose `
  --file="$rollbackDump"
```

##### Git Bash:
```bash
export SUPABASE_HOST="<SUPABASE_DIRECT_OR_POOLER_HOST>"
export SUPABASE_PORT="5432"
export SUPABASE_DB="postgres"
export SUPABASE_USER="<SUPABASE_USER>"
ROLLBACK_DUMP="/d/ProjectBackups/CampusLostFound/supabase_rollback_state.dump"

pg_dump -h "$SUPABASE_HOST" -p "$SUPABASE_PORT" -U "$SUPABASE_USER" -d "$SUPABASE_DB" -W \
  --format=custom \
  --schema=public \
  --no-owner \
  --no-privileges \
  --verbose \
  --file="$ROLLBACK_DUMP"
```

---

#### Step 3: Restore Application State into Newly Created Replacement Database on Render
Target the newly created replacement PostgreSQL database on Render (`campus-lost-found-db-replacement`):

##### Windows PowerShell:
```powershell
$env:TARGET_PGHOST = "<RENDER_REPLACEMENT_HOST>"
$env:TARGET_PGPORT = "<RENDER_REPLACEMENT_PORT>"
$env:TARGET_PGDATABASE = "<RENDER_REPLACEMENT_DB_NAME>"
$env:TARGET_PGUSER = "<RENDER_REPLACEMENT_DB_USER>"
$rollbackDump = "D:\ProjectBackups\CampusLostFound\supabase_rollback_state.dump"

pg_restore -h $env:TARGET_PGHOST -p $env:TARGET_PGPORT -U $env:TARGET_PGUSER -d $env:TARGET_PGDATABASE -W `
  --no-owner `
  --no-privileges `
  --verbose `
  "$rollbackDump"
```

##### Git Bash:
```bash
export TARGET_PGHOST="<RENDER_REPLACEMENT_HOST>"
export TARGET_PGPORT="<RENDER_REPLACEMENT_PORT>"
export TARGET_PGDATABASE="<RENDER_REPLACEMENT_DB_NAME>"
export TARGET_PGUSER="<RENDER_REPLACEMENT_DB_USER>"
ROLLBACK_DUMP="/d/ProjectBackups/CampusLostFound/supabase_rollback_state.dump"

pg_restore -h "$TARGET_PGHOST" -p "$TARGET_PGPORT" -U "$TARGET_PGUSER" -d "$TARGET_PGDATABASE" -W \
  --no-owner \
  --no-privileges \
  --verbose \
  "$ROLLBACK_DUMP"
```

---

#### Step 4: Re-Synchronize Sequences on Restored Replacement Database
Connect to the restored replacement database and ensure all identity sequences accommodate the current records:
```sql
SELECT setval(
    pg_get_serial_sequence('app_users', 'id'),
    COALESCE((SELECT MAX(id) FROM app_users), 1),
    (SELECT MAX(id) IS NOT NULL FROM app_users)
);

SELECT setval(
    pg_get_serial_sequence('reports', 'id'),
    COALESCE((SELECT MAX(id) FROM reports), 1),
    (SELECT MAX(id) IS NOT NULL FROM reports)
);

SELECT setval(
    pg_get_serial_sequence('claims', 'id'),
    COALESCE((SELECT MAX(id) FROM claims), 1),
    (SELECT MAX(id) IS NOT NULL FROM claims)
);
```

---

#### Step 5: Validate Restored Database Completely
Run the schema verification queries from Section 7 on the replacement database:
* Check `flyway_schema_history` has versions 1 and 2 with `success = true`.
* Confirm row counts match or exceed pre-migration baselines (`app_users`, `reports`, `claims`).
* Confirm unique constraints (`uq_claim`, `uq_user_email`) and indexes are intact.

---

#### Step 6: Reconfigure Render Web Service and Redeploy
Once the replacement database is verified:
1. Update Render web service environment variables to point to the replacement database:
   * `DB_HOST`: `<RENDER_REPLACEMENT_HOST>`
   * `DB_PORT`: `<RENDER_REPLACEMENT_PORT>`
   * `DB_NAME`: `<RENDER_REPLACEMENT_DB_NAME>`
   * `DB_USER`: `<RENDER_REPLACEMENT_DB_USER>`
   * `DB_PASSWORD`: `<RENDER_REPLACEMENT_DB_PASSWORD>`
   * `DB_PARAMS`: `""`
2. Deploy the web service, lift maintenance mode, and verify that the application operates correctly.
3. The original Render database remains untouched and available for offline audit.

---

## 11. Safe Deletion and Sanitization of Temporary Backups

After the migration has completed and the 48-hour post-cutover verification window has passed successfully without requiring rollback, permanently delete the local backup directory to prevent lingering storage of student data:

> [!CAUTION]
> Standard file deletion commands (`Remove-Item` in PowerShell, `rm` in Git Bash) only unlink the file entry from the file system directory table; the raw magnetic or flash storage sectors remain physically readable until overwritten.

### A. Secure Erasure Tools (Data Sanitization)
For environments requiring strict data sanitization before disk retirement or reuse:

#### Windows PowerShell (Sysinternals SDelete):
```powershell
sdelete -p 3 "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump"
if (Test-Path "D:\ProjectBackups\CampusLostFound\supabase_rollback_state.dump") {
    sdelete -p 3 "D:\ProjectBackups\CampusLostFound\supabase_rollback_state.dump"
}
```

#### Linux / Git Bash (`shred`):
```bash
shred -u -z -n 3 /d/ProjectBackups/CampusLostFound/campus_production_backup.dump
if [ -f /d/ProjectBackups/CampusLostFound/supabase_rollback_state.dump ]; then
    shred -u -z -n 3 /d/ProjectBackups/CampusLostFound/supabase_rollback_state.dump
fi
```

On SSD storage where wear-leveling algorithms prevent guaranteed physical block overwrites, storing backups on an encrypted volume (BitLocker/FileVault) and permanently purging the encryption key (crypto-shredding) is the most reliable sanitization method.

---

### B. Standard File Removal Commands

#### Windows PowerShell:
```powershell
Remove-Item -Path "D:\ProjectBackups\CampusLostFound\campus_production_backup.dump" -Force
if (Test-Path "D:\ProjectBackups\CampusLostFound\supabase_rollback_state.dump") {
    Remove-Item -Path "D:\ProjectBackups\CampusLostFound\supabase_rollback_state.dump" -Force
}
```

#### Git Bash:
```bash
rm -f /d/ProjectBackups/CampusLostFound/campus_production_backup.dump
rm -f /d/ProjectBackups/CampusLostFound/supabase_rollback_state.dump
```
