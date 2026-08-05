# Local Setup Guide

---

## Two modes

### Mode 1 — Development (recommended while writing code)

Docker runs **infrastructure only**. You run the app yourself.

```
docker compose up -d          ← PostgreSQL + MailHog
mvn spring-boot:run           ← Spring Boot (you control this)
npm run dev                   ← React on localhost:5173 (you control this)
```

- Edit code freely — no rebuilding Docker images
- Spring Boot hot-reloads with DevTools
- Vite HMR reloads the frontend instantly
- View emails at http://localhost:8025

### Mode 2 — Full stack (for sharing or deployment preview)

Docker runs **everything** — database, mail, app, frontend.

```
docker compose --profile fullstack up -d
```

- Spring Boot app on http://localhost:8080
- React frontend (nginx) on http://localhost:3000
- PostgreSQL on 5432, MailHog on 8025
- One command, fully self-contained

---

## Development setup (Mode 1)

### Prerequisites
- Docker Desktop: https://www.docker.com/products/docker-desktop
- JDK 17+: https://adoptium.net
- Node.js 18+: https://nodejs.org
- Maven (or use the `mvnw` wrapper in the project)

### Step 1 — Start infrastructure

```bash
cd mf-platform
docker compose up -d
```

Starts:
- PostgreSQL 16 on `localhost:5432`
- MailHog SMTP on `localhost:1025`, Web UI on `localhost:8025`

Verify:
```bash
docker compose ps
# Both services should show "running"
```

### Step 2 — Start the backend

```bash
cd mf-platform
mvn spring-boot:run
```

On first run, Flyway applies all 5 migrations and `DataSeeder` creates the admin account. Look for:
```
Successfully applied 5 migrations to schema "public"
[DataSeeder] Default admin account created. username=admin
Started MfPlatformApplication in X.XXX seconds
```

### Step 3 — Start the frontend

```bash
cd mf-platform-frontend
npm install    # first time only
npm run dev
```

Opens at **http://localhost:5173**

### Step 4 — Log in

```
Username: admin
Password: ChangeMe123!
```

---

## Full-stack setup (Mode 2)

Requires Docker Desktop only. No JDK or Node needed.

```bash
# From the project root (parent of mf-platform/ and mf-platform-frontend/)
cd mf-platform
docker compose --profile fullstack up -d --build
```

The `--build` flag builds the Spring Boot and React images on first run (~3-5 minutes).
Subsequent runs use the cached images and start in seconds.

Access:
- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- MailHog: http://localhost:8025

Stop everything:
```bash
docker compose --profile fullstack down
docker compose --profile fullstack down -v   # also delete postgres data
```

---

Docker handles PostgreSQL, DB creation, and initial schema automatically.
You only need Docker Desktop installed.

### Prerequisites
- Docker Desktop: https://www.docker.com/products/docker-desktop
- JDK 17+: https://adoptium.net (Eclipse Temurin is free and widely used)
- Node.js 18+: https://nodejs.org
- VS Code with **Extension Pack for Java**, **Spring Boot Extension Pack**, **Lombok Annotations Support**

### Step 1 — Start the database

From inside the `mf-platform/` folder:

```bash
docker compose up -d
```

This starts a PostgreSQL 16 container named `mf-platform-db` with:
- Database: `mf_platform`
- User: `mf_user`
- Password: `mf_password`
- Port: `5432`

Verify it's running:
```bash
docker compose ps
# Should show: mf-platform-db   running
```

### Step 2 — Run the backend

From inside `mf-platform/`:

```bash
# Via Maven (first time will download dependencies — takes ~2 minutes)
mvn spring-boot:run

# Or in VS Code: open MfPlatformApplication.java → click Run above main()
```

**What happens on first startup:**
1. Flyway runs all migrations in order:
   - `V1__init_schema.sql` — creates all tables, constraints, indexes
   - `V2__add_transaction_fields.sql` — adds status columns, renames transaction table
   - `V3__distributor_status.sql` — adds distributor lifecycle columns
   - `V4__sip_mandate_updates.sql` — adds SIP columns and index
2. `DataSeeder` runs and creates the default admin account (if not already present)

**Confirming startup worked — look for these log lines:**
```
Flyway Community Edition ... by Redgate
Successfully applied 4 migrations to schema "public"
[DataSeeder] Default admin account created. username=admin
Started MfPlatformApplication in X.XXX seconds
```

### Step 3 — Run the frontend

From inside `mf-platform-frontend/`:

```bash
npm install      # first time only
npm run dev
```

Opens at **http://localhost:5173**

### Step 4 — Log in

Open http://localhost:5173 in your browser.

Default admin credentials:
- Username: `admin`
- Password: `ChangeMe123!`

**Change this immediately in production** (or set `SEED_ADMIN_PASSWORD` env var before first run).

---

## Option B — Manual PostgreSQL (no Docker)

If you already have PostgreSQL installed locally and prefer not to use Docker.

### Step 1 — Create the database and user

Connect to PostgreSQL as a superuser (usually `postgres`):

```bash
# macOS (Homebrew install)
psql postgres

# Linux
sudo -u postgres psql

# Windows — open pgAdmin or psql from Start menu
```

Run these SQL commands:

```sql
-- Create the user
CREATE USER mf_user WITH PASSWORD 'mf_password';

-- Create the database owned by that user
CREATE DATABASE mf_platform OWNER mf_user;

-- Grant all privileges
GRANT ALL PRIVILEGES ON DATABASE mf_platform TO mf_user;

-- Exit
\q
```

Verify you can connect as the new user:
```bash
psql -U mf_user -d mf_platform -h localhost
# Should connect successfully
\q
```

### Step 2 — Verify application.yml points to your instance

Open `mf-platform/src/main/resources/application.yml` and check:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mf_platform
    username: mf_user
    password: mf_password
```

If your PostgreSQL runs on a different port (default is 5432), update the URL.

### Step 3 — Run the backend

Same as Option A Step 2 — Flyway creates all tables on first run.

---

## Admin account — how it works

The admin account is created by `DataSeeder.java` on application startup.

**What DataSeeder does:**
```java
// Checks if the admin username already exists
boolean adminExists = userAccountRepository.findByUsername(seedAdminUsername).isPresent();

if (!adminExists) {
    // Creates a user_account row with role = ADMIN, password BCrypt-hashed
    userService.createAdminAccount(seedAdminUsername, seedAdminPassword);
    System.out.println("[DataSeeder] Default admin account created...");
}
```

**It is idempotent** — runs on every startup but only creates the account if it doesn't exist. Safe to restart the app as many times as you want.

**Configuring the admin credentials:**

The default is `admin` / `ChangeMe123!`. Override via environment variables before first run:

```bash
# macOS/Linux
export SEED_ADMIN_USERNAME=myadmin
export SEED_ADMIN_PASSWORD=MySecurePass123!
mvn spring-boot:run

# Windows PowerShell
$env:SEED_ADMIN_USERNAME="myadmin"
$env:SEED_ADMIN_PASSWORD="MySecurePass123!"
mvn spring-boot:run
```

Or in VS Code, create `.vscode/launch.json`:
```json
{
  "configurations": [
    {
      "type": "java",
      "name": "MfPlatformApplication",
      "request": "launch",
      "mainClass": "com.mfplatform.mfplatform.MfPlatformApplication",
      "env": {
        "SEED_ADMIN_USERNAME": "myadmin",
        "SEED_ADMIN_PASSWORD": "MySecurePass123!"
      }
    }
  ]
}
```

**Important:** If you change `SEED_ADMIN_USERNAME` after first run, the OLD admin account still exists. DataSeeder looks for the NEW username, doesn't find it, and creates a second admin. This is intentional (no data loss). If you want to remove the old account, delete it via SQL:
```sql
DELETE FROM user_account WHERE username = 'admin' AND role = 'ADMIN';
```

---

## Demo data setup (recommended before showing to anyone)

After startup, use the admin account to seed some meaningful data so the app has something to show.

### 1. Create schemes (via the frontend or API)

```bash
# Login as admin, get token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"ChangeMe123!"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Create an equity scheme
curl -X POST http://localhost:8080/api/v1/schemes \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "schemeName": "Bluechip Equity Growth Fund",
    "schemeCode": "BCEG001",
    "category": "EQUITY",
    "cutoffTime": "15:00:00"
  }'

# Create a debt scheme
curl -X POST http://localhost:8080/api/v1/schemes \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "schemeName": "Short Duration Debt Fund",
    "schemeCode": "SDDF001",
    "category": "DEBT",
    "cutoffTime": "13:30:00"
  }'

# Create a hybrid scheme
curl -X POST http://localhost:8080/api/v1/schemes \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "schemeName": "Balanced Advantage Hybrid Fund",
    "schemeCode": "BAHF001",
    "category": "HYBRID",
    "cutoffTime": "15:00:00"
  }'
```

### 2. Simulate historical NAV data (1 year)

```bash
# This generates realistic NAV data for all schemes for the past year
# Random walk model — looks like real fund NAV history
curl -X POST http://localhost:8080/api/v1/nav/simulate-bulk \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "fromDate": "2025-07-14",
    "toDate": "2026-07-14",
    "baseNav": 50.00
  }'
```

Or use the **Import NAV** page in the frontend as admin — simpler.

### 3. Register an investor

Either self-signup via the frontend (Investor tab on login page), or:

```bash
curl -X POST http://localhost:8080/api/v1/auth/signup/investor \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Priya Sharma",
    "email": "priya@example.com",
    "panNumber": "ABCPS1234F",
    "username": "priya.sharma",
    "password": "Investor@123"
  }'
```

### 4. Create a folio for the investor

Log in as the investor, then:
```bash
INVESTOR_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"priya.sharma","password":"Investor@123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

curl -X POST http://localhost:8080/api/v1/folios \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $INVESTOR_TOKEN" \
  -d '{}'
```

### 5. Make a purchase (to show portfolio data)

```bash
# Get the folio ID and scheme ID from the responses above, then:
curl -X POST http://localhost:8080/api/v1/transactions/purchase \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $INVESTOR_TOKEN" \
  -d '{
    "folioId": 1,
    "schemeId": 1,
    "amount": 50000,
    "idempotencyKey": "demo-purchase-001"
  }'
```

After this, the investor's portfolio page shows real data — units held, current value, returns.

---

## Viewing the database directly

Useful for debugging or verifying data during development.

### Via psql (command line)

```bash
# Connect
psql -U mf_user -d mf_platform -h localhost

# Useful queries
\dt                          -- list all tables
SELECT * FROM user_account;  -- see all users and their roles
SELECT * FROM investor;      -- all investors
SELECT * FROM scheme;        -- all schemes
SELECT * FROM nav_history ORDER BY nav_date DESC LIMIT 10;  -- recent NAVs
SELECT * FROM mf_transaction ORDER BY requested_at DESC LIMIT 10;  -- recent txns
SELECT * FROM holding;       -- current unit balances

-- Check Flyway migration history
SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank;
```

### Via pgAdmin (GUI)

1. Download pgAdmin: https://www.pgadmin.org
2. Add a new server:
   - Host: `localhost`
   - Port: `5432`
   - Database: `mf_platform`
   - Username: `mf_user`
   - Password: `mf_password`

### Via VS Code (Database Client extension)

Install **Database Client** extension by Weijan Chen in VS Code.
Connect with the same credentials as pgAdmin above.

---

## Troubleshooting

**"Connection refused" on startup**
→ PostgreSQL isn't running. `docker compose up -d` (Option A) or start the PostgreSQL service.

**"Password authentication failed for user mf_user"**
→ DB user doesn't exist. Run the CREATE USER commands in Option B Step 1.

**"Relation does not exist" errors**
→ Flyway hasn't run yet, or migrations failed. Check logs for Flyway errors.
→ Common cause: running the app against an empty DB where Flyway can't connect.

**"Port 5432 already in use"**
→ Local PostgreSQL is running AND Docker is trying to start another on the same port.
→ Either stop the local PostgreSQL service, or change the Docker port to `5433:5432`
   in `docker-compose.yml` and update `application.yml` URL to `localhost:5433`.

**"DataSeeder: admin account already exists"**
→ Normal — admin was created on a previous run. Nothing to do.

**Frontend shows "Network Error" / API calls failing**
→ Backend isn't running. Start it first, then refresh the frontend.
→ Check `http://localhost:8080/api/v1/schemes` in your browser — if you get a
   response (even a login redirect), the backend is up.

**Lombok errors in VS Code ("cannot find symbol")**
→ Install **Lombok Annotations Support for VS Code** extension.
→ Reload VS Code window after installing.
