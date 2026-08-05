package com.mfplatform.mfplatform.common;

import com.mfplatform.mfplatform.security.UserAccountRepository;
import com.mfplatform.mfplatform.security.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * DataSeeder runs once on application startup to bootstrap the initial ADMIN account.
 *
 * The bootstrap problem:
 * Creating an ADMIN account normally requires already being an ADMIN (POST /distributors
 * requires ADMIN auth). Without this seeder, the very first admin would have no way
 * to log in. DataSeeder solves this by creating the admin account directly via
 * UserService on first startup, bypassing the API layer entirely.
 *
 * Idempotency:
 * DataSeeder checks whether the admin username already exists before creating it.
 * This means it's safe to run on every startup — it won't duplicate or reset an
 * existing admin's password if they've changed it since first startup.
 *
 * Configuration:
 * Username and password are configurable via environment variables:
 *   SEED_ADMIN_USERNAME (default: "admin")
 *   SEED_ADMIN_PASSWORD (default: "ChangeMe123!")
 * Always override these in real deployments via env vars, never via application.yml.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final UserAccountRepository userAccountRepository;
    private final UserService userService;
    private final String seedAdminUsername;
    private final String seedAdminPassword;

    public DataSeeder(
            UserAccountRepository userAccountRepository,
            UserService userService,
            @Value("${app.seed-admin.username:admin}") String seedAdminUsername,
            @Value("${app.seed-admin.password:ChangeMe123!}") String seedAdminPassword) {
        this.userAccountRepository = userAccountRepository;
        this.userService = userService;
        this.seedAdminUsername = seedAdminUsername;
        this.seedAdminPassword = seedAdminPassword;
    }

    @Override
    public void run(String... args) {
        boolean adminExists = userAccountRepository.findByUsername(seedAdminUsername).isPresent();

        if (!adminExists) {
            // Uses UserService (not userAccountRepository directly) to keep
            // account creation logic in one place
            userService.createAdminAccount(seedAdminUsername, seedAdminPassword);
            System.out.println("[DataSeeder] Default admin account created. " +
                    "CHANGE THIS PASSWORD before any real deployment. username=" + seedAdminUsername);
        }
    }
}
