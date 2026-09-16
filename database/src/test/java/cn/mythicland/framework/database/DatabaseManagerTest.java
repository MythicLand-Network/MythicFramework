package cn.mythicland.framework.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startsSqliteDatabaseAndRestoresContextClassLoader() throws SQLException {
        Path databaseFile = this.temporaryDirectory.resolve("nested/framework.db");
        DatabaseConfiguration configuration = DatabaseConfiguration.builder(
                        DatabaseMode.SQLITE,
                        "jdbc:sqlite:" + databaseFile
                )
                .sqliteFile(databaseFile)
                .maximumPoolSize(1)
                .minimumIdle(0)
                .migrationLocations(List.of("classpath:db/sqlite"))
                .build();
        ClassLoader original = Thread.currentThread().getContextClassLoader();

        try (DatabaseManager manager = new DatabaseManager(configuration, Logger.getLogger("test"))) {
            assertNotNull(manager.start());
            assertTrue(databaseFile.toFile().isFile());
            try (var connection = manager.dataSource().getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM framework_database_test")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }

        assertEquals(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void rejectsSecondStart() {
        DatabaseConfiguration configuration = DatabaseConfiguration.builder(
                        DatabaseMode.SQLITE,
                        "jdbc:sqlite:" + this.temporaryDirectory.resolve("database.db")
                )
                .sqliteFile(this.temporaryDirectory.resolve("database.db"))
                .maximumPoolSize(1)
                .minimumIdle(0)
                .build();
        try (DatabaseManager manager = new DatabaseManager(configuration, Logger.getLogger("test"))) {
            manager.start();
            assertThrows(IllegalStateException.class, manager::start);
        }
    }
}
