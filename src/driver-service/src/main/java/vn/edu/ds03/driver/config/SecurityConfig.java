package vn.edu.ds03.driver.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MIN_INTERNAL_TOKEN_LENGTH = 32;
    private static final int MAX_INTERNAL_HEADER_LENGTH = 512;
    private static final int MAX_SUBJECT_LENGTH = 36;
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

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
    @Order(1)
    SecurityFilterChain internalSecurityFilterChain(
            HttpSecurity http,
            @Value("${app.internal-token:}") String internalToken) throws Exception {
        byte[] expectedDigest = parseInternalTokenDigest(internalToken);

        return http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalServiceTokenFilter(expectedDigest), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/drivers/candidates")
                        .hasRole("INTERNAL_SERVICE")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/internal/drivers/*/reserve",
                                "/internal/drivers/*/start",
                                "/internal/drivers/*/release")
                        .hasRole("INTERNAL_SERVICE")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((req, res, ex) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> res.setStatus(HttpServletResponse.SC_FORBIDDEN)))
                .build();
    }

    @Bean
    @Order(2)
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
                        .requestMatchers(HttpMethod.GET, "/drivers/me")
                        .hasRole("DRIVER")
                        .requestMatchers(HttpMethod.PUT, "/drivers/me/location")
                        .hasRole("DRIVER")
                        .requestMatchers(HttpMethod.PATCH, "/drivers/me/availability")
                        .hasRole("DRIVER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    static byte[] parseInternalTokenDigest(String internalToken) {
        if (internalToken == null || internalToken.isBlank()) {
            return null;
        }
        String trimmed = internalToken.trim();
        if (trimmed.length() < MIN_INTERNAL_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "DRIVER_INTERNAL_TOKEN must contain at least 32 characters");
        }
        return sha256(trimmed);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    private static boolean isValidSubject(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            return false;
        }
        return subject.chars().allMatch(ch -> ch > 0x20 && ch <= 0x7E);
    }

    private static final class InternalServiceTokenFilter extends OncePerRequestFilter {

        private final byte[] expectedDigest;

        InternalServiceTokenFilter(byte[] expectedDigest) {
            this.expectedDigest = expectedDigest;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            if (expectedDigest != null) {
                String supplied = request.getHeader(SERVICE_TOKEN_HEADER);
                if (supplied != null
                        && !supplied.isBlank()
                        && supplied.length() <= MAX_INTERNAL_HEADER_LENGTH
                        && MessageDigest.isEqual(expectedDigest, sha256(supplied.trim()))) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    "internal-service",
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
