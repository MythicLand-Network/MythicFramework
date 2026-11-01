package cn.mythicland.framework.runtime;

import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Downloads, verifies, and loads immutable runtime library artifacts for dependent plugins.
 *
 * <p>The service deliberately mutates only the requesting plugin's own {@code libs} directory.
 * A dependency is never accepted before its configured SHA-256 digest has been verified.</p>
 */
public final class LibraryService {

    /**
     * Default Maven Central repository root.
     */
    public static final URI DEFAULT_REPOSITORY = URI.create("https://repo1.maven.org/maven2/");

    private static final String LIBRARY_DIRECTORY_NAME = "libs";

    private static URI normalizeRepository(URI repository) {
        Objects.requireNonNull(repository, "repository");
        if (repository.getScheme() == null
                || (!repository.getScheme().equalsIgnoreCase("http")
                && !repository.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Library repository must use HTTP or HTTPS: " + repository);
        }
        if (repository.getQuery() != null || repository.getFragment() != null) {
            throw new IllegalArgumentException("Library repository cannot contain query or fragment: " + repository);
        }
        String value = repository.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static URLClassLoader requireUrlClassLoader(ClassLoader classLoader) {
        if (!(classLoader instanceof URLClassLoader urlClassLoader)) {
            throw new IllegalStateException(
                    "Plugin classloader is not URLClassLoader: " + classLoader.getClass().getName()
            );
        }
        return urlClassLoader;
    }

    private static void validateUniqueFileNames(Collection<LibrarySpec> specifications) {
        Set<String> fileNames = new HashSet<>();
        for (LibrarySpec specification : specifications) {
            if (!fileNames.add(specification.fileName())) {
                throw new IllegalArgumentException(
                        "Duplicate library fileName: " + specification.fileName()
                );
            }
        }
    }

    private static Path resolveLibraryDirectory(Path dataDirectory) {
        Path pluginDataDirectory = dataDirectory.toAbsolutePath().normalize();
        Path libraryDirectory = pluginDataDirectory.resolve(LIBRARY_DIRECTORY_NAME).normalize();
        if (!libraryDirectory.startsWith(pluginDataDirectory)
                || libraryDirectory.equals(pluginDataDirectory)
                || Files.isSymbolicLink(libraryDirectory)) {
            throw new IllegalStateException("Invalid dependency directory: " + libraryDirectory);
        }
        return libraryDirectory;
    }

    private static void ensureLibraryDirectory(Path libraryDirectory) {
        try {
            Files.createDirectories(libraryDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to create dependency directory: " + libraryDirectory,
                    exception
            );
        }
        if (!Files.isDirectory(libraryDirectory) || Files.isSymbolicLink(libraryDirectory)) {
            throw new IllegalStateException("Dependency path is not a real directory: " + libraryDirectory);
        }
    }

    private static Path resolveJar(Path libraryDirectory, LibrarySpec specification) {
        Path jar = libraryDirectory.resolve(specification.fileName()).normalize();
        if (!jar.startsWith(libraryDirectory) || jar.equals(libraryDirectory)) {
            throw new IllegalArgumentException("Invalid dependency file path: " + specification.fileName());
        }
        if (Files.isSymbolicLink(jar)) {
            throw new IllegalStateException("Dependency file cannot be a symbolic link: " + jar);
        }
        return jar;
    }

    private static void resolveJarFile(
            Logger logger,
            LibrarySpec specification,
            URI repository,
            Path jar
    ) {
        if (Files.isRegularFile(jar)) {
            verifyDigest(logger, specification, jar);
            logger.info("Loading dependency jar: " + jar.getFileName());
            return;
        }
        downloadJar(logger, specification, repository, jar);
        logger.info("Loading dependency jar: " + jar.getFileName());
    }

    private static void verifyDigest(Logger logger, LibrarySpec specification, Path jar) {
        String actualDigest = sha256(jar);
        if (specification.sha256().equals(actualDigest)) return;

        logger.severe(
                "Dependency checksum mismatch: " + jar.getFileName()
                        + " expected=" + specification.sha256()
                        + " actual=" + actualDigest
        );
        throw new IllegalStateException(
                "Dependency checksum mismatch for " + specification.coordinate()
                        + ": expected " + specification.sha256() + ", actual " + actualDigest
        );
    }

    private static void downloadJar(Logger logger, LibrarySpec specification, URI repository, Path target) {
        URI source = repository.resolve(specification.repositoryPath());
        logger.info("Downloading dependency jar: " + target.getFileName());
        try {
            RuntimeArtifacts.download(source, target, specification.sha256(), "DreamRPG-LibraryLoader");
        } catch (IOException exception) {
            logger.severe("Failed to download dependency jar: " + specification.coordinate() + " from " + source);
            throw new IllegalStateException(
                    "Failed to download dependency jar: " + specification.coordinate() + " from " + source,
                    exception
            );
        }
    }

    static String sha256(Path file) {
        try {
            return RuntimeArtifacts.sha256(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to hash dependency file: " + file, exception);
        }
    }

    private static ClassLoader createDependencyClassLoader(
            URLClassLoader pluginClassLoader,
            Collection<Path> jars
    ) {
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) {
            try {
                urls.add(jar.toUri().toURL());
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to resolve dependency URL: " + jar, exception);
            }
        }

        /*
         * Paper 1.12.2 inherits URLClassLoader.addURL(URL) without exposing a plugin-level bridge.
         * On JDK 21, opening java.net reflectively is rejected by JPMS. A child loader keeps the
         * verified JARs isolated while retaining the requesting Paper loader as their parent.
         */
        return new URLClassLoader(
                urls.toArray(URL[]::new),
                pluginClassLoader
        );
    }

    /**
     * Loads the requested libraries into a class loader owned by the requesting plugin.
     *
     * <p>This method is intentionally synchronous and should be called during plugin startup,
     * before classes that use the external library are initialized. A verified cache is used
     * without network access. Download or class-loader failures are fatal to the caller.</p>
     *
     * <p>The download path is blocking. Callers must not invoke it from a request or gameplay
     * callback that cannot tolerate network latency.</p>
     *
     * @param dataDirectory  owner of the library cache
     * @param parent         parent class loader
     * @param logger         logger receiving dependency diagnostics
     * @param specifications immutable library specifications
     * @param repository     one repository root used to resolve every specification
     * @return verified paths and a dependency class loader whose parent is the plugin loader
     * @throws IllegalStateException if a library cannot be verified, downloaded, or loaded
     */
    public LibraryLoadResult load(
            Path dataDirectory,
            ClassLoader parent,
            Logger logger,
            Collection<LibrarySpec> specifications,
            URI repository
    ) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(specifications, "specifications");
        URI normalizedRepository = normalizeRepository(repository);
        URLClassLoader pluginClassLoader = requireUrlClassLoader(parent);
        List<LibrarySpec> requestedLibraries = List.copyOf(specifications);
        validateUniqueFileNames(requestedLibraries);

        Path libraryDirectory = resolveLibraryDirectory(dataDirectory);
        ensureLibraryDirectory(libraryDirectory);
        List<Path> resolvedJars = new ArrayList<>();
        for (LibrarySpec specification : requestedLibraries) {
            Path jar = resolveJar(libraryDirectory, specification);
            resolveJarFile(logger, specification, normalizedRepository, jar);
            resolvedJars.add(jar);
        }

        ClassLoader dependencyClassLoader = createDependencyClassLoader(pluginClassLoader, resolvedJars);
        return new LibraryLoadResult(resolvedJars, dependencyClassLoader);
    }
}
