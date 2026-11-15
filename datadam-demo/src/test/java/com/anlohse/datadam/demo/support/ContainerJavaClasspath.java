package com.anlohse.datadam.demo.support;

import java.nio.file.*;
import java.util.*;

/** Copy only compiled classpaths/dependencies, never the host Maven settings or entire workspace. */
public record ContainerJavaClasspath(Path directory, String classpath) {
    public static ContainerJavaClasspath prepare(Path directory, Path... additionalEntries) throws Exception {
        Files.createDirectories(directory);
        var entries = new ArrayList<String>(List.of(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(java.io.File.pathSeparator)));
        for (Path entry : additionalEntries) {
            if (!Files.isDirectory(entry)) throw new IllegalArgumentException("Required compiled test classes are missing: " + entry);
            entries.add(entry.toAbsolutePath().toString());
        }
        var classpath = new ArrayList<String>();
        for (int i = 0; i < entries.size(); i++) {
            Path source = Path.of(entries.get(i)); if (!Files.exists(source)) continue;
            String name = "entry-" + i + (Files.isDirectory(source) ? "" : ".jar"); Path target = directory.resolve(name);
            if (Files.isDirectory(source)) {
                try (var walk = Files.walk(source)) {
                    for (Path path : walk.toList()) {
                        Path destination = target.resolve(source.relativize(path).toString());
                        if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination);
                    }
                }
            } else Files.copy(source, target);
            classpath.add("/tmp/client/" + name);
        }
        return new ContainerJavaClasspath(directory, String.join(":", classpath));
    }
}
