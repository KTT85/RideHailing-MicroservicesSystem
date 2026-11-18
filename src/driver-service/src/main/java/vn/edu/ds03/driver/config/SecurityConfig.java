package vn.edu.ds03.driver.config;

import jakarta.servlet.DispatcherType;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_SUBJECT_LENGTH = 36;

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.jwt.secret}") String base64Secret,
            @Value("${app.jwt.issuer}") String issuer) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalArgumentException("JWT_SECRET must not be blank");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT_ISSUER must not be blank");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT_SECRET must be Base64 encoded", exception);
        }
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("JWT_SECRET must decode to at least 32 bytes");
        }

        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuer.trim());
        OAuth2TokenValidator<Jwt> expirationRequiredValidator =
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull);
        OAuth2TokenValidator<Jwt> subjectValidator = new JwtClaimValidator<Object>(
                JwtClaimNames.SUB, claim -> claim instanceof String s && isValidSubject(s));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                defaultValidator, expirationRequiredValidator, subjectValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("role");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/drivers/profile")
                        .hasRole("DRIVER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    private static boolean isValidSubject(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            return false;
        }
        return subject.chars().allMatch(ch -> ch > 0x20 && ch <= 0x7E);
    }
}
