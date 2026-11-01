package cn.mythicland.framework.database;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SqlDatabaseTest {
    @TempDir
    Path directory;

    @Test
    void resourceMigrationsStayIdempotentAndFailedTransactionsRollBack() throws Exception {
        try (SqlDatabase database = SqliteDatabase.open(Logger.getAnonymousLogger(), directory.resolve("players.db"),
                "org.sqlite.JDBC", getClass().getClassLoader())) {
            MigrationRunner runner = new MigrationRunner("test_schema_history");
            var migration = List.of(new MigrationSpec(1, "players.sql"));
            byte[] sql = "CREATE TABLE players (id INTEGER PRIMARY KEY)".getBytes(StandardCharsets.UTF_8);
            runner.migrate(database, name -> new ByteArrayInputStream(sql), migration);
            runner.migrate(database, name -> new ByteArrayInputStream(sql), migration);
            assertThrows(SQLException.class, () -> database.transaction(connection -> {
                connection.createStatement().executeUpdate("INSERT INTO players VALUES (1)");
                throw new SQLException("abort test transaction");
            }));
            int players = database.query(connection -> {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM players")) {
                    return rows.getInt(1);
                }
            });
            assertEquals(0, players);
        }
    }
}
