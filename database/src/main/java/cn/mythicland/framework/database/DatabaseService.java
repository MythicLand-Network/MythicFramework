package cn.mythicland.framework.database;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Factory for databases whose third-party drivers are loaded by Lib.
 */
public final class DatabaseService {

    /**
     * Opens a generic JDBC database using a driver already loaded into the supplied class loader.
     *
     * @param logger            plugin receiving diagnostics
     * @param jdbcUrl           JDBC connection URL
     * @param properties        JDBC connection properties
     * @param driverClassName   driver implementation class
     * @param driverClassLoader class loader containing the driver
     * @return open generic JDBC database
     */
    public JdbcDatabase openJdbc(
            Logger logger,
            String jdbcUrl,
            Properties properties,
            String driverClassName,
            ClassLoader driverClassLoader
    ) {
        return JdbcDatabase.open(
                Objects.requireNonNull(logger, "logger"),
                Objects.requireNonNull(jdbcUrl, "jdbcUrl"),
                Objects.requireNonNull(properties, "properties"),
                Objects.requireNonNull(driverClassName, "driverClassName"),
                Objects.requireNonNull(driverClassLoader, "driverClassLoader")
        );
    }

    /**
     * Opens an SQLite database using a driver already loaded into the supplied class loader.
     *
     * @param logger            plugin receiving diagnostics
     * @param databaseFile      plugin-owned database path
     * @param driverClassName   driver implementation class
     * @param driverClassLoader class loader containing the driver
     * @return open SQLite database
     */
    public SqliteDatabase openSqlite(
            Logger logger,
            Path databaseFile,
            String driverClassName,
            ClassLoader driverClassLoader
    ) {
        Objects.requireNonNull(logger, "logger");
        return SqliteDatabase.open(logger, databaseFile, driverClassName, driverClassLoader);
    }
}
