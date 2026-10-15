# Geek Monitor JVM release package

This archive contains the production Quarkus JVM application, this run guide, the deployment guide, and PostgreSQL backup/restore scripts. It does not contain PostgreSQL or SMTP services.

## Requirements

- Java 21 runtime
- PostgreSQL 17 database

Configure the process environment before starting the application:

| Variable | Purpose |
| --- | --- |
| `DB_URL` | JDBC URL for the PostgreSQL database |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials |
| `GEEK_MONITOR_ENCRYPTION_KEY` | Base64-encoded 32-byte key; keep it stable and back it up separately |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` | Optional real mail delivery configuration |
| `SMTP_START_TLS` | Defaults to `REQUIRED` |

Generate the application encryption key with `openssl rand -base64 32`. Do not commit or email it. The first application start applies Flyway migrations; opening the site then starts the one-time administrator and project setup flow.

Run the packaged application with:

```sh
java -jar quarkus-app/quarkus-run.jar
```

Read `docs/deployment.md` before exposing the service. It covers TLS/reverse proxy configuration, SMTP, backups, and restore verification.
