package cn.mythicland.framework.bukkit.content;

import cn.mythicland.framework.bukkit.storage.AtomicYamlTransaction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * An I/O-thread snapshot of YAML documents merged by section entry. Each section's direct children
 * are indivisible records. Duplicate records fail; equal scalar headers may be shared.
 * Edits retain their source file, and new records use the explicitly selected destination.
 */
public final class ContentDocument {
    private final Map<Path, String> originals = new LinkedHashMap<>();
    private final Map<Path, YamlConfiguration> sources = new LinkedHashMap<>();
    private final Map<String, Path> owners = new LinkedHashMap<>();
    private final YamlConfiguration yaml = new YamlConfiguration();
    private Path destination;

    /** Captures real files and validates duplicate entries before exposing the merged snapshot. */
    public ContentDocument(List<Path> files, Path destination) {
        this.destination = destination;
        try {
            for (Path file : files) {
                YamlConfiguration source = ContentWorkspace.readYaml(file);
                originals.put(file, Files.readString(file));
                sources.put(file, source);
                for (String key : source.getKeys(false)) {
                    ConfigurationSection section = source.getConfigurationSection(key);
                    if (section == null) {
                        Object value = source.get(key);
                        if (yaml.contains(key) && !Objects.equals(yaml.get(key), value))
                            throw new IllegalArgumentException("Conflicting document header: " + key + " in " + file);
                        yaml.set(key, value);
                        owners.putIfAbsent(key, file);
                        continue;
                    }
                    if (yaml.contains(key) && !yaml.isConfigurationSection(key))
                        throw new IllegalArgumentException("Conflicting document section: " + key);
                    if (!yaml.contains(key)) yaml.createSection(key);
                    for (String id : section.getKeys(false)) {
                        String path = key + "." + id;
                        Path previous = owners.putIfAbsent(path, file);
                        if (previous != null) throw new IllegalArgumentException("Duplicate document entry " + path + ": " + previous + " and " + file);
                        copy(source, yaml, path);
                    }
                }
            }
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    /** @return the editable merged snapshot, owned by this document */
    public YamlConfiguration yaml() { return yaml; }

    /** Selects the already validated destination for newly inserted records. */
    public void destination(Path path) { destination = Objects.requireNonNull(path); }

    /** @return the source of an existing entry, or the insertion destination */
    public Path source(String path) { return owners.getOrDefault(path, destination); }

    /** Persists changed sources atomically after optimistic concurrency checks. */
    public void save() {
        try (var transaction = new AtomicYamlTransaction()) {
            write(transaction);
            transaction.commit();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    /** Appends changed files to a caller-owned transaction; the caller must commit or close it. */
    public void write(AtomicYamlTransaction transaction) throws IOException {
        Map<Path, YamlConfiguration> next = new LinkedHashMap<>();
        for (var entry : sources.entrySet()) {
            YamlConfiguration clone = new YamlConfiguration();
            try { clone.loadFromString(entry.getValue().saveToString()); }
            catch (org.bukkit.configuration.InvalidConfigurationException exception) { throw new IOException(exception); }
            next.put(entry.getKey(), clone);
        }
        Set<String> paths = new LinkedHashSet<>(owners.keySet());
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) paths.add(key);
            else for (String id : section.getKeys(false)) paths.add(key + "." + id);
        }
        for (String path : paths) {
            Path owner = source(path);
            YamlConfiguration target = next.computeIfAbsent(owner, ignored -> new YamlConfiguration());
            copy(yaml, target, path);
        }
        for (YamlConfiguration target : next.values()) {
            for (String key : yaml.getKeys(false))
                if (!yaml.isConfigurationSection(key) && !target.contains(key)) target.set(key, yaml.get(key));
        }
        for (var entry : originals.entrySet())
            if (!Files.readString(entry.getKey()).equals(entry.getValue()))
                throw new IOException("Content document changed externally: " + entry.getKey());
        if (!originals.containsKey(destination) && Files.exists(destination))
            throw new IOException("New content destination already exists: " + destination);
        for (var entry : next.entrySet()) {
            YamlConfiguration previous = sources.get(entry.getKey());
            if (previous != null && previous.saveToString().equals(entry.getValue().saveToString())) continue;
            transaction.write(entry.getKey(), entry.getValue());
        }
    }

    private static void copy(ConfigurationSection source, ConfigurationSection target, String path) {
        ConfigurationSection section = source.getConfigurationSection(path);
        target.set(path, null);
        if (section == null) target.set(path, source.get(path));
        else {
            target.createSection(path);
            for (var entry : section.getValues(true).entrySet())
                if (entry.getValue() instanceof ConfigurationSection) target.createSection(path + "." + entry.getKey());
                else target.set(path + "." + entry.getKey(), entry.getValue());
        }
    }
}
