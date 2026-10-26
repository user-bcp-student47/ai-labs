package com.bcp.workshop.auth;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AuthResourceTest {
    private static final String TEST_DATABASE_URL =
            System.getenv().getOrDefault("AUTH_DB_URL", "jdbc:sqlite:./target/workshop-auth-test.db");
    private static final String TEST_USERNAME_WITH_QUOTE = "workshop'o'brien";
    private static final String TEST_PASSWORD = "IsolatedTestPassword42!";

    @Inject
    AuthService authService;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    TokenService tokenService;

    @Test
    void logsInWithWorkshopUser() {
        String token = given()
                .contentType("application/json")
                .body("{\"username\":\"student\",\"password\":\"Workshop123!\",\"client\":\"angular-workshop\"}")
                .when().post("/auth/login")
                .then()
                .statusCode(200)
                .body("user.username", is("student"))
                .body("user.displayName", is("Workshop Student"))
                .body("user.role", is("STUDENT"))
                .body("expiresIn", is(3600))
                .body("debug", is("authenticated-with-legacy-flow"))
                .body("token", notNullValue())
                .extract()
                .path("token");

        TokenService.TokenClaims claims = tokenService.verify(token);
        assertEquals("student", claims.username());
        assertEquals("STUDENT", claims.role());
    }

    @Test
    void rejectsInvalidCredentials() {
        given()
                .contentType("application/json")
                .body("{\"username\":\"student\",\"password\":\"wrong\"}")
                .when().post("/auth/login")
                .then()
                .statusCode(401)
                .body("message", is("Invalid user student or password"));
    }

    @Test
    void rejectsCredentialsContainingQuote() {
        given()
                .contentType("application/json")
                .body("{\"username\":\"stu'dent\",\"password\":\"wrong\"}")
                .when().post("/auth/login")
                .then()
                .statusCode(401)
                .body("message", is("Invalid user stu'dent or password"));
    }

    @Test
    void rejectsMissingUsername() {
        given()
                .contentType("application/json")
                .body("{\"username\":\"\",\"password\":\"Workshop123!\"}")
                .when().post("/auth/login")
                .then()
                .statusCode(400)
                .body("message", is("Username is required"));
    }

    @Test
    void rejectsMissingPassword() {
        given()
                .contentType("application/json")
                .body("{\"username\":\"student\",\"password\":\" \"}")
                .when().post("/auth/login")
                .then()
                .statusCode(400)
                .body("message", is("Password is required"));
    }

    @Test
    void rejectsNullRequest() {
        given()
                .contentType("application/json")
                .body("null")
                .when().post("/auth/login")
                .then()
                .statusCode(400)
                .body("message", is("Request is null"));
    }

    @Test
    void allowsCorsFromConfiguredLocalOrigin() {
        given()
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .when().options("/auth/login")
                .then()
                .header("Access-Control-Allow-Origin", is("http://localhost:4200"));
    }

    @Test
    void blocksUntrustedCorsOriginAfterRestriction() {
        given()
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")
                .when().options("/auth/login")
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void returnsGenericErrorWithoutInternalDetails() {
        try (Response response = new AuthResource()
                .mapUnexpectedError(new IllegalStateException("test-only database failure"))) {
            ErrorResponse body = (ErrorResponse) response.getEntity();
            assertEquals(500, response.getStatus());
            assertEquals("Internal server error", body.message());
        }
    }

    @Test
    void returnsGenericErrorWhenAuthenticationServiceThrows() {
        AuthService failingAuthService = new AuthService() {
            @Override
            public Optional<LoginResult> authenticate(String username, String password) {
                throw new IllegalStateException("test-only authentication failure");
            }
        };
        QuarkusMock.installMockForType(failingAuthService, AuthService.class);

        given()
                .contentType("application/json")
                .body("{\"username\":\"student\",\"password\":\"Workshop123!\"}")
                .when().post("/auth/login")
                .then()
                .statusCode(500)
                .body("message", is("Internal server error"))
                .body("exception", nullValue());
    }

    @Test
    void acceptsAUsernameContainingQuoteAsLiteralAfterSqlParameterization() throws Exception {
        try (Connection connection = DriverManager.getConnection(TEST_DATABASE_URL);
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT OR REPLACE INTO legacy_users(username, password_hash, display_name, role) "
                             + "VALUES (?, ?, ?, ?)")) {
            insert.setString(1, TEST_USERNAME_WITH_QUOTE);
            insert.setString(2, passwordHasher.hash(TEST_PASSWORD));
            insert.setString(3, "Workshop Test User");
            insert.setString(4, "STUDENT");
            insert.executeUpdate();
        }

        try {
            assertTrue(authService.authenticate(TEST_USERNAME_WITH_QUOTE, TEST_PASSWORD).isPresent());
        } finally {
            try (Connection connection = DriverManager.getConnection(TEST_DATABASE_URL);
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM legacy_users WHERE username = ?")) {
                delete.setString(1, TEST_USERNAME_WITH_QUOTE);
                delete.executeUpdate();
            }
        }
    }

    @Test
    void migratedSchemaStoresOnlyPasswordHashes() throws Exception {
        Set<String> columnNames = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(TEST_DATABASE_URL);
             PreparedStatement query = connection.prepareStatement("PRAGMA table_info(legacy_users)");
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                columnNames.add(result.getString("name"));
            }
        }

        assertTrue(columnNames.contains("password_hash"));
        assertFalse(columnNames.contains("password"));
        assertWorkshopPasswordWasMigrated();
    }

    @Test
    void initializesAlreadyMigratedDatabaseIdempotently() {
        initializeTestDatabase();
        assertTrue(authService.authenticate("student", "Workshop123!").isPresent());
    }

    private void initializeTestDatabase() {
        UserRepository repository = new UserRepository();
        repository.passwordHasher = passwordHasher;
        repository.databaseUrl = TEST_DATABASE_URL;
        repository.initialize();
    }

    private void assertWorkshopPasswordWasMigrated() throws Exception {
        try (Connection connection = DriverManager.getConnection(TEST_DATABASE_URL);
             PreparedStatement query = connection.prepareStatement(
                     "SELECT password_hash FROM legacy_users WHERE username = ?")) {
            query.setString(1, "student");
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertNotNull(result.getString("password_hash"));
                assertTrue(passwordHasher.matches("Workshop123!", result.getString("password_hash")));
            }
        }
    }
}
