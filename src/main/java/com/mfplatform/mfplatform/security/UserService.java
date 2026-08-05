package com.mfplatform.mfplatform.security;

import com.mfplatform.mfplatform.common.Role;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * UserService owns everything related to the user_account table.
 *
 * WHY THIS EXISTS (Option B refactor):
 * Previously, userAccountRepository.save() calls were embedded directly
 * inside InvestorService.createInvestorAccount() and DistributorService.addDistributor().
 * This meant two services both "knew about" how to create login credentials,
 * violating the single responsibility principle.
 *
 * Now:
 *   InvestorService  → creates the investor business entity only
 *   DistributorService → creates the distributor business entity only
 *   UserService      → creates the login credentials only
 *   AuthService      → orchestrates both, calling each service for its part
 *
 * This also makes it trivial to add future account management features
 * (password change, account deactivation, role change) in one place.
 */
@Service
public class UserService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates a user_account row for an investor.
     *
     * Called by AuthService.signupInvestor() and AuthService.addInvestor()
     * after the investor business entity has been created.
     *
     * @param investorId  the id of the newly created Investor row to link to
     * @param username    the desired login username
     * @param rawPassword the plain-text password — BCrypt-hashed before storage
     */
    public UserAccount createInvestorAccount(Long investorId, String username, String rawPassword) {
        return userAccountRepository.save(
                UserAccount.builder()
                        .investorId(investorId)
                        .username(username)
                        .passwordHash(passwordEncoder.encode(rawPassword))
                        .role(Role.INVESTOR)
                        .createdAt(Instant.now())
                        .build()
        );
    }

    /**
     * Creates a user_account row for a distributor.
     *
     * Called by AuthService.addDistributor() after the distributor business
     * entity has been created.
     *
     * @param distributorId the id of the newly created Distributor row to link to
     * @param username      the desired login username
     * @param rawPassword   the plain-text password — BCrypt-hashed before storage
     */
    public UserAccount createDistributorAccount(Long distributorId, String username, String rawPassword) {
        return userAccountRepository.save(
                UserAccount.builder()
                        .distributorId(distributorId)
                        .username(username)
                        .passwordHash(passwordEncoder.encode(rawPassword))
                        .role(Role.DISTRIBUTOR)
                        .createdAt(Instant.now())
                        .build()
        );
    }

    /**
     * Creates a user_account row for an admin.
     * Called only by DataSeeder on first startup to bootstrap the initial admin.
     *
     * @param username    the admin username
     * @param rawPassword the plain-text password — BCrypt-hashed before storage
     */
    public UserAccount createAdminAccount(String username, String rawPassword) {
        return userAccountRepository.save(
                UserAccount.builder()
                        .username(username)
                        .passwordHash(passwordEncoder.encode(rawPassword))
                        .role(Role.ADMIN)
                        .createdAt(Instant.now())
                        .build()
        );
    }
}
