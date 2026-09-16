package cn.mythicland.framework.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;

public final class DatabaseManager implements AutoCloseable {
    private final DatabaseConfiguration configuration;
    private final Logger logger;
    private HikariDataSource dataSource;

    public DatabaseManager(DatabaseConfiguration configuration, Logger logger) {
        this.configuration = Objects.requireNonNull(configuration, "database configuration");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized DataSource start() {
        if (this.dataSource != null) throw new IllegalStateException("Database manager is already started");
        this.prepareSqliteDirectory();
        HikariDataSource candidate = this.createDataSource();
        try {
            this.migrate(candidate);
            this.dataSource = candidate;
            return candidate;
        } catch (RuntimeException | Error exception) {
            try {
                candidate.close();
            } catch (RuntimeException | Error closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            if (exception instanceof Error error) throw error;
            throw new IllegalStateException("Failed to initialize " + this.configuration.poolName() + " database", exception);
        }
    }

    public synchronized DataSource dataSource() {
        if (this.dataSource == null) throw new IllegalStateException("Database manager is not started");
        return this.dataSource;
    }

    public DatabaseMode mode() {
        return this.configuration.mode();
    }

    @Override
    public synchronized void close() {
        if (this.dataSource == null) return;
        this.dataSource.close();
        this.dataSource = null;
        this.logger.info(this.configuration.poolName() + " database pool closed.");
    }

    private HikariDataSource createDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(this.configuration.jdbcUrl());
        hikariConfig.setDriverClassName(this.configuration.driverClassName());
        hikariConfig.setUsername(this.configuration.username());
        hikariConfig.setPassword(this.configuration.password());
        hikariConfig.setMaximumPoolSize(this.configuration.maximumPoolSize());
        hikariConfig.setMinimumIdle(this.configuration.minimumIdle());
        hikariConfig.setConnectionTimeout(this.configuration.connectionTimeoutMillis());
        hikariConfig.setValidationTimeout(this.configuration.validationTimeoutMillis());
        hikariConfig.setIdleTimeout(this.configuration.idleTimeoutMillis());
        hikariConfig.setMaxLifetime(this.configuration.maxLifetimeMillis());
        hikariConfig.setKeepaliveTime(this.configuration.keepaliveTimeMillis());
        hikariConfig.setPoolName(this.configuration.poolName());
        if (this.configuration.mode() == DatabaseMode.SQLITE) {
            hikariConfig.setConnectionInitSql("PRAGMA foreign_keys = ON");
        }
        for (var property : this.configuration.dataSourceProperties().entrySet()) {
            hikariConfig.addDataSourceProperty(property.getKey(), property.getValue());
        }
        hikariConfig.setAutoCommit(this.configuration.autoCommit());
        if (this.configuration.connectionTestQuery() != null) {
            hikariConfig.setConnectionTestQuery(this.configuration.connectionTestQuery());
        }
        return new HikariDataSource(hikariConfig);
    }

    private void migrate(HikariDataSource candidate) {
        if (this.configuration.migrationLocations().isEmpty()) return;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(this.getClass().getClassLoader());
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(candidate)
                    .locations(this.configuration.migrationLocations().toArray(String[]::new))
                    .placeholders(this.configuration.placeholders())
                    .load();
            try {
                flyway.migrate();
            } catch (FlywayValidateException exception) {
                if (!this.configuration.repairOnValidationError()) throw exception;
                this.logger.warning("Flyway validation failed, repairing schema history and retrying migration: "
                        + exception.getMessage());
                flyway.repair();
                flyway.migrate();
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private void prepareSqliteDirectory() {
        if (this.configuration.mode() != DatabaseMode.SQLITE) return;
        Path file = this.configuration.sqliteFile().toAbsolutePath().normalize();
        Path parent = file.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create SQLite database directory: " + parent, exception);
        }
    }
}
