package cn.mythicland.framework.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Owns a DataSource and provides checked commit-or-rollback work.
 */
public record DataSourceDatabase(DataSource source, AutoCloseable pool) implements SqlDatabase {
    @Override
    public <T> T query(SqlWork<T> work) throws SQLException {
        try (Connection connection = source.getConnection()) {
            return work.execute(connection);
        }
    }

    @Override
    public <T> T transaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            pool.close();
        } catch (Exception exception) {
            throw new SQLException("Failed to close database pool", exception);
        }
    }
}
