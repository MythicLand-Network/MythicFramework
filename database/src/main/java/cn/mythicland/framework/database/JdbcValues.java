package cn.mythicland.framework.database;

import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public final class JdbcValues {
    private JdbcValues() {
    }

    public static UUID readUuid(ResultSet rows, int index) throws SQLException {
        Object value = rows.getObject(index);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    public static Instant readInstant(ResultSet rows, int index) throws SQLException {
        try {
            Timestamp timestamp = rows.getTimestamp(index);
            if (timestamp != null) return timestamp.toInstant();
        } catch (SQLException ignored) {
        }
        String raw = rows.getString(index);
        if (raw == null) return null;
        if (!raw.isEmpty() && raw.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochMilli(Long.parseLong(raw));
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                String normalized = raw.replace('T', ' ');
                int offset = normalized.indexOf('+', 19);
                if (offset < 0) offset = normalized.indexOf('-', 19);
                if (offset >= 0) normalized = normalized.substring(0, offset);
                return Timestamp.valueOf(normalized).toInstant();
            }
        }
    }

    public static UUID readNullableUuid(ResultSet rows, int index) throws SQLException {
        Object value = rows.getObject(index);
        return value == null ? null : value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    public static Integer readNullableInt(ResultSet rows, int index) throws SQLException {
        Number value = (Number) rows.getObject(index);
        return value == null ? null : value.intValue();
    }

    public static void bindUuid(PreparedStatement statement, int index, UUID value, DatabaseMode mode) throws SQLException {
        if (value == null) statement.setObject(index, null);
        else if (mode == DatabaseMode.POSTGRESQL) statement.setObject(index, value);
        else statement.setString(index, value.toString());
    }

    public static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setObject(index, null);
        else statement.setInt(index, value);
    }

}
