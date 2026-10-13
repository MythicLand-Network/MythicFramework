package cn.mythicland.framework.database;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcValuesTest {
    @Test
    void uuidValues_shouldSupportBothDatabaseRepresentationsAndNull() throws Exception {
        UUID uuid = UUID.randomUUID();
        ResultSet rows = mock(ResultSet.class);
        when(rows.getObject(1)).thenReturn(uuid);
        when(rows.getObject(2)).thenReturn(uuid.toString());
        assertEquals(uuid, JdbcValues.readUuid(rows, 1));
        assertEquals(uuid, JdbcValues.readUuid(rows, 2));
        assertEquals(uuid, JdbcValues.readNullableUuid(rows, 2));
        assertNull(JdbcValues.readNullableUuid(rows, 3));

        PreparedStatement statement = mock(PreparedStatement.class);
        JdbcValues.bindUuid(statement, 1, uuid, DatabaseMode.POSTGRESQL);
        JdbcValues.bindUuid(statement, 2, uuid, DatabaseMode.SQLITE);
        JdbcValues.bindUuid(statement, 3, null, DatabaseMode.POSTGRESQL);
        verify(statement).setObject(1, uuid);
        verify(statement).setString(2, uuid.toString());
        verify(statement).setObject(3, null);
    }

    @Test
    void nullableIntegers_shouldPreserveNullAndNumericValues() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(rows.getObject(1)).thenReturn(42L);
        assertEquals(42, JdbcValues.readNullableInt(rows, 1));
        assertNull(JdbcValues.readNullableInt(rows, 2));
        PreparedStatement statement = mock(PreparedStatement.class);
        JdbcValues.setNullableInt(statement, 1, 42);
        JdbcValues.setNullableInt(statement, 2, null);
        verify(statement).setInt(1, 42);
        verify(statement).setObject(2, null);
    }

    @Test
    void instantValues_shouldRetainTimestampAndLegacyTextDecoding() throws Exception {
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        ResultSet rows = mock(ResultSet.class);
        when(rows.getTimestamp(1)).thenReturn(Timestamp.from(instant));
        when(rows.getTimestamp(2)).thenThrow(new SQLException("legacy timestamp"));
        when(rows.getString(2)).thenReturn(Long.toString(instant.toEpochMilli()));
        when(rows.getString(3)).thenReturn(instant.toString());
        when(rows.getString(4)).thenReturn("2026-01-01T08:00:00+08:00");
        when(rows.getString(5)).thenReturn("2026-01-01 00:00:00");
        for (int index = 1; index <= 4; index++) assertEquals(instant, JdbcValues.readInstant(rows, index));
        assertEquals(Timestamp.valueOf("2026-01-01 00:00:00").toInstant(), JdbcValues.readInstant(rows, 5));
        assertNull(JdbcValues.readInstant(rows, 6));
    }
}
