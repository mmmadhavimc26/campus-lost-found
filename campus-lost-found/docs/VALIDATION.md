# Validation record

Verified on 1 November 2026 using Java 17.0.17 and Maven 3.9.11:

- `clean verify`: BUILD SUCCESS; 13 backend tests, zero failures/errors/skips.
- The executable Spring Boot JAR started successfully on port 8080.
- Flyway created the schema and Hibernate validated it against H2 in PostgreSQL compatibility mode.
- Browser checks: noticeboard, dummy student selection and sign-in, report submission, a 100-point backpack match, private claim submission and Pending — not approved status.
- Basic frontend JavaScript passed `node --check`.
- The revised report list was checked at phone and desktop widths with no horizontal overflow.

The dummy-login interface and application were rebuilt together with `clean verify`. All 13 backend tests passed.

Render deployment has not been performed. Docker is not installed in the authoring environment, so the container image was not built locally. A real PostgreSQL connection has not been exercised here; deployment wiring is provided and the migration uses PostgreSQL-compatible SQL. The Docker build runs the test suite before producing its runtime image.

Dummy login was also checked in the browser: Mayank could establish a session and submit a report without a password. No Render deployment or cron job has been created.

Session recovery: 13 backend tests and 5 Node frontend tests pass, including stale tokens, lost sessions, re-entry before claims, permission failures and no automatic retry after uncertain network outcomes.

Browser restart regression: with a claim draft open, the local server was restarted. Submission requested demo re-entry, preserved the identifying details, and succeeded as PENDING after re-entry. The deployed Render site still requires the fix to be pushed and deployed.
