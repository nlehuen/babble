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
            Axioms axioms = stub.getAnnotation(Axioms.class);
            Class<?> target = axioms.value();
            String targetName = target != void.class ? target.getName() : axioms.targetName();
            String targetPackage = targetName.substring(0, targetName.lastIndexOf('.'));
            String expectedPackage = "org.babblelang.axioms." + targetPackage;
            String expectedName = targetName.substring(targetPackage.length() + 1).replace("$", "") + "Axioms";
            assertEquals(expectedPackage, stub.getPackageName(), stub.getName() + " : package of " + targetName);
            assertEquals(expectedName, stub.getSimpleName(), stub.getName() + " : name for " + targetName);
        }
    }

    @Test
    void stubMethodsMirrorRealMethods() {
        List<String> stale = new ArrayList<>();
        for (Class<?> stub : BuiltinAxioms.get().stubTypes()) {
            Class<?> target = stub.getAnnotation(Axioms.class).value();
            if (target == void.class) {
                continue; // an axiom about a class named, not on the class path
            }
            for (Method m : stub.getDeclaredMethods()) {
                if (m.isAnnotationPresent(org.babblelang.axioms.Constructor.class)) {
                    if (Arrays.stream(target.getConstructors()).noneMatch(k -> Arrays.equals(k.getParameterTypes(), m.getParameterTypes()))) {
                        stale.add(stub.getSimpleName() + ".<init>" + Arrays.toString(m.getParameterTypes()));
                    }
                    continue;
                }
                String name = BuiltinAxioms.mirroredName(m);
                if (!hasPublicMethod(target, name, m.getParameterTypes())
                        && !hasPublicMethod(Object.class, name, m.getParameterTypes())) {
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

    /**
     * Without axioms the analysis knows nothing about any Java method : no assumption survives
     * outside org.babblelang.axioms (BIP019-P5).
     */
    @Test
    void withoutAxiomsNothingIsAssumed() throws Exception {
        java.lang.reflect.Method listGet = java.util.List.class.getMethod("get", int.class);
        java.lang.reflect.Method arraySize = java.util.ArrayList.class.getMethod("size");
        java.lang.reflect.Method sqrt = Math.class.getMethod("sqrt", double.class);
        String pure = "def f = fn (s: string): int -> (s.length())";

        // With the axioms of the class path.
        assertEquals(JavaNullability.Nullness.PARAMETRIC, JavaNullability.returnNullness(listGet));
        assertTrue(JavaNullability.isNullMarked(arraySize));
        assertTrue(org.babblelang.opt.HirPurity.isWhitelistedPureMath(sqrt));
        assertTrue(BytecodeEffectAnalyzer.analyze(sqrt).effects().isPure());
        assertTrue(BuiltinAxioms.get().annotationMeaning("org.jspecify.annotations.Nullable") != null);
        compile(pure);

        BuiltinAxioms.runWith(BuiltinAxioms.EMPTY, () -> {
            assertEquals(JavaNullability.Nullness.UNSPECIFIED, JavaNullability.returnNullness(listGet));
            assertTrue(!JavaNullability.isNullMarked(arraySize));
            assertTrue(!org.babblelang.opt.HirPurity.isWhitelistedPureMath(sqrt));
            assertTrue(!BytecodeEffectAnalyzer.analyze(sqrt).effects().isPure());
            assertTrue(BuiltinAxioms.get().annotationMeaning("org.jspecify.annotations.Nullable") == null);
            javax.script.ScriptException e = org.junit.jupiter.api.Assertions.assertThrows(javax.script.ScriptException.class,
                    () -> compile(pure));
            assertTrue(e.getMessage().contains("violates pure guarantee"), e.getMessage());
        });
    }

    private static void compile(String source) throws javax.script.ScriptException {
        javax.script.ScriptEngine engine = org.babblelang.engine.BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.getContext().setErrorWriter(new java.io.StringWriter());
        ((javax.script.Compilable) engine).compile(source);
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
