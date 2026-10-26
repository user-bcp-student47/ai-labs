package com.bcp.workshop.auth;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String HASH_FORMAT = "pbkdf2-sha256";
    private static final int ITERATION_COUNT = 120_000;
    private static final int MINIMUM_ITERATION_COUNT = 100_000;
    private static final int MAXIMUM_ITERATION_COUNT = 1_000_000;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BITS = 256;
    private static final String FIELD_SEPARATOR = "\\$";

    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password is required");
        }

        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derivedKey = deriveKey(password, salt, ITERATION_COUNT);
        return HASH_FORMAT + "$" + ITERATION_COUNT + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derivedKey);
    }

    public boolean matches(String password, String encodedHash) {
        if (password == null || encodedHash == null) {
            return false;
        }

        String[] fields = encodedHash.split(FIELD_SEPARATOR, -1);
        if (fields.length != 4 || !HASH_FORMAT.equals(fields[0])) {
            return false;
        }

        int iterationCount;
        try {
            iterationCount = Integer.parseInt(fields[1]);
        } catch (NumberFormatException error) {
            return false;
        }
        if (iterationCount < MINIMUM_ITERATION_COUNT || iterationCount > MAXIMUM_ITERATION_COUNT) {
            return false;
        }

        try {
            byte[] salt = Base64.getDecoder().decode(fields[2]);
            byte[] expectedHash = Base64.getDecoder().decode(fields[3]);
            byte[] actualHash = deriveKey(password, salt, iterationCount);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static byte[] deriveKey(String password, byte[] salt, int iterationCount) {
        char[] passwordCharacters = password.toCharArray();
        PBEKeySpec keySpec = new PBEKeySpec(passwordCharacters, salt, iterationCount, HASH_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing is unavailable", error);
        } finally {
            keySpec.clearPassword();
            java.util.Arrays.fill(passwordCharacters, '\0');
        }
    }
}
