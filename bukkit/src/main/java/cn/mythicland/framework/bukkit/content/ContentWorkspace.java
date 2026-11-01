package cn.mythicland.framework.bukkit.content;

import cn.mythicland.framework.bukkit.storage.AtomicFileStore;
import cn.mythicland.framework.path.SafePathResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Resolves a single editable content tree. File I/O belongs on the owner's I/O executor. */
public final class ContentWorkspace {
    private final SafePathResolver content;
    private final String collectionDirectory;
    private final String descriptor;
    private volatile Map<String, String> files = Map.of();

    /**
     * Opens an editable directory without migrating or publishing any data.
     * @param directory content directory
     * @throws UncheckedIOException if its optional manifest is unreadable
     */
    public ContentWorkspace(Path directory) {
        this(directory, "", "");
    }

    /** Opens a workspace with opt-in collections described by name, order and enabled fields. */
    public ContentWorkspace(Path directory, String collectionDirectory, String descriptor) {
        content = new SafePathResolver(directory);
        this.collectionDirectory = collectionDirectory;
        this.descriptor = descriptor;
        reloadManifest();
    }

    /** @return the absolute editable content directory */
    public Path root() {
        return content.root();
    }

    /**
     * Opens the server workspace for a plugin-owned store created outside a component graph.
     *
     * @param plugin plugin with a standard server/plugins/name data directory
     * @param directory sibling content directory name
     * @return content workspace
     */
    public static ContentWorkspace forPlugin(JavaPlugin plugin, String directory) {
        SafePathResolver plugins = new SafePathResolver(plugin.getDataFolder().toPath().toAbsolutePath().getParent());
        return new ContentWorkspace(plugins.resolveSingleSegment(directory));
    }

    /**
     * Resolves a logical document through the manifest, using a content-relative default.
     *
     * @param logicalName stable plugin/resource identifier
     * @param defaultPath content-relative path used when no mapping is declared
     * @return validated absolute path, which may not exist yet
     * @throws IllegalArgumentException if a path is unsafe
     */
    public Path resolve(String logicalName, String defaultPath) {
        return content.resolveRelative(files.getOrDefault(logicalName, defaultPath));
    }

    /**
     * Initializes an absent editable document from a bundled resource.
     *
     * @param plugin resource owner
     * @param resource bundled resource and logical document suffix
     * @param defaultPath content-relative default destination
     * @return the existing or initialized document
     * @throws UncheckedIOException if initialization fails
     */
    public Path resource(JavaPlugin plugin, String resource, String defaultPath) {
        Path target = resolve(plugin.getName() + "/" + resource, defaultPath);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target;
        if (files.containsKey(plugin.getName() + "/" + resource)) throw new IllegalStateException("Declared content document is missing: " + target);
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) throw new IOException("Missing bundled resource: " + resource);
            AtomicFileStore.writeUtf8(target, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Replaces manifest settings only after all declared paths validate.
     *
     * @throws UncheckedIOException if the manifest is malformed or unreadable
     */
    public synchronized void reloadManifest() {
        Path manifest = content.resolveRelative("manifest.yml");
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            if (!files.isEmpty()) throw new IllegalStateException("Content manifest is missing: " + manifest);
            return;
        }
        YamlConfiguration yaml = readYaml(manifest);
        if (yaml.getInt("version") != 1 || yaml.contains("publish"))
            throw new IllegalArgumentException("Unsupported content manifest: " + manifest);
        Map<String, String> nextFiles = stringMap(yaml, "files");
        nextFiles.values().forEach(content::resolveRelative);
        if (!files.isEmpty() && !files.equals(nextFiles)) throw new IllegalStateException("Content document mappings require a server restart");
        files = Map.copyOf(nextFiles);
    }

    /**
     * Lists and strictly validates definitions directly inside each resource directory.
     * @param kinds resource directory names
     * @return immutable kind-to-source-files map
     * @throws IllegalArgumentException if paths, YAML or IDs conflict
     */
    public synchronized Map<String, List<Path>> definitions(Collection<String> kinds) {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        List<Path> roots = sourceRoots();
        for (String kind : kinds) {
            content.resolveSingleSegment(kind);
            List<Path> sources = new ArrayList<>();
            for (Path base : roots) {
                Path resource = base.resolve(kind);
                if (Files.exists(resource, LinkOption.NOFOLLOW_LINKS)) sources.addAll(yamlFiles(resource));
            }
            Map<String, Path> ids = new HashMap<>();
            for (Path source : sources) {
                for (String id : readYaml(source).getKeys(false)) {
                    Path previous = ids.putIfAbsent(id.toLowerCase(Locale.ROOT), source);
                    if (previous != null) throw new IllegalArgumentException("Duplicate " + kind + " ID " + id + ": " + previous + " and " + source);
                }
            }
            result.put(kind, sources);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Lists real YAML files, rejecting links anywhere in the tree.
     *
     * @param directory managed directory
     * @return sorted files
     * @throws UncheckedIOException if a file tree is unsafe or unreadable
     */
    public static List<Path> yamlFiles(Path directory) {
        SafePathResolver paths = new SafePathResolver(directory);
        try {
            paths.requireRealDirectory(directory);
            try (Stream<Path> entries = Files.walk(directory)) {
                List<Path> result = new ArrayList<>();
                for (Path path : entries.sorted().toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Content path cannot be a symbolic link: " + path);
                    String name = path.getFileName().toString();
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && (name.endsWith(".yml") || name.endsWith(".yaml"))) result.add(path);
                }
                return List.copyOf(result);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Parses YAML strictly, including duplicate mapping keys at any nesting depth.
     *
     * @param path regular UTF-8 YAML file
     * @return parsed configuration
     * @throws UncheckedIOException if the file is missing, unsafe, or malformed
     */
    public static YamlConfiguration readYaml(Path path) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Content is not a regular file: " + path);
            String text = Files.readString(path);
            Node root = new Yaml().compose(new StringReader(text));
            validateKeys(root, path, Collections.newSetFromMap(new IdentityHashMap<>()));
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new UncheckedIOException(new IOException("Cannot read content YAML: " + path, exception));
        } catch (org.yaml.snakeyaml.error.YAMLException exception) {
            throw new IllegalArgumentException("Invalid YAML in " + path + ": " + exception.getMessage(), exception);
        }
    }

    /** Immutable editable collection descriptor. Disabled collections never contribute source files. */
    public record CollectionInfo(String id, String name, int order, boolean enabled) { }

    /** @return ordered descriptors; malformed metadata fails even for disabled collections */
    public List<CollectionInfo> collections() {
        if (collectionDirectory.isEmpty()) return List.of();
        Path directory = content.resolveSingleSegment(collectionDirectory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> entries = Files.list(directory)) {
            List<CollectionInfo> result = new ArrayList<>();
            for (Path entry : entries.sorted().toList()) {
                if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("Invalid collection directory: " + entry);
                String id = entry.getFileName().toString();
                if (!id.matches("[a-z][a-z0-9_-]*")) throw new IllegalArgumentException("Invalid collection ID: " + id);
                YamlConfiguration yaml = readYaml(entry.resolve(descriptor));
                if (!yaml.isBoolean("enabled") || !yaml.isString("name") || yaml.getString("name").isBlank()
                        || (yaml.contains("order") && !yaml.isInt("order")))
                    throw new IllegalArgumentException("Invalid collection descriptor: " + entry);
                for (String key : yaml.getKeys(false)) if (!Set.of("name", "enabled", "order").contains(key))
                    throw new IllegalArgumentException("Unknown collection setting: " + entry + " / " + key);
                result.add(new CollectionInfo(id, yaml.getString("name"), yaml.getInt("order"), yaml.getBoolean("enabled")));
            }
            return result.stream().sorted(Comparator.comparingInt(CollectionInfo::order).thenComparing(CollectionInfo::id)).toList();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    /** @return active source roots, including workspace-wide and shared definitions */
    public List<Path> sourceRoots() {
        List<Path> result = new ArrayList<>();
        result.add(root());
        if (!collectionDirectory.isEmpty()) {
            result.add(content.resolveSingleSegment("common"));
            for (CollectionInfo info : collections()) if (info.enabled())
                result.add(content.resolveRelative(collectionDirectory + "/" + info.id()));
        }
        return List.copyOf(result);
    }

    /** Resolves a new resource destination, rejecting unknown or disabled collection IDs. */
    public Path destination(String collection, String relative) {
        if (collectionDirectory.isEmpty()) return content.resolveRelative(relative);
        if (collection.equals("common")) return content.resolveRelative("common/" + relative);
        if (collections().stream().noneMatch(info -> info.id().equals(collection) && info.enabled()))
            throw new IllegalArgumentException("Unknown or disabled content collection: " + collection);
        return content.resolveRelative(collectionDirectory + "/" + collection + "/" + relative);
    }

    /** @return existing documents in active roots; the name must be a single safe segment */
    public List<Path> documents(String name) {
        content.resolveSingleSegment(name);
        return sourceRoots().stream().map(base -> base.resolve(name))
                .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS)).toList();
    }

    /** Opens an aggregate document while retaining the owner of every section entry. */
    public ContentDocument document(String name) {
        return new ContentDocument(documents(name), destination("common", name));
    }

    /** @return the resource kind relative to its active source root */
    public String kind(Path source) {
        return sourceRoots().stream().filter(base -> !base.equals(root()) && source.startsWith(base))
                .findFirst().orElse(root()).relativize(source).getName(0).toString();
    }

    private static void validateKeys(Node node, Path path, Set<Node> visited) {
        if (node == null || !visited.add(node)) return;
        if (node instanceof MappingNode mapping) {
            Set<String> keys = new HashSet<>();
            for (var tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key) || !keys.add(key.getValue()))
                    throw new IllegalArgumentException("Duplicate or non-scalar YAML key in " + path);
                validateKeys(tuple.getValueNode(), path, visited);
            }
        } else if (node instanceof SequenceNode sequence) {
            sequence.getValue().forEach(child -> validateKeys(child, path, visited));
        }
    }

    private static Map<String, String> stringMap(YamlConfiguration yaml, String key) {
        ConfigurationSection section = yaml.getConfigurationSection(key);
        if (section == null) throw new IllegalArgumentException("Content manifest requires " + key);
        Map<String, String> result = new LinkedHashMap<>();
        section.getValues(true).forEach((name, value) -> {
            if (value instanceof ConfigurationSection) return;
            if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid content manifest entry: " + name);
            result.put(name, text);
        });
        return result;
    }
}
