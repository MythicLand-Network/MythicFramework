package cn.mythicland.framework.pagination;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FolderLayoutTest {
    @Test
    void expandsLargeFoldersWithoutLosingMembersOrExceedingIconStackSize() {
        var ids = IntStream.range(0, 90).mapToObj(index -> "mob_" + index).toList();
        var layout = new FolderLayout(List.of(new FolderLayout.Entry("village", 10, true, ids)));
        var collapsed = layout.rows(Set.of(), ignored -> true);
        assertEquals(90, collapsed.getFirst().count());
        assertEquals(64, collapsed.getFirst().iconAmount());
        var expanded = layout.rows(Set.of("village"), ignored -> true);
        assertEquals(ids, expanded.stream().map(FolderLayout.Row::entryId).toList());
        assertEquals(4, PageWindow.of(expanded.size(), 28, 0).pageCount());
        assertEquals("mob_1", layout.rows(Set.of(), id -> !id.equals("mob_0")).getFirst().entryId());
        assertEquals(1, layout.rows(Set.of("village"), ignored -> true).getLast().iconAmount());
    }

    @Test
    void rejectsMembersAssignedToTwoFolders() {
        assertThrows(IllegalArgumentException.class, () -> new FolderLayout(List.of(
                new FolderLayout.Entry("one", 0, true, List.of("shared")),
                new FolderLayout.Entry("two", 1, true, List.of("shared")))));
    }
}
