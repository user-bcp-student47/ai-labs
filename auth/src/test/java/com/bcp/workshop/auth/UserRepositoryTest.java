package com.bcp.workshop.auth;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRepositoryTest {
    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void doesNotSeedTrainerWhenBootstrapIsDisabled() throws Exception {
        loadSqliteDriver();
        Path databaseFile = createTemporaryDatabaseFile();
        try {
            UserRepository repository = repositoryFor(databaseFile, false);

            repository.initialize();

            assertTrue(repository.findByUsername("student").isPresent());
            assertFalse(repository.findByUsername("trainer").isPresent());
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void blocksExistingTrainerAccountWhenBootstrapIsDisabled() throws Exception {
        loadSqliteDriver();
        Path databaseFile = createTemporaryDatabaseFile();
        try {
            insertExistingTrainer(databaseFile);
            UserRepository repository = repositoryFor(databaseFile, false);

            repository.initialize();

            assertFalse(repository.findByUsername("trainer").isPresent());
            assertTrue(repository.findByUsername("student").isPresent());
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void productionProfileConfigurationDisablesTrainerBootstrap() throws Exception {
        loadSqliteDriver();
        Path databaseFile = createTemporaryDatabaseFile();
        try {
            boolean trainerBootstrapEnabled = productionTrainerBootstrapEnabled();
            UserRepository repository = repositoryFor(databaseFile, trainerBootstrapEnabled);

            repository.initialize();

            assertFalse(trainerBootstrapEnabled);
            assertFalse(repository.findByUsername("trainer").isPresent());
            assertTrue(repository.findByUsername("student").isPresent());
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    private static Path createTemporaryDatabaseFile() throws Exception {
        return Files.createTempFile(Path.of("target"), "auth-bootstrap-", ".db");
    }

    private static void loadSqliteDriver() throws ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
    }

    private static boolean productionTrainerBootstrapEnabled() throws Exception {
        Properties properties = new Properties();
        try (InputStream applicationProperties =
                     UserRepositoryTest.class.getResourceAsStream("/application.properties")) {
            if (applicationProperties == null) {
                throw new IllegalStateException("Authentication application.properties was not found");
            }
            properties.load(applicationProperties);
        }

        SmallRyeConfig productionConfig = new SmallRyeConfigBuilder()
                .withProfile("prod")
                .withSources(new PropertiesConfigSource(properties, "application.properties", 100))
                .build();
        return productionConfig.getValue("auth.bootstrap.trainer.enabled", Boolean.class);
    }

    private UserRepository repositoryFor(Path databaseFile, boolean trainerBootstrapEnabled) {
        UserRepository repository = new UserRepository();
        repository.passwordHasher = passwordHasher;
        repository.databaseUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        repository.trainerBootstrapEnabled = trainerBootstrapEnabled;
        return repository;
    }

    private void insertExistingTrainer(Path databaseFile) throws Exception {
        String databaseUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(databaseUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE legacy_users ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "username TEXT UNIQUE, "
                    + "password_hash TEXT NOT NULL, "
                    + "display_name TEXT NOT NULL, "
                    + "role TEXT NOT NULL)");
        }

        try (Connection connection = DriverManager.getConnection(databaseUrl);
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO legacy_users(username, password_hash, display_name, role) "
                             + "VALUES (?, ?, ?, ?)")) {
            insert.setString(1, "trainer");
            insert.setString(2, passwordHasher.hash("test-only-password"));
            insert.setString(3, "Test Trainer");
            insert.setString(4, "TRAINER");
            insert.executeUpdate();
        }
    }
}
