package cn.mythicland.framework.bukkit.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContentWorkspaceTest {
    @TempDir Path directory;

    @Test
    void discoversNestedSourcesWithoutWritingRuntimeCopies() throws Exception {
        Path source = write("items/village/food.yml", "chicken: {Id: 366}\n");
        var content = new ContentWorkspace(directory);
        assertEquals(List.of(source), content.definitions(List.of("items")).get("items"));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
        Files.delete(source);
        assertTrue(content.definitions(List.of("items")).get("items").isEmpty());
    }

    @Test
    void rejectsDuplicateIdsAndDuplicateYamlKeys() throws Exception {
        write("items/first.yml", "chicken: {}\n");
        Path duplicate = write("items/nested/second.yml", "chicken: {}\n");
        var content = new ContentWorkspace(directory);
        assertThrows(IllegalArgumentException.class, () -> content.definitions(List.of("items")));
        Files.delete(duplicate);
        write("items/first.yml", "chicken:\n  Id: 366\n  Id: 1\n");
        assertThrows(IllegalArgumentException.class, () -> content.definitions(List.of("items")));
    }

    @Test
    void rejectsTraversalAndObsoletePublishingManifest() throws Exception {
        var content = new ContentWorkspace(directory);
        assertThrows(IllegalArgumentException.class, () -> content.resolve("example", "../outside.yml"));
        write("manifest.yml", "version: 1\nfiles: {}\npublish: {}\n");
        assertThrows(IllegalArgumentException.class, content::reloadManifest);
        assertEquals(directory.resolve("default.yml"), content.resolve("example", "default.yml"));
    }

    private Path write(String relative, String text) throws Exception {
        Path target = directory.resolve(relative);
        Files.createDirectories(target.getParent());
        return Files.writeString(target, text);
    }
}
