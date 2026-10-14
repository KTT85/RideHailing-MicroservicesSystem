package vn.edu.ds03.user.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import vn.edu.ds03.user.model.User;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final long expiresIn;

    public JwtService(JwtEncoder encoder, @Value("${app.jwt.issuer}") String issuer,
                      @Value("${app.jwt.expires-in-seconds}") long expiresIn) {
        if (expiresIn <= 0) throw new IllegalArgumentException("JWT expiry must be positive");
        this.encoder = encoder;
        this.issuer = issuer;
        this.expiresIn = expiresIn;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getId())
                .issuedAt(now).expiresAt(now.plusSeconds(expiresIn)).id(UUID.randomUUID().toString())
                .claim("userId", user.getId()).claim("role", user.getRole().name()).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public long getExpiresIn() { return expiresIn; }
}
