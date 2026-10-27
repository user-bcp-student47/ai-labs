package com.bcp.workshop.auth;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class UserRepository {
    private static final String DEFAULT_STUDENT_USERNAME = "student";
    private static final String DEFAULT_STUDENT_PASSWORD = "Workshop123!";
    private static final String DEFAULT_STUDENT_DISPLAY_NAME = "Workshop Student";
    private static final String DEFAULT_TRAINER_USERNAME = "trainer";
    private static final String DEFAULT_TRAINER_PASSWORD = "Trainer123!";
    private static final String DEFAULT_TRAINER_DISPLAY_NAME = "Workshop Trainer";
    private static final String STUDENT_ROLE = "STUDENT";
    private static final String TRAINER_ROLE = "TRAINER";

    @Inject
    PasswordHasher passwordHasher;

    @ConfigProperty(name = "auth.db.url")
    String databaseUrl;

    @ConfigProperty(name = "auth.bootstrap.trainer.enabled", defaultValue = "true")
    boolean trainerBootstrapEnabled;

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(Path.of("data"));
            try (Connection connection = DriverManager.getConnection(databaseUrl);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS legacy_users ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "username TEXT UNIQUE, "
                        + "password_hash TEXT NOT NULL, "
                        + "display_name TEXT NOT NULL, "
                        + "role TEXT NOT NULL)");
                migratePasswordStorage(connection, statement);
                seedUsers(connection);
            }
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("Unable to initialize authentication database", error);
        }
    }

    public Optional<UserRecord> findByUsername(String username) {
        if (isDisabledTrainerBootstrapAccount(username)) {
            return Optional.empty();
        }

        String sql = "SELECT id, username, password_hash, display_name, role "
                + "FROM legacy_users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(databaseUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRecord(
                        result.getLong("id"),
                        result.getString("username"),
                        result.getString("password_hash"),
                        result.getString("display_name"),
                        result.getString("role")
                ));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to query authentication database", error);
        }
    }

    private void migratePasswordStorage(Connection connection, Statement statement) throws SQLException {
        boolean hasPlaintextPassword = hasColumn(connection, "password");
        boolean hasPasswordHash = hasColumn(connection, "password_hash");

        if (hasPlaintextPassword && !hasPasswordHash) {
            statement.executeUpdate("ALTER TABLE legacy_users ADD COLUMN password_hash TEXT");
            hasPasswordHash = true;
        }
        if (!hasPasswordHash) {
            throw new IllegalStateException("Authentication database has no password storage column");
        }

        if (hasPlaintextPassword) {
            migratePlaintextPasswords(connection);
            statement.executeUpdate("ALTER TABLE legacy_users DROP COLUMN password");
        }
    }

    private static boolean hasColumn(Connection connection, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(legacy_users)")) {
            while (columns.next()) {
                if (columnName.equals(columns.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void migratePlaintextPasswords(Connection connection) throws SQLException {
        List<PlaintextPassword> passwordsToMigrate = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet users = statement.executeQuery(
                     "SELECT id, password, password_hash FROM legacy_users")) {
            while (users.next()) {
                String existingHash = users.getString("password_hash");
                if (existingHash == null || existingHash.isBlank()) {
                    passwordsToMigrate.add(new PlaintextPassword(
                            users.getLong("id"),
                            users.getString("password")
                    ));
                }
            }
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE legacy_users SET password_hash = ? WHERE id = ?")) {
            for (PlaintextPassword password : passwordsToMigrate) {
                if (password.value() == null) {
                    throw new IllegalStateException("Authentication database contains an empty password");
                }
                update.setString(1, passwordHasher.hash(password.value()));
                update.setLong(2, password.id());
                update.executeUpdate();
            }
        }
    }

    private void seedUsers(Connection connection) throws SQLException {
        seedUser(connection, DEFAULT_STUDENT_USERNAME, DEFAULT_STUDENT_PASSWORD,
                DEFAULT_STUDENT_DISPLAY_NAME, STUDENT_ROLE);
        if (trainerBootstrapEnabled) {
            seedUser(connection, DEFAULT_TRAINER_USERNAME, DEFAULT_TRAINER_PASSWORD,
                    DEFAULT_TRAINER_DISPLAY_NAME, TRAINER_ROLE);
        }
    }

    private boolean isDisabledTrainerBootstrapAccount(String username) {
        return !trainerBootstrapEnabled && DEFAULT_TRAINER_USERNAME.equals(username);
    }

    private void seedUser(Connection connection, String username, String password, String displayName, String role)
            throws SQLException {
        String sql = "INSERT OR IGNORE INTO legacy_users(username, password_hash, display_name, role) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, passwordHasher.hash(password));
            statement.setString(3, displayName);
            statement.setString(4, role);
            statement.executeUpdate();
        }
    }

    private record PlaintextPassword(long id, String value) {}
}
