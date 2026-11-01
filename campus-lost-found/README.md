# Campus Lost and Found

A Spring Boot application for reporting lost and found items on campus. The frontend uses HTML, CSS and JavaScript and is served by the same application.

## Working features

- Dummy student login: choose Mayank, Bob or Finder. Sessions and CSRF protection remain enabled; no email, password or registration is required.
- Public noticeboard with type, category and search filters (latest 200 reports).
- Lost/found report creation, own reports and ranked possible matches.
- Duplicate warnings that retain both reports.
- Private text ownership evidence, one pending claim per student per found report.
- Competing pending claims from different demo students, separated by the selected account.
- Flyway database migrations, automated tests and Render Docker configuration.

## Not implemented yet

No real student authentication, admin login, staff dashboard, approval/rejection actions, file uploads, physical handover, recovered status, report closure, password recovery or verified campus emails. All accounts are STUDENT accounts; all reports stay OPEN and claims stay PENDING. Staff evidence review is planned for milestone 2. This version stops at pending claims.

See [project design, diagrams and review script](docs/PROJECT.md).

## Technology

Java 17, Spring Boot 3.5.16, Spring MVC, JPA, Security, Maven and Flyway. PostgreSQL on Render; file-backed H2 locally. The Java interface is a REST backend and the frontend uses plain browser files.

## Run locally

Install JDK 17 and set JAVA_HOME. The Maven wrapper needs internet on its first run.

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```sh
sh ./mvnw spring-boot:run
```

Open http://localhost:8080, select **Demo login**, choose a student and continue. Mayank and Bob can submit competing claims; Finder can report found items. Three fictional reports are inserted only into an empty report database. Set `SEED_DEMO=false` to disable them. Local records persist under `data/`, which is excluded from Git.

Tests and executable JAR:

```powershell
.\mvnw.cmd clean verify
java -jar target/lost-found-0.1.0.jar
```

## Deploy to Render

1. Commit and push this repository. The application lives in its `campus-lost-found/` subdirectory. Exclude `target/`, `data/` and `.env`.
2. In Render choose **New → Blueprint**, connect that repository and select its branch. Set **Blueprint Path** to `campus-lost-found/render.yaml`. The service uses `rootDir: campus-lost-found` to find the application and Dockerfile.
3. Review the proposed plans and any costs, then apply. The Blueprint creates a Docker web service and PostgreSQL database and wires their environment variables.
4. Wait for the build and health check. Open the HTTPS service URL, select a demo student and test a report and claim.
5. If free plans are unavailable, select available plans in `render.yaml` before deploying. Render controls free database availability/expiry; back up records you need to keep.

The Docker build runs `clean verify`; failing tests stop deployment. The app listens on Render's `PORT`, binds to `0.0.0.0` and uses `/api/health` for its process health check. The health endpoint is not an ongoing database readiness probe.

For manual setup, select Docker and set these variables:

| Variable | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_HOST` | Render database internal hostname |
| `DB_PORT` | `5432` |
| `DB_NAME` | Database name |
| `DB_USER` | Database username |
| `DB_PASSWORD` | Database password, stored as a secret |
| `SEED_DEMO` | `true` for fictional sample reports, otherwise `false` |

Use the same region for the database and service. Individual database values form the JDBC URL; do not paste a `postgresql://...` URL into a JDBC property. Production enables secure session cookies, so access it over HTTPS. The local profile permits HTTP localhost.

Reports/claims persist in PostgreSQL, not the container filesystem. Sessions are memory-based: redeployment signs users out. Use one service instance for this milestone.

Official references: [Render Docker](https://render.com/docs/docker), [Blueprints](https://render.com/docs/blueprint-spec), [PostgreSQL](https://render.com/docs/postgresql-creating-connecting), [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).

## API

GET `/api/csrf` first, retain the session cookie and send the returned token using its `headerName` for writes. Refresh the token after login/logout. Same-origin browser requests need no CORS setup.

| Method | Path | Access |
| --- | --- | --- |
| GET | `/api/reports`, `/api/options`, `/api/csrf`, `/api/health` | Public |
| GET | `/api/auth/demo-accounts` | Public; three fixed demo students |
| POST | `/api/auth/demo` | Public + CSRF; JSON `{"account":"mayank"}`, `bob` or `finder` |
| POST | `/api/auth/logout` | Session + CSRF |
| GET | `/api/me`, `/api/me/reports`, `/api/me/claims` | Student, own data |
| POST | `/api/reports` | Student + CSRF |
| GET | `/api/reports/{id}/matches` | Report owner |
| POST | `/api/reports/{id}/claims` | Student other than finder + CSRF |

Public report DTOs omit emails, hashes and ownership evidence. Demo accounts are shared: anyone can select an account and view its claims. Use fictional details only; this is not real authentication. Evidence is text only and is not encrypted at the application field level. Database operators can read it: restrict credentials and backups.

## Before real campus use

Replace dummy login with real student and admin authentication. Add verified campus accounts, rate limits, password recovery, moderation, staff provisioning, retention/deletion policies, audit records, backup/restore procedures and the full decision/handover workflow. The 14-day window assumes accurate dates and exact categories; approximate dates and nearby-zone matching are future enhancements.

## Demo access and background jobs

The three demo students are created automatically, including when `SEED_DEMO=false`. That setting controls sample reports only. Password login and registration endpoints are disabled. Existing user/report rows are retained, but old password accounts cannot sign in through the demo screen. The `password_hash` database column is retained for migration compatibility and is not checked by dummy login.

A GitHub Actions workflow pings the deployed health endpoint every 10 minutes after it is pushed to the default branch. There is no Render cron service or Spring scheduled job. Matching runs on request when the student chooses Find matches. Flyway migrations and optional sample report creation run at startup. No email notifications or automatic claim processing are implemented.

Manual Render deployment requires `SPRING_PROFILES_ACTIVE=prod`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` and `DB_PASSWORD`. `SEED_DEMO=true` is optional for sample reports. Render provides `PORT`; no Supabase variables or cron variables are used.

## Session expiry and logs

A Render restart or redeploy ends in-memory demo sessions. The frontend refreshes rejected CSRF tokens once. If sign-in is also lost, it asks for a demo student again and keeps the open report or claim form. Submit the form after re-entry; network failures are not automatically retried because their outcome may be unknown.

Hikari startup and shutdown messages are normal database-pool lifecycle logs. They occur when the application starts or stops, not for every report. They do not establish why the host restarted the application.

Run frontend session-recovery tests with Node.js 18 or later:

```sh
node --test src/test/js/session-recovery.test.cjs
```

The Docker/Maven build runs the backend tests. The Node tests are a separate check.

## Scheduled website ping

The repository-root workflow `.github/workflows/keep-site-awake.yml` checks:

`https://campus-lost-found-7pw0.onrender.com/api/health`

It runs every 10 minutes, at minutes 03, 13, 23, 33, 43 and 53 UTC. It checks for HTTP 200 and JSON `status: UP`, with up to six attempts to allow a sleeping service to start. It never signs in, changes reports or processes claims. The health endpoint reports that the application is responding; it does not query the database.

To activate it:

1. Commit and push the workflow to the repository's default branch (`main`). The file must be in the Git repository root, one level above this application folder.
2. Open the repository on GitHub and choose **Actions → Keep site awake**.
3. If GitHub asks you to enable Actions, enable it for the repository.
4. Choose **Run workflow** to test it immediately, then inspect the run result.

No additional Render service or new secret/environment variable is needed. GitHub Actions runner usage counts toward your account's allowance. Scheduled runs can be delayed or dropped, and public repositories' schedules can be disabled after 60 days without activity. The ping can reduce idle sleep but does not guarantee uptime or prevent Render restarts, session expiry or database-plan expiration.

References: https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule and https://render.com/docs/free
