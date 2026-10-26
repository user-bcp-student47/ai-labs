package com.bcp.workshop.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void hashesPasswordsWithUniqueSaltsAndVerifiesThem() {
        String firstHash = passwordHasher.hash("test-password");
        String secondHash = passwordHasher.hash("test-password");

        assertNotEquals(firstHash, secondHash);
        assertTrue(passwordHasher.matches("test-password", firstHash));
        assertFalse(passwordHasher.matches("wrong-password", firstHash));
    }

    @Test
    void rejectsMalformedHashesAndNullValues() {
        assertFalse(passwordHasher.matches("test-password", null));
        assertFalse(passwordHasher.matches(null, "pbkdf2-sha256$120000$a$b"));
        assertFalse(passwordHasher.matches("test-password", "unknown$120000$a$b"));
        assertFalse(passwordHasher.matches("test-password", "pbkdf2-sha256$invalid$a$b"));
        assertFalse(passwordHasher.matches("test-password", "pbkdf2-sha256$1$a$b"));
        assertFalse(passwordHasher.matches("test-password", "pbkdf2-sha256$120000$%%%$%%%"));
    }

    @Test
    void requiresANonNullPasswordForHashing() {
        assertThrows(IllegalArgumentException.class, () -> passwordHasher.hash(null));
    }
}
