package com.bcp.workshop.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@ApplicationScoped
public class TokenService {
    public static final long EXPIRATION_SECONDS = 3600;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TOKEN_ALGORITHM = "HS256";
    private static final String TOKEN_TYPE = "JWT";
    private static final int MINIMUM_SECRET_LENGTH_BYTES = 32;
    private static final int TOKEN_SEGMENT_COUNT = 3;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "auth.token.secret")
    String signingSecret;

    @PostConstruct
    void validateSigningSecret() {
        signingKey();
    }

    public String create(String username, String role) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + EXPIRATION_SECONDS;
        String header = encodeJson(Map.of("alg", TOKEN_ALGORITHM, "typ", TOKEN_TYPE));
        String claims = encodeJson(Map.of(
                "sub", username,
                "role", role,
                "iat", issuedAt,
                "exp", expiresAt
        ));
        String signingInput = header + "." + claims;
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sign(signingInput));
    }

    public TokenClaims verify(String token) {
        String[] segments = token == null ? new String[0] : token.split("\\.", -1);
        if (segments.length != TOKEN_SEGMENT_COUNT) {
            throw new IllegalArgumentException("Invalid token");
        }

        JsonNode header = decodeJson(segments[0]);
        if (!TOKEN_ALGORITHM.equals(textClaim(header, "alg"))
                || !TOKEN_TYPE.equals(textClaim(header, "typ"))) {
            throw new IllegalArgumentException("Unsupported token header");
        }

        String signingInput = segments[0] + "." + segments[1];
        byte[] providedSignature;
        try {
            providedSignature = Base64.getUrlDecoder().decode(segments[2]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid token signature", error);
        }
        if (!MessageDigest.isEqual(sign(signingInput), providedSignature)) {
            throw new IllegalArgumentException("Invalid token signature");
        }

        JsonNode claims = decodeJson(segments[1]);
        String username = textClaim(claims, "sub");
        String role = textClaim(claims, "role");
        long issuedAt = numericClaim(claims, "iat");
        long expiresAt = numericClaim(claims, "exp");
        if (username.isBlank() || role.isBlank() || expiresAt <= Instant.now().getEpochSecond()
                || expiresAt <= issuedAt) {
            throw new IllegalArgumentException("Invalid token claims");
        }
        return new TokenClaims(username, role, issuedAt, expiresAt);
    }

    private String encodeJson(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode authentication token", error);
        }
    }

    private JsonNode decodeJson(String segment) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(segment);
            return objectMapper.readTree(json);
        } catch (IllegalArgumentException | java.io.IOException error) {
            throw new IllegalArgumentException("Invalid token encoding", error);
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey(), HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Token signing is unavailable", error);
        }
    }

    private byte[] signingKey() {
        byte[] key = signingSecret == null ? new byte[0] : signingSecret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MINIMUM_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException("Authentication signing secret must contain at least 32 bytes");
        }
        return key;
    }

    private static String textClaim(JsonNode claims, String name) {
        JsonNode value = claims.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Invalid token claim: " + name);
        }
        return value.textValue();
    }

    private static long numericClaim(JsonNode claims, String name) {
        JsonNode value = claims.get(name);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid token claim: " + name);
        }
        return value.longValue();
    }

    public record TokenClaims(String username, String role, long issuedAt, long expiresAt) {}
}
