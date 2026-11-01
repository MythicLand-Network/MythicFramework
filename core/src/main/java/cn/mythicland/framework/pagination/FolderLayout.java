package cn.mythicland.framework.pagination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Immutable ordered entries with expandable folders; expansion state belongs to each viewer. */
public record FolderLayout(List<Entry> entries) {
    public FolderLayout {
        Set<String> ids = new HashSet<>();
        Set<String> members = new HashSet<>();
        for (Entry entry : entries) {
            if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate library entry: " + entry.id());
            for (String id : entry.members())
                if (!members.add(id)) throw new IllegalArgumentException("Duplicate library member: " + id);
        }
        entries = entries.stream().sorted(Comparator.comparingInt(Entry::order).thenComparing(Entry::id)).toList();
    }

    /** Flattens available members for paging, keeping each expanded folder in its configured position. */
    public List<Row> rows(Set<String> expanded, Predicate<String> available) {
        List<Row> result = new ArrayList<>();
        for (Entry entry : entries) {
            List<String> members = entry.members().stream().filter(available).toList();
            if (members.isEmpty()) continue;
            if (!entry.folder()) result.add(new Row(members.getFirst(), null, 1));
            else if (expanded.contains(entry.id())) {
                for (String id : members) result.add(new Row(id, entry.id(), 1));
            } else result.add(new Row(members.getFirst(), entry.id(), members.size()));
        }
        return List.copyOf(result);
    }

    /** A single entry or an ordered folder. Folder size is independent of inventory stack limits. */
    public record Entry(String id, int order, boolean folder, List<String> members) {
        public Entry {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Library entry ID is required");
            members = List.copyOf(Objects.requireNonNull(members, "members"));
            if (members.isEmpty() || (!folder && members.size() != 1)) throw new IllegalArgumentException("Invalid library member count: " + id);
            if (members.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank library member: " + id);
        }
    }

    /** A rendered entry; folderId is null for entries that cannot be expanded. */
    public record Row(String entryId, String folderId, int count) {
        public int iconAmount() { return Math.min(count, 64); }
    }
}
