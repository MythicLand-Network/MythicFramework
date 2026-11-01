package cn.mythicland.framework.bukkit.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContentCollectionsTest {
    @TempDir Path root;

    @Test
    void disabledSourcesAreIgnoredAndActiveDuplicatesAreRejected() throws Exception {
        write("dungeons/village/dungeon.yml", "name: Village\nenabled: true\n");
        write("dungeons/retired/dungeon.yml", "name: Retired\nenabled: false\n");
        write("common/items/coin.yml", "coin: {Id: STONE}\n");
        write("dungeons/village/items/weapon.yml", "weapon: {Id: STONE}\n");
        write("dungeons/retired/items/broken.yml", "broken: [\n");
        var workspace = new ContentWorkspace(root, "dungeons", "dungeon.yml");
        assertEquals(2, workspace.definitions(List.of("items")).get("items").size());
        assertThrows(IllegalArgumentException.class, () -> workspace.destination("retired", "items/new.yml"));
        assertThrows(IllegalArgumentException.class, () -> workspace.destination("../village", "items/new.yml"));
        write("dungeons/village/items/weapon.yml", "coin: {Id: STONE}\n");
        assertThrows(IllegalArgumentException.class, () -> workspace.definitions(List.of("items")));
    }

    @Test
    void editsAndDeletesKeepTheirSourceAndNewRecordsUseSelectedCollection() throws Exception {
        write("dungeons/village/dungeon.yml", "name: Village\nenabled: true\n");
        write("common/exchanges.yml", "version: 1\nexchanges:\n  shared: {amount: 1}\n");
        write("dungeons/village/exchanges.yml", "version: 1\nexchanges:\n  local: {amount: 2}\n");
        var workspace = new ContentWorkspace(root, "dungeons", "dungeon.yml");
        var document = workspace.document("exchanges.yml");
        document.destination(workspace.destination("village", "exchanges.yml"));
        document.yaml().set("exchanges.local.amount", 3);
        document.yaml().set("exchanges.shared", null);
        document.yaml().createSection("exchanges.new.offers");
        document.save();
        var local = ContentWorkspace.readYaml(root.resolve("dungeons/village/exchanges.yml"));
        assertEquals(3, local.getInt("exchanges.local.amount"));
        assertTrue(local.isConfigurationSection("exchanges.new.offers"));
        assertFalse(ContentWorkspace.readYaml(root.resolve("common/exchanges.yml")).contains("exchanges.shared"));
        assertFalse(Files.exists(root.resolve("exchanges.yml")));
        assertEquals(2, workspace.document("exchanges.yml").yaml().getConfigurationSection("exchanges").getKeys(false).size());
    }

    @Test
    void staleDocumentFailsBeforeAnySourceIsWritten() throws Exception {
        write("one.yml", "entries:\n  first: {value: 1}\n");
        write("two.yml", "entries:\n  second: {value: 2}\n");
        var document = new ContentDocument(List.of(root.resolve("one.yml"), root.resolve("two.yml")), root.resolve("new.yml"));
        document.yaml().set("entries.first.value", 9);
        write("two.yml", "entries:\n  second: {value: 3}\n");
        assertThrows(java.io.UncheckedIOException.class, document::save);
        assertEquals(1, ContentWorkspace.readYaml(root.resolve("one.yml")).getInt("entries.first.value"));
    }

    private void write(String name, String yaml) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
    }
}
