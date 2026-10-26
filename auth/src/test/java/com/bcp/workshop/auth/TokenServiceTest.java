package com.bcp.workshop.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TokenServiceTest {
    private static final String TEST_SECRET = "test-only-signing-key-with-at-least-32-bytes";
    private static final String TEST_ALGORITHM = "HmacSHA256";

    @Inject
    TokenService tokenService;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void createsSignedTokenWithSubjectRoleAndExpiration() {
        String token = tokenService.create("student", "STUDENT");
        TokenService.TokenClaims claims = tokenService.verify(token);

        assertEquals("student", claims.username());
        assertEquals("STUDENT", claims.role());
        assertEquals(TokenService.EXPIRATION_SECONDS, claims.expiresAt() - claims.issuedAt());
    }

    @Test
    void rejectsTamperedSignatureAndMalformedToken() {
        String token = tokenService.create("student", "STUDENT");
        String[] segments = token.split("\\.", -1);
        String tamperedToken = segments[0] + "." + segments[1] + ".invalidsignature";

        assertThrows(IllegalArgumentException.class, () -> tokenService.verify(tamperedToken));
        assertThrows(IllegalArgumentException.class, () -> tokenService.verify("not-a-jwt"));
        assertThrows(IllegalArgumentException.class, () -> tokenService.verify("!!!.e30.signature"));
    }

    @Test
    void rejectsUnsupportedAlgorithmAndExpiredClaims() {
        long now = Instant.now().getEpochSecond();
        String unsupportedToken = signedToken(
                Map.of("alg", "none", "typ", "JWT"),
                Map.of("sub", "student", "role", "STUDENT", "iat", now, "exp", now + 60)
        );
        String expiredToken = signedToken(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of("sub", "student", "role", "STUDENT", "iat", now - 120, "exp", now - 60)
        );

        assertThrows(IllegalArgumentException.class, () -> tokenService.verify(unsupportedToken));
        assertThrows(IllegalArgumentException.class, () -> tokenService.verify(expiredToken));
    }

    @Test
    void rejectsSigningSecretsShorterThanThirtyTwoBytes() {
        TokenService service = new TokenService();
        service.objectMapper = objectMapper;
        service.signingSecret = "too-short";

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.create("student", "STUDENT")
        );
        assertTrue(error.getMessage().contains("32 bytes"));
    }

    private String signedToken(Map<String, Object> header, Map<String, Object> claims) {
        try {
            String encodedHeader = encode(header);
            String encodedClaims = encode(claims);
            String signingInput = encodedHeader + "." + encodedClaims;
            Mac mac = Mac.getInstance(TEST_ALGORITHM);
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), TEST_ALGORITHM));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (GeneralSecurityException | JsonProcessingException error) {
            throw new AssertionError("Unable to create isolated test token", error);
        }
    }

    private String encode(Object value) throws JsonProcessingException {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(value));
    }
}
