package vn.edu.ds03.driver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class SecurityConfigTest {

    private static final String ISSUER = "ds03-user-service";
    private static final byte[] DETERMINISTIC_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private final SecurityConfig securityConfig = new SecurityConfig();
    private JwtDecoder decoder;
    private JwtEncoder encoder;

    @BeforeEach
    void setUp() {
        String paddedBase64Secret = "  " + Base64.getEncoder().encodeToString(DETERMINISTIC_KEY_BYTES) + "\n";
        decoder = securityConfig.jwtDecoder(paddedBase64Secret, ISSUER);
        SecretKey secretKey = new SecretKeySpec(DETERMINISTIC_KEY_BYTES, "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(secretKey).build();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "%%%not-base64%%%"})
    @DisplayName("jwtDecoder throws IllegalArgumentException for null, blank, or invalid Base64 secrets")
    void jwtDecoder_nullBlankOrMalformedBase64Secret_throwsIllegalArgumentException(String invalidSecret) {
        assertThatThrownBy(() -> securityConfig.jwtDecoder(invalidSecret, ISSUER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("jwtDecoder throws IllegalArgumentException when issuer is null or blank")
    void jwtDecoder_nullOrBlankIssuer_throwsIllegalArgumentException(String invalidIssuer) {
        String validSecret = Base64.getEncoder().encodeToString(DETERMINISTIC_KEY_BYTES);

        assertThatThrownBy(() -> securityConfig.jwtDecoder(validSecret, invalidIssuer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_ISSUER");
    }

    @Test
    @DisplayName("jwtDecoder throws IllegalArgumentException when decoded secret is 31 bytes (< 32-byte minimum)")
    void jwtDecoder_thirtyOneByteSecret_throwsIllegalArgumentException() {
        byte[] thirtyOneBytes = new byte[31];
        String shortSecret = Base64.getEncoder().encodeToString(thirtyOneBytes);

        assertThatThrownBy(() -> securityConfig.jwtDecoder(shortSecret, ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("parseInternalTokenDigest fails closed (null) when blank, rejects < 32 chars, and hashes >= 32 chars")
    void parseInternalTokenDigest_blankShortAndValidTokens_behavesAsExpected() {
        assertThat(SecurityConfig.parseInternalTokenDigest(null)).isNull();
        assertThat(SecurityConfig.parseInternalTokenDigest("")).isNull();
        assertThat(SecurityConfig.parseInternalTokenDigest("   ")).isNull();

        assertThatThrownBy(() -> SecurityConfig.parseInternalTokenDigest("x".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 characters");

        assertThat(SecurityConfig.parseInternalTokenDigest("x".repeat(32))).hasSize(32);
    }

    @Test
    @DisplayName("jwtDecoder trims Base64 secret and decodes valid HS256 token with matching issuer and subject")
    void jwtDecoder_validHs256Token_returnsDecodedJwtClaims() {
        Instant now = Instant.now();
        String validToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject("550e8400-e29b-41d4-a716-446655440000")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        Jwt decoded = decoder.decode(validToken);

        assertThat(decoded.getSubject()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("DRIVER");
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    @DisplayName("jwtDecoder rejects alg:none, HS384, missing/wrong issuer, missing/expired exp, future nbf, and invalid sub")
    void jwtDecoder_rejectedTokenVariants_throwsJwtException(String tokenCase) {
        assertThatThrownBy(() -> decoder.decode(tokenCase))
                .isInstanceOf(JwtException.class);
    }

    static Stream<String> rejectedTokens() throws Exception {
        SecretKey hs256Key = new SecretKeySpec(DETERMINISTIC_KEY_BYTES, "HmacSHA256");
        JwtEncoder hs256Encoder = NimbusJwtEncoder.withSecretKey(hs256Key).build();

        Instant now = Instant.now();
        String validSub = "550e8400-e29b-41d4-a716-446655440000";

        String algNoneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String algNonePayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + validSub
                        + "\",\"exp\":" + now.plusSeconds(300).getEpochSecond()
                        + ",\"role\":\"DRIVER\"}").getBytes(StandardCharsets.UTF_8));
        String algNoneToken = algNoneHeader + "." + algNonePayload + ".";

        String hs384Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS384\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String hs384SigningInput = hs384Header + "." + algNonePayload;
        javax.crypto.Mac mac384 = javax.crypto.Mac.getInstance("HmacSHA384");
        mac384.init(new SecretKeySpec(DETERMINISTIC_KEY_BYTES, "HmacSHA384"));
        String hs384Sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac384.doFinal(hs384SigningInput.getBytes(StandardCharsets.US_ASCII)));
        String hs384Token = hs384SigningInput + "." + hs384Sig;

        String wrongIssuer = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("unexpected-issuer")
                        .subject(validSub)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String missingIssuer = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .subject(validSub)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String expiredToken = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject(validSub)
                        .issuedAt(now.minusSeconds(600))
                        .expiresAt(now.minusSeconds(120))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String missingExp = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject(validSub)
                        .issuedAt(now)
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String futureNbf = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject(validSub)
                        .issuedAt(now)
                        .notBefore(now.plusSeconds(120))
                        .expiresAt(now.plusSeconds(600))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String missingSub = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String blankSub = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject("   ")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String oversizedSub = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject("x".repeat(37))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String internalSpaceSub = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject("user id")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        String nonAsciiSub = hs256Encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject("user-\u00E9")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        return Stream.of(
                algNoneToken,
                hs384Token,
                wrongIssuer,
                missingIssuer,
                expiredToken,
                missingExp,
                futureNbf,
                missingSub,
                blankSub,
                oversizedSub,
                internalSpaceSub,
                nonAsciiSub);
    }
}
