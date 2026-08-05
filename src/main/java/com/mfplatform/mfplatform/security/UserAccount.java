package com.mfplatform.mfplatform.security;

import com.mfplatform.mfplatform.common.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // set only for ROLE_INVESTOR
    @Column(name = "investor_id")
    private Long investorId;

    // set only for ROLE_DISTRIBUTOR
    @Column(name = "distributor_id")
    private Long distributorId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
