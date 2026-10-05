package cn.mythicland.framework.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class FrameworkDependencyTest {
    @Test
    void framework_shouldNotDependOnThePitAndCoreShouldNotDependOnTheServer() throws Exception {
        Path frameworkRoot = Path.of("..").toAbsolutePath().normalize();
        for (String module : List.of("core", "bukkit", "database", "world")) {
            try (var files = Files.walk(frameworkRoot.resolve(module + "/src/main/java"))) {
                for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(sourceFile);
                    assertFalse(source.contains("cn.mythicland.mythicthepit"), sourceFile.toString());
                    if (!module.equals("core")) continue;
                    source = source.replace("\"org.bukkit.\"", "");
                    assertFalse(source.contains("org.bukkit."), sourceFile.toString());
                    assertFalse(source.contains("net.minecraft."), sourceFile.toString());
                }
            }
        }
    }
}
