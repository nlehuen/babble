package org.babblelang.analysis;

import org.babblelang.axioms.Axioms;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The axioms of {@code org.babblelang.axioms} (BIP019) : every stub is found, named after its
 * target, mirrors real methods, and is listed in the axioms' README.
 */
class AxiomsTestCase {
    private static final Path DIRECTORY = Path.of("src/main/java/org/babblelang/axioms");

    /** The stub source files : every {@code *Axioms.java} but the annotation's own. */
    private static List<Path> stubFiles() throws IOException {
        try (Stream<Path> files = Files.walk(DIRECTORY)) {
            return files.filter(p -> p.getFileName().toString().endsWith("Axioms.java"))
                    .filter(p -> !p.equals(DIRECTORY.resolve("Axioms.java")))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void everyStubFileIsFound() throws IOException {
        Set<String> found = BuiltinAxioms.get().stubTypes().stream().map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> files = new TreeSet<>();
        for (Path file : stubFiles()) {
            String relative = DIRECTORY.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            files.add("org.babblelang.axioms." + relative.substring(0, relative.length() - ".java".length()).replace('/', '.'));
        }
        assertEquals(files, found, "every stub file, and only them, is an @Axioms type found on the class path");
    }

    @Test
    void stubsAreNamedAfterTheirTarget() {
        for (Class<?> stub : BuiltinAxioms.get().stubTypes()) {
            Class<?> target = stub.getAnnotation(Axioms.class).value();
            String expectedPackage = "org.babblelang.axioms." + target.getPackageName();
            String expectedName = target.getName().substring(target.getPackageName().length() + 1).replace("$", "") + "Axioms";
            assertEquals(expectedPackage, stub.getPackageName(), stub.getName() + " : package of " + target.getName());
            assertEquals(expectedName, stub.getSimpleName(), stub.getName() + " : name for " + target.getName());
        }
    }

    @Test
    void stubMethodsMirrorRealMethods() {
        List<String> stale = new ArrayList<>();
        for (Class<?> stub : BuiltinAxioms.get().stubTypes()) {
            Class<?> target = stub.getAnnotation(Axioms.class).value();
            for (Method m : stub.getDeclaredMethods()) {
                if (!hasPublicMethod(target, m.getName(), m.getParameterTypes())
                        && !hasPublicMethod(Object.class, m.getName(), m.getParameterTypes())) {
                    stale.add(stub.getSimpleName() + "." + m.getName() + Arrays.toString(m.getParameterTypes()));
                }
            }
        }
        assertTrue(stale.isEmpty(), "stub methods without a public method of their target : " + stale);
    }

    @Test
    void readmeListsEveryStub() throws IOException {
        String readme = Files.readString(DIRECTORY.resolve("README.md"));
        List<String> missing = new ArrayList<>();
        for (Path file : stubFiles()) {
            if (!readme.contains(file.getFileName().toString())) {
                missing.add(file.getFileName().toString());
            }
        }
        assertTrue(missing.isEmpty(), "stubs missing from the axioms' README : " + missing);
    }

    private static boolean hasPublicMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), parameterTypes)) {
                return true;
            }
        }
        for (Method m : type.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getName().equals(name)
                    && Arrays.equals(m.getParameterTypes(), parameterTypes)) {
                return true;
            }
        }
        return false;
    }
}
