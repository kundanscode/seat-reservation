package com.kundan.seat_reservation.security.demo;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class DemoTokenService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final Duration tokenTtl;

    public DemoTokenService(
            JwtEncoder jwtEncoder,
            @Value("${app.security.jwt-issuer:seat-reservation}") String issuer,
            @Value("${app.security.demo-token-ttl:15m}") Duration tokenTtl) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.tokenTtl = tokenTtl;
    }

    public DemoTokenResponse issueUserToken(String userId) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(tokenTtl))
                .claim("scope", "user")
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        return new DemoTokenResponse(token, "Bearer", tokenTtl.toSeconds());
    }
}
