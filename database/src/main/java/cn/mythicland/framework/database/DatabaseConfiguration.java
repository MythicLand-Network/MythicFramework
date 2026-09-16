package cn.mythicland.framework.database;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DatabaseConfiguration {
    private final DatabaseMode mode;
    private final String jdbcUrl;
    private final String driverClassName;
    private final Path sqliteFile;
    private final String username;
    private final String password;
    private final int maximumPoolSize;
    private final int minimumIdle;
    private final long connectionTimeoutMillis;
    private final long validationTimeoutMillis;
    private final long idleTimeoutMillis;
    private final long maxLifetimeMillis;
    private final long keepaliveTimeMillis;
    private final String poolName;
    private final List<String> migrationLocations;
    private final Map<String, String> placeholders;
    private final boolean repairOnValidationError;
    private final Map<String, Object> dataSourceProperties;
    private final boolean autoCommit;
    private final String connectionTestQuery;

    private DatabaseConfiguration(Builder builder) {
        this.mode = Objects.requireNonNull(builder.mode, "database mode");
        this.jdbcUrl = requireText(builder.jdbcUrl, "JDBC URL");
        this.driverClassName = requireText(builder.driverClassName, "JDBC driver class name");
        this.sqliteFile = builder.sqliteFile;
        this.username = builder.username;
        this.password = builder.password;
        this.maximumPoolSize = positive(builder.maximumPoolSize, "maximum pool size");
        this.minimumIdle = nonNegative(builder.minimumIdle, "minimum idle");
        if (this.minimumIdle > this.maximumPoolSize) {
            throw new IllegalArgumentException("minimum idle cannot exceed maximum pool size");
        }
        this.connectionTimeoutMillis = positive(builder.connectionTimeoutMillis, "connection timeout");
        this.validationTimeoutMillis = positive(builder.validationTimeoutMillis, "validation timeout");
        this.idleTimeoutMillis = positive(builder.idleTimeoutMillis, "idle timeout");
        this.maxLifetimeMillis = positive(builder.maxLifetimeMillis, "max lifetime");
        this.keepaliveTimeMillis = nonNegative(builder.keepaliveTimeMillis, "keepalive time");
        this.poolName = requireText(builder.poolName, "pool name");
        this.migrationLocations = List.copyOf(builder.migrationLocations);
        this.placeholders = Map.copyOf(builder.placeholders);
        this.repairOnValidationError = builder.repairOnValidationError;
        this.dataSourceProperties = Map.copyOf(builder.dataSourceProperties);
        this.autoCommit = builder.autoCommit;
        this.connectionTestQuery = builder.connectionTestQuery;
        if (this.mode == DatabaseMode.SQLITE && this.sqliteFile == null) {
            throw new IllegalArgumentException("SQLite file is required for SQLite databases");
        }
    }

    public static Builder builder(DatabaseMode mode, String jdbcUrl) {
        return new Builder(mode, jdbcUrl);
    }

    public DatabaseMode mode() {
        return this.mode;
    }

    String jdbcUrl() {
        return this.jdbcUrl;
    }

    String driverClassName() {
        return this.driverClassName;
    }

    Path sqliteFile() {
        return this.sqliteFile;
    }

    String username() {
        return this.username;
    }

    String password() {
        return this.password;
    }

    int maximumPoolSize() {
        return this.maximumPoolSize;
    }

    int minimumIdle() {
        return this.minimumIdle;
    }

    long connectionTimeoutMillis() {
        return this.connectionTimeoutMillis;
    }

    long validationTimeoutMillis() {
        return this.validationTimeoutMillis;
    }

    long idleTimeoutMillis() {
        return this.idleTimeoutMillis;
    }

    long maxLifetimeMillis() {
        return this.maxLifetimeMillis;
    }

    long keepaliveTimeMillis() {
        return this.keepaliveTimeMillis;
    }

    String poolName() {
        return this.poolName;
    }

    List<String> migrationLocations() {
        return this.migrationLocations;
    }

    Map<String, String> placeholders() {
        return this.placeholders;
    }

    boolean repairOnValidationError() {
        return this.repairOnValidationError;
    }

    Map<String, Object> dataSourceProperties() {
        return this.dataSourceProperties;
    }

    boolean autoCommit() {
        return this.autoCommit;
    }

    String connectionTestQuery() {
        return this.connectionTestQuery;
    }

    public static final class Builder {
        private final DatabaseMode mode;
        private final String jdbcUrl;
        private String driverClassName;
        private Path sqliteFile;
        private String username = "";
        private String password = "";
        private int maximumPoolSize = 10;
        private int minimumIdle;
        private long connectionTimeoutMillis = 30_000L;
        private long validationTimeoutMillis = 5_000L;
        private long idleTimeoutMillis = 600_000L;
        private long maxLifetimeMillis = 1_800_000L;
        private long keepaliveTimeMillis;
        private String poolName = "MythicFramework-Database";
        private List<String> migrationLocations = List.of();
        private Map<String, String> placeholders = Map.of();
        private boolean repairOnValidationError;
        private final Map<String, Object> dataSourceProperties = new LinkedHashMap<>();
        private boolean autoCommit = true;
        private String connectionTestQuery;

        private Builder(DatabaseMode mode, String jdbcUrl) {
            this.mode = Objects.requireNonNull(mode, "database mode");
            this.jdbcUrl = jdbcUrl;
            this.driverClassName = mode == DatabaseMode.SQLITE ? "org.sqlite.JDBC" : "org.postgresql.Driver";
            if (mode == DatabaseMode.SQLITE && (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:"))) {
                throw new IllegalArgumentException("SQLite JDBC URL must start with jdbc:sqlite:");
            }
            this.sqliteFile = mode == DatabaseMode.SQLITE
                    ? Path.of(jdbcUrl.substring("jdbc:sqlite:".length()))
                    : null;
        }

        public Builder driverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
            return this;
        }

        public Builder sqliteFile(Path sqliteFile) {
            this.sqliteFile = sqliteFile;
            return this;
        }

        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }

        public Builder connectionTimeoutMillis(long connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            return this;
        }

        public Builder validationTimeoutMillis(long validationTimeoutMillis) {
            this.validationTimeoutMillis = validationTimeoutMillis;
            return this;
        }

        public Builder idleTimeoutMillis(long idleTimeoutMillis) {
            this.idleTimeoutMillis = idleTimeoutMillis;
            return this;
        }

        public Builder maxLifetimeMillis(long maxLifetimeMillis) {
            this.maxLifetimeMillis = maxLifetimeMillis;
            return this;
        }

        public Builder keepaliveTimeMillis(long keepaliveTimeMillis) {
            this.keepaliveTimeMillis = keepaliveTimeMillis;
            return this;
        }

        public Builder poolName(String poolName) {
            this.poolName = poolName;
            return this;
        }

        public Builder migrationLocations(List<String> migrationLocations) {
            this.migrationLocations = Objects.requireNonNull(migrationLocations, "migration locations");
            return this;
        }

        public Builder placeholders(Map<String, String> placeholders) {
            this.placeholders = Objects.requireNonNull(placeholders, "Flyway placeholders");
            return this;
        }

        public Builder repairOnValidationError(boolean repairOnValidationError) {
            this.repairOnValidationError = repairOnValidationError;
            return this;
        }

        public Builder dataSourceProperty(String name, Object value) {
            this.dataSourceProperties.put(requireText(name, "data source property name"), value);
            return this;
        }

        public Builder autoCommit(boolean autoCommit) {
            this.autoCommit = autoCommit;
            return this;
        }

        public Builder connectionTestQuery(String connectionTestQuery) {
            this.connectionTestQuery = requireText(connectionTestQuery, "connection test query");
            return this;
        }

        public DatabaseConfiguration build() {
            return new DatabaseConfiguration(this);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value.trim();
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be greater than zero");
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be greater than zero");
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0L) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }
}
