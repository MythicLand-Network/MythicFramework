package cn.mythicland.framework.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public final class FrameworkRuntimeClassLoading {
    private static final String FRAMEWORK_PACKAGE = "cn.mythicland.framework.";
    private static final String COMPONENT_ROLE = "cn.mythicland.framework.component.ComponentRole";

    private FrameworkRuntimeClassLoading() {
    }

    public static List<String> childFirstPackages(Collection<String> applicationPackages) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        packages.add(FRAMEWORK_PACKAGE);
        packages.addAll(applicationPackages);
        return List.copyOf(packages);
    }

    public static List<String> parentFirstClasses(Collection<String> contractClasses) {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        classes.add(COMPONENT_ROLE);
        classes.addAll(contractClasses);
        return List.copyOf(classes);
    }
}
