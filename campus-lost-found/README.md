# Campus Lost and Found

A Spring Boot application for reporting lost and found items on campus. The frontend uses plain HTML, CSS and JavaScript served directly by the backend.

## Working features

- Dummy student login: choose Mayank or Test. Sessions and CSRF protection remain enabled; no email, password or registration is required.
- Public noticeboard with report type, category, subtype and keyword search filters (latest 200 reports).
- Lost and found report creation with a validated 37-subtype taxonomy.
- Typo-tolerant ranked matching (Matching Engine V2) with strict textual evidence gating.
- Duplicate report warnings that retain both reports.
- Private text ownership evidence: one pending claim per student per found report.
- Competing pending claims from different demo students, separated by account.
- Idempotent Flyway database migrations (V1, V2, V3), automated test suites and Docker container configuration.

## Current status and planned work

Implemented in this milestone:
- Reports remain OPEN and claims remain PENDING.
- Public noticeboard entries show general descriptions, categories, subtypes, campus zones and dates. Identifying details (serial numbers, hidden contents) are withheld from public view and stored only in private claims.
- The dummy login allows evaluating workflows without real student credentials.

Planned for future milestones:
- Staff evidence review interface.
- Approval and rejection decisions with recorded reasons.
- Dispute resolution for competing claims.
- Atomic physical handover confirmation, marking items RECOVERED and closing linked reports.

## Technology

Java 17, Spring Boot 3.5.16, Spring MVC, Data JPA, Spring Security, Maven and Flyway. Production uses PostgreSQL (Supabase or Render); local development and testing use file-backed or in-memory H2 in PostgreSQL compatibility mode. The frontend uses standard browser APIs without external JavaScript frameworks.

## Local setup and execution

Install JDK 17 and ensure `JAVA_HOME` is set.

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```sh
sh ./mvnw spring-boot:run
```

Open http://localhost:8080, select **Demo login**, choose Mayank or Test and continue. Three fictional demo reports are inserted when starting with an empty database. Set `SEED_DEMO=false` to start with a blank noticeboard. Local records persist under `data/`, which is excluded from Git.

Run tests:

```powershell
# Backend test suite (Maven / JUnit 5)
.\mvnw.cmd test

# Frontend session-recovery test suite (Node.js 18+)
node --test src/test/js/session-recovery.test.cjs
```

Build executable JAR:

```powershell
.\mvnw.cmd clean package
java -jar target/lost-found-0.1.0.jar
```

## Demo accounts

Two fixed demo students are provided:
- **Mayank** (`mayank`, display: `Mayank (demo student)`)
- **Test** (`bob`, display: `Test (demo student)`)

Demo accounts share sessions across browsers. Use fictional details only; this mechanism is intended for evaluation and demonstration. Historical database accounts (such as `finder`) remain preserved in the database to maintain referential integrity with existing records, but cannot be selected for demo sign-in.

## Matching Engine V2

When a student selects **Find matches** on a report, the backend evaluates candidate reports of the opposite type (`LOST` versus `FOUND`) in the same category. Candidates are evaluated symmetrically regardless of submission order.

### Eligibility rules
- Both reports must be `OPEN`.
- Reports must belong to opposite kinds (`LOST` vs `FOUND`) within the same category.
- Found date must be 0–14 calendar days after loss date.
- Incompatible specific subtypes are disqualified.

### Scoring components (0–100 points)
1. **Subtype (up to 35 points):**
   - 35 points: Both reports share the same specific subtype (e.g., `LAPTOP` to `LAPTOP`).
   - 20 points: Generic subtype fallback (`OTHER_*` or `MISC` matched with any subtype in the category).
   - 0 points & disqualified: Different specific subtypes within the same category (e.g., `LAPTOP` vs `SMARTPHONE`).
2. **Campus zone (up to 20 points):**
   - 20 points: Reports share the same named campus location.
   - 0 points: Different locations or `OTHER`.
3. **Date gap (up to 20 points):**
   - 20 points: 0–1 day gap between loss and found dates.
   - 12 points: 2–7 day gap.
   - 5 points: 8–14 day gap.
4. **Keyword similarity (up to 25 points):**
   - Tokenization: words lowercased, non-alphanumeric characters stripped, words under 3 characters and stopwords filtered.
   - Category-aware synonym canonicalization: maps domain synonyms within their category (e.g., `notebook` &rarr; `laptop` in ELECTRONICS; `flask` &rarr; `bottle` in OTHER; `diary` &rarr; `planner` in BOOKS).
   - Typo tolerance: Damerau-Levenshtein distance with adjacent transposition cost 1. Matched if similarity &ge; 0.80 and token length &ge; 4. Numeric and model codes require exact match.
   - Protected confusable words: blocks false-positive fuzzy matches between distinct words (e.g., `bottle`/`battle`, `white`/`write`, `metal`/`medal`, `chair`/`chain`).
   - Bipartite matching: genuine two-stage optimization (maximizes cardinality $K$ via augmenting paths first, then maximizes total similarity via min-cost flow with deterministic tie-breaking).
   - Jaccard similarity: $J = |M| / (|A| + |B| - |M|)$. Points: $\text{round}(J \times 25)$.

### Evidence gating
- **Zero Evidence Rule:** A report pair with zero matched keywords ($|M| = 0$) is immediately discarded (score 0), even if subtype, location and dates match.
- **Strong Match:** Score &ge; 75, $|M| \ge 2$, and $J \ge 0.30$.
- **Possible Match:** Score &ge; 50, $|M| \ge 1$, and $J \ge 0.10$ (capped at 74 if raw score &ge; 75 fails the Strong gate).
- **Weak Match:** Score &ge; 40, $|M| \ge 1$ (capped at 49 if raw score &ge; 50 fails the Possible gate).
- **Discarded:** Score < 40 or $|M| = 0$.

### Duplicate warnings
A report submission checks for potential duplicates with existing `OPEN` reports having the same kind, category, location, compatible subtypes, event date within 1 day, and either an identical case-insensitive title or description Jaccard similarity &ge; 0.50. The creation response returns warnings while retaining both reports.

## API

GET `/api/csrf` first, retain the session cookie and send the returned token using its `headerName` for write operations. Refresh the token after login or logout.

| Method | Path | Access | Description |
| --- | --- | --- | --- |
| GET | `/api/health` | Public | Process health check (`status: UP`) |
| GET | `/api/csrf` | Public | CSRF token and header name |
| GET | `/api/options` | Public | Categories, subtypes by category and locations |
| GET | `/api/auth/demo-accounts` | Public | Selectable demo students (Mayank and Test) |
| POST | `/api/auth/demo` | Public + CSRF | Sign in as demo student `{"account":"mayank"}` or `"bob"` |
| POST | `/api/auth/logout` | Session + CSRF | Sign out and end session |
| GET | `/api/me` | Student | Current signed-in student details |
| GET | `/api/reports` | Public | Public noticeboard (latest 200 reports) |
| GET | `/api/me/reports` | Student | Reports submitted by current user |
| POST | `/api/reports` | Student + CSRF | Create a lost or found report |
| GET | `/api/reports/{id}/matches` | Report owner | Retrieve ranked potential matches |
| POST | `/api/reports/{id}/claims` | Student other than finder + CSRF | Submit private ownership claim |
| GET | `/api/me/claims` | Student | Claims submitted by current user |

Public report responses omit user identifiers and private claim details. Claims are stored as plain text and separated by account.

## Production deployment

### Architecture overview
The application runs as a Docker container on **Render** connected to a managed **Supabase PostgreSQL** database.

### Automated Flyway migrations
Database schema management is fully automated via Flyway on application bootstrap:
1. `V1__initial_schema.sql`: creates `app_users`, `reports`, `claims`, constraints and indexes.
2. `V2__rename_demo_student.sql`: updates demo account display names.
3. `V3__add_report_subtype.sql`: adds the `subtype` column idempotently (`ADD COLUMN IF NOT EXISTS`), backfills historical rows with category-specific generic fallbacks, enforces `NOT NULL`, and creates `idx_reports_matching_v2`.

The V3 migration is idempotent: if the `subtype` column, constraints or indexes already exist in production, Flyway executes safely, preserves existing subtype values without overwriting them, and records version 3 in `flyway_schema_history`. Unexpected or null categories fail fast.

### Supabase database configuration
When connecting to Supabase:
- Use the **Supabase Shared Pooler in Session Mode** on port `5432` (`aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require`).
- Do **not** use transaction poolers on port `6543`; Flyway relies on session-level advisory locks (`pg_advisory_lock`), which fail in transaction poolers.
- The application connects directly using administrative JDBC credentials (`postgres`). In the Supabase Dashboard under **Project Settings &rarr; API**, disable the PostgREST and GraphQL Data APIs or remove `public` from exposed schemas so application tables are never exposed over public HTTP REST endpoints.

### Render environment variables
Configure the web service on Render with the following environment variables:

| Variable | Value | Description |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` | Activates production profile |
| `DB_HOST` | `aws-0-ap-southeast-2.pooler.supabase.com` | Supabase pooler host |
| `DB_PORT` | `5432` | Session mode port |
| `DB_NAME` | `postgres` | Database name |
| `DB_USER` | `postgres.<project-ref>` | Database user (required) |
| `DB_PASSWORD` | `<secret>` | Database password (required secret) |
| `DB_PARAMS` | `?sslmode=require` | Enforces SSL encryption in transit |
| `SEED_DEMO` | `false` | Set `false` in production; `true` seeds sample reports |

Both `DB_USER` and `DB_PASSWORD` are strictly required in `application-prod.properties`; missing credentials fail fast at startup.

### Deploying updates
1. Commit and push repository changes to GitHub.
2. Render detects changes to `campus-lost-found/` and triggers a Docker build running `mvn clean verify`.
3. The container starts, executes Flyway migrations up to the current version, and begins serving requests.

## Session handling and background keep-awake

- In-memory sessions end upon container restart or redeployment. The frontend refreshes expired CSRF tokens automatically and prompts for demo account re-selection if the session has lapsed, preserving open form input.
- A GitHub Actions workflow (`.github/workflows/keep-site-awake.yml`) pings `/api/health` every 10 minutes (at minutes 03, 13, 23, 33, 43 and 53 UTC) to reduce free-tier container sleep. It performs up to six HTTP GET retries and never mutates data.
