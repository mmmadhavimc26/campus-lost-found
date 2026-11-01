# Campus Lost and Found

A Spring Boot application for reporting lost and found items on campus. The frontend uses HTML, CSS and JavaScript and is served by the same application.

## Working features

- Student registration and session login, BCrypt password hashes and CSRF protection.
- Public noticeboard with type, category and search filters (latest 200 reports).
- Lost/found report creation, own reports and ranked possible matches.
- Duplicate warnings that retain both reports.
- Private text ownership evidence, one pending claim per student per found report.
- Competing pending claims from different students, each visible only to its claimant.
- Flyway database migrations, automated tests and Render Docker configuration.

## Not implemented yet

No staff dashboard, approval/rejection actions, file uploads, physical handover, recovered status, report closure, password recovery or verified campus emails. All accounts are STUDENT accounts; all reports stay OPEN and claims stay PENDING. Staff evidence review is planned for milestone 2. This version stops at pending claims.

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

Open http://localhost:8080 and create an account. Register your own test account. Three fictional reports are inserted only into an empty report database. Set `SEED_DEMO=false` to disable them. Local records persist under `data/`, which is excluded from Git.

Tests and executable JAR:

```powershell
.\mvnw.cmd clean verify
java -jar target/lost-found-0.1.0.jar
```

## Deploy to Render

1. Upload the **contents of this folder** to a GitHub repository. `pom.xml`, `Dockerfile` and `render.yaml` must be at its root. Exclude `target/`, `data/` and `.env`.
2. In Render choose **New → Blueprint**, connect that repository and select its branch.
3. Review the proposed plans and any costs, then apply. The Blueprint creates a Docker web service and PostgreSQL database and wires their environment variables.
4. Wait for the build and health check. Open the HTTPS service URL, create an account and test a report and claim.
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
| POST | `/api/auth/register` | Public + CSRF; JSON name/email/password |
| POST | `/api/auth/login` | Public + CSRF; form username/password |
| POST | `/api/auth/logout` | Session + CSRF |
| GET | `/api/me`, `/api/me/reports`, `/api/me/claims` | Student, own data |
| POST | `/api/reports` | Student + CSRF |
| GET | `/api/reports/{id}/matches` | Report owner |
| POST | `/api/reports/{id}/claims` | Student other than finder + CSRF |

Public report DTOs omit emails, hashes and ownership evidence. Evidence is text only and is not encrypted at the application field level. Database operators can read it: restrict credentials and backups.

## Before real campus use

Add verified campus accounts, rate limits, password recovery, moderation, staff provisioning, retention/deletion policies, audit records, backup/restore procedures and the full decision/handover workflow. The 14-day window assumes accurate dates and exact categories; approximate dates and nearby-zone matching are future enhancements.
