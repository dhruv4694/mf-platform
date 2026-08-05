# Mutual Fund Platform

Spring Boot backend for a mutual fund transaction platform (investors, distributors, folios, NAV-based purchase/redemption, SIP).

## Docker setup — two modes

### Development (edit code on your machine, containers provide infrastructure)

```bash
# Build dev images once — bakes in JDK + all Maven dependencies
# Rebuild only when pom.xml changes (new dependency added)
docker compose -f compose.dev.yml build

# Start everything
docker compose -f compose.dev.yml up -d
```

- Edit any `.java` file on your machine → Spring DevTools restarts the app
- Edit any `.jsx` file → Vite HMR pushes to browser
- Logs: `docker logs mf-dev-app -f`
- Emails: http://localhost:8025
- API: http://localhost:8080
- Frontend: http://localhost:5173

### Production / full-stack

```bash
# Fill in real values
cp .env.example .env

# Build immutable images and start
docker compose -f compose.prod.yml up -d --build
```

- API: http://localhost:8080
- Frontend: http://localhost:3000
- Emails: http://localhost:8025

## File structure

```
mf-platform/
  Dockerfile         ← production multi-stage build (JRE + JAR only)
  Dockerfile.dev     ← development image (JDK + deps, source mounted at runtime)
  compose.dev.yml    ← dev: all services + bind mounts for hot reload
  compose.prod.yml   ← prod: all services, immutable images, .env for secrets
  .env.example       ← template for production env vars (copy to .env)
```

1. Open this folder in VS Code.
2. Install the **Extension Pack for Java**, **Spring Boot Extension Pack**, and **Lombok Annotations Support for VS Code** (Extensions sidebar → search each name → Install). The Lombok extension is required — without it, VS Code will show false errors on entities using `@Builder`/`@Getter` even though Maven compiles them fine.
3. Start the database and mail server:
   ```
   docker compose up -d
   ```
   This starts PostgreSQL (port 5432) AND MailHog (SMTP 1025, Web UI 8025).
   View captured emails at **http://localhost:8025** after any signup.
4. Run the app:
   - Via terminal: `mvn spring-boot:run`
   - Or via VS Code: open `MfPlatformApplication.java` → click **Run** above the `main` method.
5. Flyway will automatically create the schema (`V1__init_schema.sql`) against the running Postgres container on first startup.
6. App runs at `http://localhost:8080`.

## Documentation

| Doc | Contents |
|---|---|
| `PLAN.md` | Full project plan, schema, phase roadmap (keep at project root) |
| `docs/JAVA_FEATURES.md` | Every Java 17+ feature used and why, with code examples |
| `docs/DESIGN_PATTERNS.md` | Every design pattern used, implementation location, interview talking points |
| `docs/TECHNOLOGIES.md` | Every technology used, why it was chosen, trade-offs |
| `docs/ADR.md` | Architecture Decision Records — key design decisions and alternatives considered |

## Project structure

```
src/main/java/com/mfplatform/mfplatform/   # application code (entities, services, controllers go here)
src/main/resources/application.yml         # config
src/main/resources/db/migration/           # Flyway migrations
src/test/java/...                          # tests
docker-compose.yml                         # local Postgres
```

## What's implemented so far

- **Auth**: JWT login (`POST /auth/login`), refresh (`POST /auth/refresh`), public investor self-signup (`POST /auth/signup/investor`)
- **Bootstrap**: a default `ADMIN` account is auto-seeded on first startup (see `DataSeeder`) — username/password configurable via `SEED_ADMIN_USERNAME`/`SEED_ADMIN_PASSWORD` env vars, defaults to `admin` / `ChangeMe123!`. **Change this before any real deployment.**
- **Investor module**: role-scoped listing (`GET /investors`), self-view (`GET /investors/me`), single view with ownership check (`GET /investors/{id}`), privileged add (`POST /investors`)
- **Distributor module**: admin-only listing/add, self-view (`GET /distributors/me`)
- **Security**: stateless JWT auth, `@PreAuthorize` on every endpoint, row-level scoping baked into service queries (not just endpoint-level role checks)
- **Error handling**: `GlobalExceptionHandler` mapping not-found/bad-credentials/access-denied/validation errors to proper HTTP status codes

## Trying it out once running

```bash
# Login as the seeded admin
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"ChangeMe123!"}'

# Use the returned accessToken as a Bearer token for everything else
curl http://localhost:8080/api/v1/investors \
  -H "Authorization: Bearer <accessToken>"
```

## Next steps (Phase 3)

- `MutualFund`, `NavHistory` entities + `NavImportService`
- `Folio` entity + folio creation flow (an investor needs at least one folio before transacting)
- `Transaction`/`Holding` entities matching the controller/service sketch discussed earlier, wired to real persistence
- `FolioSecurity` component (referenced in the transaction controller's `@PreAuthorize` but not yet implemented)
