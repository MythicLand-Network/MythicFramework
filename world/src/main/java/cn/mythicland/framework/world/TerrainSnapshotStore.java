package cn.mythicland.framework.world;

import org.bukkit.World;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class TerrainSnapshotStore {
    private static final Predicate<String> REGION_FILE_NAME = name -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mca");

    private final Path directory;

    TerrainSnapshotStore(Path directory) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    }

    Path capture(String name, Path regionDirectory) throws IOException {
        this.snapshotPath(name);
        if (!Files.isDirectory(regionDirectory)) {
            throw new IOException("World region directory does not exist: " + regionDirectory);
        }
        Files.createDirectories(this.directory);
        Path capture = Files.createTempDirectory(this.directory, name + "-capture-");
        try {
            int copied = 0;
            for (Path source : regionFiles(regionDirectory)) {
                Files.copy(source, capture.resolve(source.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            }
            if (copied == 0) throw new IOException("World has no region files: " + name);
            return capture;
        } catch (IOException exception) {
            deleteTree(capture, this.directory);
            throw exception;
        }
    }

    Map<String, World.Environment> snapshots() throws IOException {
        if (!Files.isDirectory(this.directory)) return Map.of();
        Map<String, World.Environment> snapshots = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(this.directory)) {
            for (Path archive : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".terrain"))
                    .sorted()
                    .toList()) {
                String fileName = archive.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - ".terrain".length());
                if (!this.snapshotPath(name).equals(archive.toAbsolutePath().normalize())) {
                    throw new IOException("Invalid terrain snapshot name: " + fileName);
                }
                snapshots.put(name, readEnvironment(archive));
            }
        }
        return Map.copyOf(snapshots);
    }

    void write(String name, World.Environment environment, Path capture) throws IOException {
        Path target = this.snapshotPath(name);
        Files.createDirectories(this.directory);
        Path sanitized = Files.createTempDirectory(this.directory, name + "-sanitized-");
        Path temporary = Files.createTempFile(this.directory, name + "-", ".terrain.tmp");
        try {
            List<Path> capturedFiles = regionFiles(capture);
            if (capturedFiles.isEmpty()) throw new IOException("Captured world has no region files: " + name);
            for (Path source : capturedFiles) {
                Path sanitizedFile = sanitized.resolve(source.getFileName());
                TerrainRegionFile.sanitize(source, sanitizedFile);
                TerrainRegionFile.validate(sanitizedFile);
            }
            writeArchive(sanitized, environment, temporary);
            validateArchive(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
            deleteTree(sanitized, this.directory);
            deleteTree(capture, this.directory);
        }
    }

    boolean restore(String name, Path targetRegionDirectory) throws IOException {
        Path archive = this.snapshotPath(name);
        if (!Files.isRegularFile(archive)) return false;
        Path target = targetRegionDirectory.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("World region directory has no parent: " + target);
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + target.getFileName() + ".mythic-staging");
        Path backup = parent.resolve("." + target.getFileName() + ".mythic-backup");
        recoverSwap(target, staging, backup, parent);
        Files.createDirectory(staging);
        try {
            extractArchive(archive, staging);
            List<Path> restoredFiles = regionFiles(staging);
            if (restoredFiles.isEmpty()) throw new IOException("Terrain snapshot has no region files: " + name);
            for (Path restoredFile : restoredFiles) TerrainRegionFile.validate(restoredFile);
            swapRegionDirectory(target, staging, backup, parent);
            return true;
        } catch (IOException exception) {
            deleteTree(staging, parent);
            throw exception;
        }
    }

    private Path snapshotPath(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid managed world name: " + name);
        }
        return this.directory.resolve(name + ".terrain");
    }

    private static List<Path> regionFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> REGION_FILE_NAME.test(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    private static void writeArchive(Path source, World.Environment environment, Path archive) throws IOException {
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setComment(environment.name());
            for (Path region : regionFiles(source)) {
                zip.putNextEntry(new ZipEntry(region.getFileName().toString()));
                Files.copy(region, zip);
                zip.closeEntry();
            }
        }
    }

    private static void validateArchive(Path archive) throws IOException {
        readEnvironment(archive);
        int entries = 0;
        try (InputStream input = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !REGION_FILE_NAME.test(entry.getName())) {
                    throw new IOException("Invalid terrain snapshot entry: " + entry.getName());
                }
                while (zip.read(buffer) != -1) {
                }
                entries++;
            }
        }
        if (entries == 0) throw new IOException("Terrain snapshot archive is empty: " + archive);
    }

    private static World.Environment readEnvironment(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String environment = zip.getComment();
            if (environment == null || environment.isBlank()) {
                throw new IOException("Terrain snapshot has no world environment: " + archive);
            }
            try {
                return World.Environment.valueOf(environment);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Terrain snapshot has an invalid world environment: " + archive, exception);
            }
        }
    }

    private static void extractArchive(Path archive, Path target) throws IOException {
        try (InputStream input = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !REGION_FILE_NAME.test(entry.getName())) {
                    throw new IOException("Invalid terrain snapshot entry: " + entry.getName());
                }
                Path output = target.resolve(entry.getName()).normalize();
                if (!output.getParent().equals(target)) {
                    throw new IOException("Terrain snapshot entry escapes its directory: " + entry.getName());
                }
                Files.copy(zip, output);
            }
        }
    }

    private static void recoverSwap(Path target, Path staging, Path backup, Path parent) throws IOException {
        if (Files.exists(backup)) {
            if (Files.exists(target)) deleteTree(backup, parent);
            else Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
        }
        deleteTree(staging, parent);
    }

    private static void swapRegionDirectory(Path target, Path staging, Path backup, Path parent) throws IOException {
        boolean backedUp = false;
        if (Files.exists(target)) {
            Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
            backedUp = true;
        }
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            if (backedUp && !Files.exists(target)) Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
            throw exception;
        }
        if (backedUp) deleteTree(backup, parent);
    }

    private static void deleteTree(Path target, Path allowedParent) throws IOException {
        if (!Files.exists(target)) return;
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = allowedParent.toAbsolutePath().normalize();
        if (normalized.equals(parent) || !normalized.startsWith(parent)) {
            throw new IOException("Refusing to delete path outside the expected directory: " + normalized);
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(normalized)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) Files.deleteIfExists(path);
    }
}
