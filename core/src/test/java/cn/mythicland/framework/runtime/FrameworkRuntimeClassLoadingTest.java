package cn.mythicland.framework.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FrameworkRuntimeClassLoadingTest {
    @Test
    void childFirstPackages_shouldIncludeFrameworkAndApplicationPackages() {
        assertEquals(
                List.of("cn.mythicland.framework.", "cn.mythicland.example."),
                FrameworkRuntimeClassLoading.childFirstPackages(List.of("cn.mythicland.example."))
        );
    }

    @Test
    void parentFirstClasses_shouldIncludeComponentRoleAndPluginContracts() {
        assertEquals(
                List.of(
                        "cn.mythicland.framework.component.ComponentRole",
                        "cn.mythicland.example.ExamplePlugin"
                ),
                FrameworkRuntimeClassLoading.parentFirstClasses(List.of("cn.mythicland.example.ExamplePlugin"))
        );
    }

    @Test
    void bukkitPreset_shouldKeepPlatformAndSharedContractsParentFirst() {
        FrameworkRuntimeClassLoading.ClassLoadingPreset preset = FrameworkRuntimeClassLoading.bukkit(
                List.of("cn.mythicland.example."),
                List.of("cn.mythicland.example.ExamplePlugin"),
                List.of("cn.mythicland.membership.api.")
        );

        assertEquals(List.of("cn.mythicland.framework.", "cn.mythicland.example."), preset.childFirstPackages());
        assertEquals(
                List.of("org.bukkit.", "org.spigotmc.", "net.md_5.bungee.api.", "cn.mythicland.membership.api."),
                preset.parentFirstPackages()
        );
        assertEquals(
                List.of(
                        "cn.mythicland.framework.component.ComponentRole",
                        "cn.mythicland.example.ExamplePlugin"
                ),
                preset.parentFirstClasses()
        );
    }
}
