package cn.mythicland.framework.database;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Synchronous share storage; callers own scheduling and use one instance per table. */
public final class JdbcShareStore<T> {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{6}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Supplier<? extends DataSource> dataSource;
    private final BooleanSupplier postgres;
    private final Function<T, String> encode;
    private final Function<String, T> decode;
    private final String table;
    private final String column;
    private final String description;

    public JdbcShareStore(
            Supplier<? extends DataSource> dataSource,
            BooleanSupplier postgres,
            String table,
            String column,
            String description,
            Function<T, String> encode,
            Function<String, T> decode
    ) {
        if (!table.matches("[a-z][a-z0-9_]*") || !column.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid share table or column name");
        }
        this.dataSource = dataSource;
        this.postgres = postgres;
        this.table = table;
        this.column = column;
        this.description = description;
        this.encode = encode;
        this.decode = decode;
    }

    public String normalizeCode(String code) {
        if (code == null) throw new IllegalArgumentException(description + " share code cannot be null");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(description + " share code must contain six letters or digits");
        }
        return normalized;
    }

    public synchronized String save(UUID owner, T data) {
        try (Connection connection = dataSource.get().getConnection()) {
            connection.setAutoCommit(false);
            try {
                String existing = existingCode(connection, owner, data);
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                String code = availableCode(connection);
                if (count(connection, owner) >= 20) deleteOldest(connection, owner);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + table + " (share_code, owner_uuid, " + column +
                                ", created_at) VALUES (?, ?, ?, ?)"
                )) {
                    statement.setString(1, code);
                    bindOwner(statement, 2, owner);
                    statement.setString(3, encode.apply(data));
                    statement.setLong(4, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                connection.commit();
                return code;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to save " + description.toLowerCase(Locale.ROOT) + " share for " + owner, exception
            );
        }
    }

    public Optional<T> find(String code) {
        try (Connection connection = dataSource.get().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM " + table + " WHERE share_code = ?")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(decode.apply(rows.getString(column)));
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to find " + description.toLowerCase(Locale.ROOT) + " share " + code, exception
            );
        }
    }

    private String existingCode(Connection connection, UUID owner, T data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT share_code, " + column + " FROM " + table +
                        " WHERE owner_uuid = ? ORDER BY created_at ASC, share_code ASC")) {
            bindOwner(statement, 1, owner);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (data.equals(decode.apply(rows.getString(column)))) return rows.getString("share_code");
                }
            }
        }
        return null;
    }

    private String availableCode(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE share_code = ?"
        )) {
            for (int attempt = 0; attempt < 1_000; attempt++) {
                StringBuilder code = new StringBuilder(6);
                for (int index = 0; index < 6; index++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
                statement.setString(1, code.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return code.toString();
                }
            }
        }
        throw new IllegalStateException(
                "Failed to generate a unique " + description.toLowerCase(Locale.ROOT) + " share code"
        );
    }

    private int count(Connection connection, UUID owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE owner_uuid = ?"
        )) {
            bindOwner(statement, 1, owner);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException(
                        "Failed to count " + description.toLowerCase(Locale.ROOT) + " shares"
                );
                return rows.getInt(1);
            }
        }
    }

    private void deleteOldest(Connection connection, UUID owner) throws SQLException {
        String code;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT share_code FROM " + table +
                        " WHERE owner_uuid = ? ORDER BY created_at ASC, share_code ASC LIMIT 1"
        )) {
            bindOwner(statement, 1, owner);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException(description + " share count is inconsistent");
                code = rows.getString("share_code");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE share_code = ?"
        )) {
            statement.setString(1, code);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Failed to delete oldest " + description.toLowerCase(Locale.ROOT) + " share " + code
                );
            }
        }
    }

    private void bindOwner(PreparedStatement statement, int index, UUID owner) throws SQLException {
        JdbcValues.bindUuid(
                statement, index, owner,
                postgres.getAsBoolean() ? DatabaseMode.POSTGRESQL : DatabaseMode.SQLITE
        );
    }
}
