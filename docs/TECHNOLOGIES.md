# Technologies Used

Every technology in this project, why it was chosen, and what it does.

---

## Core Framework

### Spring Boot 3.3
**What:** An opinionated framework for building Java web applications.
Provides auto-configuration — most things work out of the box without
manual wiring.

**Why:** Industry standard for Java backend development. The most commonly
required framework in Java job descriptions. Spring Boot 3.x requires Java 17+,
which signals that we're using modern Java.

**Key Spring Boot features used:**
- `@SpringBootApplication` — enables component scanning, auto-configuration
- `spring-boot-starter-web` — embedded Tomcat, Jackson JSON serialisation,
  Spring MVC for REST controllers
- `spring-boot-starter-validation` — Bean Validation (`@NotBlank`, `@Email`,
  `@Size`) wired into controller request handling
- `spring-boot-starter-data-jpa` — JPA + Hibernate ORM, Spring Data repositories
- `spring-boot-starter-security` — Spring Security for authentication/authorisation

---

### Spring Security
**What:** Authentication and authorisation framework integrated with Spring Boot.

**How we use it:**
1. `SecurityConfig` — defines which endpoints are public vs protected
2. `JwtAuthFilter` — reads the `Authorization: Bearer <token>` header on every
   request, validates the JWT, and populates the `SecurityContext`
3. `@EnableMethodSecurity` — enables `@PreAuthorize` on controller methods
4. `@PreAuthorize("hasRole('ADMIN')")` — evaluated by Spring AOP before the
   method body runs; returns 403 if false
5. `@PreAuthorize("@folioSecurity.canView(#id, authentication)")` — calls a
   Spring-managed bean for complex ownership checks

**Why stateless (no sessions):**
REST APIs should be stateless — no server-side session state. JWT tokens carry
all identity information; the server doesn't store anything about the session.
This makes the app horizontally scalable (any instance can handle any request).

---

### Spring Data JPA + Hibernate
**What:** Spring Data JPA provides repository interfaces; Hibernate is the JPA
implementation that translates them to SQL.

**What we write:**
```java
public interface InvestorRepository extends JpaRepository<Investor, Long> {
    Page<Investor> findByDistributorId(Long distributorId, Pageable pageable);
}
```

**What Spring generates (SQL):**
```sql
SELECT * FROM investor WHERE distributor_id = ? LIMIT ? OFFSET ?
```

We declare what we want; Spring/Hibernate writes the SQL. We only write
explicit `@Query` JPQL for complex joins or conditional updates.

**Key JPA features used:**
- `@Entity`, `@Table`, `@Column` — entity-to-table mapping
- `@GeneratedValue(strategy = IDENTITY)` — DB auto-increment for PKs
- `@Version` — optimistic locking (Hibernate manages the version column)
- `@ManyToOne(fetch = FetchType.LAZY)` — relationship without eager loading
- `@Enumerated(EnumType.STRING)` — store enum as string, not ordinal integer
  (ordinal breaks if enum values are reordered)
- `@UniqueConstraint` — maps to DB UNIQUE constraint
- Pageable — automatic LIMIT/OFFSET pagination

---

### Spring ApplicationEventPublisher
**What:** Spring's built-in synchronous event bus.

**How we use it:**
```java
// Publisher (NavImportService)
eventPublisher.publishEvent(new NavImportedEvent(this, savedNav));

// Listener (SipExecutionService)
@EventListener
public void onNavImported(NavImportedEvent event) { ... }
```

**Why not Kafka/RabbitMQ:**
Spring events are in-process and synchronous — simpler, no infrastructure to
run. Sufficient for a portfolio project. The trade-off (synchronous = publisher
waits for listener to finish) is documented as a known limitation. Upgrading to
Kafka would mean changing the publisher call and the listener annotation,
leaving business logic untouched.

---

## Database

### PostgreSQL 16
**What:** Open-source relational database. Production-grade, ACID compliant.

**Why PostgreSQL over MySQL:**
- Better standards compliance (e.g. `LIMIT` in subqueries works consistently)
- `NUMERIC(12,4)` type for exact decimal arithmetic — critical for financial data
- More advanced constraint support (partial indexes, check constraints)
- Preferred in most modern Java/Spring job descriptions

**Key DB features we use:**
- `BIGSERIAL` — auto-incrementing PK (PostgreSQL syntax)
- `NUMERIC(12,4)` — exact decimal, 4 places (not FLOAT which is approximate)
- `UNIQUE` constraints — enforced at DB level, not just application level
- `CHECK` constraints — e.g. `CHECK (category IN ('EQUITY', 'DEBT', 'HYBRID'))`
  ensures invalid enum values can never be stored even if application validation fails
- `REFERENCES` (FK constraints) — relational integrity enforced at DB level
- Partial index: `CREATE INDEX ... WHERE status = 'ACTIVE'` — only indexes
  active SIP mandates, reducing index size and improving scheduler query speed

---

### Flyway
**What:** Database migration tool. Manages schema changes as versioned SQL scripts.

**How it works:**
- SQL files named `V1__init_schema.sql`, `V2__add_column.sql` etc.
- On startup, Flyway checks which migrations have already run (tracked in a
  `flyway_schema_history` table) and applies any new ones in order
- `ddl-auto: validate` in `application.yml` tells Hibernate to only validate
  the schema matches entities — never auto-generate DDL

**Why not Hibernate DDL generation (`ddl-auto: create` or `update`):**
- `create` drops and recreates tables on every startup — data loss
- `update` tries to alter tables — unsafe for production (can't drop columns,
  doesn't handle renames)
- Flyway gives full control — you write exactly the SQL you want, reviewed,
  version-controlled, reproducible on any environment

**Interview talking point:**
"Flyway migrations are committed to the repo alongside code changes. A DB schema
change and the code that uses it are always deployed together. Rolling back a
Flyway migration requires writing a new migration (V3__rollback_something.sql),
not running a script backwards — this forces intentional, auditable changes."

---

## Security

### JWT (JSON Web Tokens) via jjwt 0.12.5
**What:** A standard for representing claims securely as a compact, URL-safe
token. Consists of three parts: header.payload.signature (all Base64-encoded).

**Structure of our tokens:**
```json
{
  "sub": "john.smith",
  "userId": 42,
  "role": "INVESTOR",
  "investorId": 7,
  "iat": 1720000000,
  "exp": 1720000900
}
```

**Access token vs refresh token:**
- Access token: short-lived (15 min), sent with every API request
- Refresh token: long-lived (7 days), sent only to `POST /auth/refresh`
- Short access token lifetime limits damage if a token is intercepted —
  it expires before an attacker can do much with it

**Why the role and entity IDs are in the token:**
`ActorContext` (role, investorId, distributorId) is extracted from the JWT on
every request by `JwtAuthFilter`. This means **no database lookup per request**
to determine who is asking and what role they have. The token carries its own
identity — this is what makes the API stateless.

**Why HMAC-SHA256 (HS256) signing:**
The token is signed with a secret key. The server verifies the signature on
every request — if the token was tampered with (e.g. changing `role: INVESTOR`
to `role: ADMIN`), the signature verification fails and the request is rejected.

---

### BCrypt Password Hashing
**What:** A slow, adaptive hashing algorithm designed specifically for passwords.

**Why BCrypt over SHA-256 or MD5:**
- BCrypt is intentionally slow (configurable work factor, default ~100ms)
- Slowness doesn't matter for legitimate logins (100ms is fine)
- Slowness makes brute-force attacks expensive — an attacker can only try ~10
  passwords/second vs millions with MD5
- Built-in salt — each hash is unique even for identical passwords; rainbow
  tables are useless
- `passwordEncoder.matches(raw, hash)` — Spring Security's BCryptPasswordEncoder
  handles salt extraction and comparison automatically

---

## Build & Infrastructure

### Maven
**What:** Build tool and dependency manager.

**Key files:**
- `pom.xml` — declares dependencies, Java version, build plugins
- `spring-boot-starter-parent` — manages compatible dependency versions;
  you declare `spring-boot-starter-web` without a version because the parent
  BOM (Bill of Materials) specifies the compatible version

**Why Maven over Gradle:**
Both are fine. Maven uses XML (more verbose, less flexible); Gradle uses a DSL
(more concise, more powerful). Maven is more commonly seen in enterprise Java
environments and more familiar to Java interviewers. Either choice is defensible.

---

### Docker + Docker Compose
**What:** Docker packages the application and its dependencies into containers.
Docker Compose orchestrates multiple containers together.

**`docker-compose.yml` in this project:**
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: mf_platform
      POSTGRES_USER: mf_user
      POSTGRES_PASSWORD: mf_password
    ports:
      - "5432:5432"
```

One command (`docker compose up -d`) starts a PostgreSQL instance with the
correct database and credentials. No local PostgreSQL installation needed.

**Phase 10 — Dockerising the app itself:**
A `Dockerfile` will package the Spring Boot jar into a container image, so the
entire stack (app + database) can be started with one `docker compose up`.

---

### Lombok
**What:** Annotation processor that generates boilerplate Java code at compile time.

**Annotations used:**

| Annotation | Generates |
|---|---|
| `@Getter` | Getter methods for all fields |
| `@Builder` | Static builder class for the annotated class |
| `@NoArgsConstructor` | Constructor with no arguments (required by JPA) |
| `@AllArgsConstructor` | Constructor with all fields (required by `@Builder`) |

**Why NOT `@Data`:**
`@Data` generates getters, setters, equals, hashCode, toString. We don't want
setters on entities (immutability preference) and JPA's `equals`/`hashCode`
from field values can cause subtle issues with proxied entities. We use only
the specific annotations we need.

---

## Testing (Phase 10)

### JUnit 5
Standard Java testing framework. Comes with `spring-boot-starter-test`.

### Mockito
Mocking framework — creates fake implementations of dependencies so you can
test a service in isolation without a real database.

```java
// Testing InvestorService without a real DB
@ExtendWith(MockitoExtension.class)
class InvestorServiceTest {
    @Mock InvestorRepository investorRepository;
    @Mock OwnershipValidator ownershipValidator;
    @InjectMocks InvestorService investorService;

    @Test
    void getById_whenInvestorExists_returnsResponse() {
        when(investorRepository.findById(1L))
            .thenReturn(Optional.of(new Investor(...)));

        InvestorResponse response = investorService.getById(1L);

        assertThat(response.name()).isEqualTo("John Smith");
    }
}
```

### Testcontainers
Spins up a real PostgreSQL container for integration tests. Tests run against
a real database, not an in-memory H2 substitute.

```java
// Integration test — real PostgreSQL, real Flyway migrations
@SpringBootTest
@Testcontainers
class InvestorRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
}
```

**Why Testcontainers over H2:**
H2 is an in-memory database that speaks a different SQL dialect. Tests passing
against H2 can fail against PostgreSQL (different type casting, different
constraint behaviour). Testcontainers tests are what actually runs in production.

---

## Frontend (Phase 9)

### React 18
Component-based UI library. Each piece of the UI is a self-contained component.

### React Query (TanStack Query)
Data fetching and server state management. Handles:
- Caching API responses
- Background refetching
- Loading and error states
- Automatic retry on failure

Preferable to Redux for REST APIs because it's designed specifically for
"fetch data from a server, display it, handle loading/error states" — which is
90% of what this frontend does.

### Recharts
Chart library built on React and D3. Used for:
- NAV history line charts
- Portfolio value over time
- Asset allocation pie charts

### Tailwind CSS
Utility-first CSS framework. Classes like `flex`, `p-4`, `text-lg` applied
directly in JSX instead of separate CSS files. Fast to build with, consistent
design system by default.

---

## Deployment (Phase 10)

### Railway or Render
Cloud platforms that can deploy a Dockerised Spring Boot app + managed
PostgreSQL database with minimal configuration. Free tier available for
portfolio projects.

**What the deployment will include:**
- Docker image of the Spring Boot app
- Managed PostgreSQL instance (data persists between deploys)
- Environment variables for secrets (`JWT_SECRET`, `SEED_ADMIN_PASSWORD`, etc.)
- A public URL to include in your portfolio/resume

### GitHub Actions (CI/CD)
Automated pipeline that runs on every push:
1. Compile the project (`mvn compile`)
2. Run tests (`mvn test`)
3. Build Docker image
4. Deploy to Railway/Render if tests pass

A working CI/CD pipeline on a portfolio project is a meaningful differentiator —
most candidates don't have it.
