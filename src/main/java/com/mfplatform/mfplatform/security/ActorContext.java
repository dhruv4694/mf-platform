package com.mfplatform.mfplatform.security;

import com.mfplatform.mfplatform.common.Role;

/**
 * Resolved identity of whoever made the current request, derived from the JWT.
 * investorId is set only when role == INVESTOR; distributorId only when role == DISTRIBUTOR.
 */
public record ActorContext(
        Long userId,
        Role role,
        Long investorId,
        Long distributorId
) {}
