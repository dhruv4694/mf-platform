package com.mfplatform.mfplatform;

import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for all integration tests that need a real PostgreSQL database.
 *
 * TESTCONTAINERS:
 * @Testcontainers activates the Testcontainers JUnit 5 extension.
 * @Container marks the PostgreSQLContainer as a managed container —
 * Testcontainers starts it before tests run and stops it after.
 *
 * WHY TESTCONTAINERS OVER H2:
 * H2 is an in-memory database that speaks a different SQL dialect.
 * Tests that pass against H2 can fail against PostgreSQL because:
 *   - Different type casting rules
 *   - Different CHECK constraint behavior
 *   - Different JSONB support
 *   - H2 doesn't support partial indexes (which we use)
 * Testcontainers runs a real PostgreSQL 16 container — identical to production.
 *
 * STATIC CONTAINER (shared across all tests in a class):
 * The @Container is static, which means ONE container is started for all tests
 * in a subclass. This is faster than starting a new container per test.
 * Flyway runs its migrations once against this container — the same state
 * that production would have on a fresh deploy.
 *
 * @DynamicPropertySource:
 * Overrides the Spring datasource URL/username/password at test runtime
 * to point to the Testcontainers PostgreSQL instance (random port).
 * This replaces the localhost:5432 from application.yml.
 *
 * @TestInstance(PER_CLASS):
 * Allows @BeforeAll and @AfterAll to be non-static (nicer with Kotlin too).
 * Means one test instance is created per class, not per method.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("mf_platform_test")
                .withUsername("mf_user_test")
                .withPassword("mf_password_test");

    /**
     * Injects the Testcontainers PostgreSQL URL into Spring's datasource config.
     * Called by Spring before the application context is created.
     * getJdbcUrl() returns something like:
     *   jdbc:postgresql://localhost:54321/mf_platform_test
     * where 54321 is a random port chosen by Testcontainers.
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Force Flyway to run migrations on the test container
        registry.add("spring.flyway.enabled", () -> "true");
        // Disable the DataSeeder in tests — we set up test data ourselves
        registry.add("app.seed-admin.username", () -> "test-admin");
        registry.add("app.seed-admin.password", () -> "TestAdmin123!");
    }
}
