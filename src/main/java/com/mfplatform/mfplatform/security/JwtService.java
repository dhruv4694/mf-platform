package com.mfplatform.mfplatform.security;

import com.mfplatform.mfplatform.common.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMinutes;
    private final long refreshTokenExpiryDays;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry-minutes}") long accessTokenExpiryMinutes,
            @Value("${jwt.refresh-token-expiry-days}") long refreshTokenExpiryDays) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }

    public String generateAccessToken(UserAccount account) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(account.getUsername())
                .claim("userId", account.getId())
                .claim("role", account.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiryMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey);

        if (account.getInvestorId() != null) {
            builder.claim("investorId", account.getInvestorId());
        }
        if (account.getDistributorId() != null) {
            builder.claim("distributorId", account.getDistributorId());
        }
        return builder.compact();
    }

    public String generateRefreshToken(UserAccount account) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(account.getUsername())
                .claim("userId", account.getId())
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenExpiryDays, ChronoUnit.DAYS)))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public ActorContext toActorContext(Claims claims) {
        return new ActorContext(
                claims.get("userId", Long.class),
                Role.valueOf(claims.get("role", String.class)),
                claims.get("investorId", Long.class),
                claims.get("distributorId", Long.class)
        );
    }
}
