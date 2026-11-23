package org.babblelang.tests;

import org.babblelang.runtime.Namespace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Compiler architecture boundary and file-size ceiling tests ({@code BIP027-D9}).
 *
 * <p>Prevents architectural regression by enforcing:
 * <ol>
 *   <li>Zero reverse dependencies from {@code analysis}, {@code ir}, {@code opt}, or
 *       {@code compiler} into {@code org.babblelang.engine.impl} (e.g. {@code Interpreter}).</li>
 *   <li>ANTLR isolation: zero {@code org.antlr} or {@code org.babblelang.parser} references in
 *       {@code ir} (outside {@code HirBuilder}), {@code opt}, {@code compiler} (outside the
 *       {@code BabbleCompiler} entry point), and ANTLR-free {@code analysis} core classes.</li>
 *   <li>{@code ScopeAnalysis} isolation: zero {@code ScopeAnalysis} references in {@code ir}
 *       (outside {@code HirBuilder}), {@code opt}, or {@code compiler} (outside
 *       {@code BabbleCompiler}).</li>
 *   <li>File-size ceilings (≤ {@value #MAX_REFACTORED_LOC} LOC) on refactored compiler classes.</li>
 * </ol>
 */
public class ArchitectureBoundaryTestCase {
    static final int MAX_REFACTORED_LOC = 2_000;

    private static final Set<String> ANTLR_FREE_ANALYSIS_CORES = Set.of(
            "org/babblelang/analysis/PrimitiveKind",
            "org/babblelang/analysis/LoweringTargets",
            "org/babblelang/analysis/TypeAlgebra",
            "org/babblelang/analysis/JavaTypeBridge",
            "org/babblelang/analysis/TypeDisplay",
            "org/babblelang/analysis/OverloadSolver",
            "org/babblelang/analysis/Reachability",
            "org/babblelang/analysis/JavaEffectResolver",
            "org/babblelang/analysis/EffectFixpointDriver",
            "org/babblelang/analysis/SymbolicEffectSummary"
    );

    /**
     * Refactored compiler classes that must remain at or below {@value #MAX_REFACTORED_LOC} LOC.
     * Extended as each {@code BIP027} phase lands ({@code CallSolver} in P1, {@code HirEmitter}
     * in P2, {@code EffectChecker} in P3).
     */
    private static final List<String> SIZE_CAPPED_SOURCES = List.of(
            "src/main/java/org/babblelang/analysis/CallSolver.java",
            "src/main/java/org/babblelang/compiler/HirEmitter.java",
            "src/main/java/org/babblelang/analysis/EffectChecker.java"
    );

    private static Path classesDirectory() throws URISyntaxException {
        Path directory = Path.of(Namespace.class.getResource("Namespace.class").toURI()).getParent();
        for (int i = 0; i < Namespace.class.getPackageName().split("\\.").length; i++) {
            directory = directory.getParent();
        }
        return directory;
    }

    private static List<String> compiledClassesUnder(Path root, String packagePrefix) throws Exception {
        Path dir = root.resolve(packagePrefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(f -> f.toString().endsWith(".class")).forEach(f -> {
                String name = root.relativize(f).toString().replace('\\', '/');
                result.add(name.substring(0, name.length() - ".class".length()));
            });
        }
        return result;
    }

    private static String topLevelClass(String internalName) {
        int dollar = internalName.indexOf('$');
        return dollar >= 0 ? internalName.substring(0, dollar) : internalName;
    }

    private static boolean isAntlrReference(String internalName) {
        return internalName.startsWith("org/antlr/")
                || internalName.startsWith("org/babblelang/parser/");
    }

    @Test
    public void noReverseDependencyIntoInterpreterOrEngineImpl() throws Exception {
        Path root = classesDirectory();
        List<String> classes = new ArrayList<>();
        classes.addAll(compiledClassesUnder(root, "org/babblelang/analysis"));
        classes.addAll(compiledClassesUnder(root, "org/babblelang/ir"));
        classes.addAll(compiledClassesUnder(root, "org/babblelang/opt"));
        classes.addAll(compiledClassesUnder(root, "org/babblelang/compiler"));
        Assertions.assertFalse(classes.isEmpty());

        List<String> violations = new ArrayList<>();
        for (String name : classes) {
            try (InputStream in = Files.newInputStream(root.resolve(name + ".class"))) {
                for (String ref : RuntimeBoundaryTestCase.referencedClasses(in)) {
                    if (ref.startsWith("org/babblelang/engine/impl/") && !RuntimeBoundaryTestCase.isRuntime(ref)) {
                        violations.add(name + " -> " + ref);
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), violations);
    }

    @Test
    public void antlrIsolatedFromIrOptCompilerAndAnalysisCores() throws Exception {
        Path root = classesDirectory();
        List<String> classes = new ArrayList<>();
        classes.addAll(compiledClassesUnder(root, "org/babblelang/ir"));
        classes.addAll(compiledClassesUnder(root, "org/babblelang/opt"));
        classes.addAll(compiledClassesUnder(root, "org/babblelang/compiler"));
        for (String cls : compiledClassesUnder(root, "org/babblelang/analysis")) {
            if (ANTLR_FREE_ANALYSIS_CORES.contains(topLevelClass(cls))) {
                classes.add(cls);
            }
        }
        Assertions.assertFalse(classes.isEmpty());

        List<String> violations = new ArrayList<>();
        for (String name : classes) {
            String top = topLevelClass(name);
            if (top.equals("org/babblelang/ir/hir/HirBuilder")
                    || top.equals("org/babblelang/compiler/BabbleCompiler")) {
                continue;
            }
            try (InputStream in = Files.newInputStream(root.resolve(name + ".class"))) {
                for (String ref : RuntimeBoundaryTestCase.referencedClasses(in)) {
                    if (isAntlrReference(ref)) {
                        violations.add(name + " -> " + ref);
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), violations);
    }

    @Test
    public void scopeAnalysisIsolatedFromIrOptAndCompiler() throws Exception {
        Path root = classesDirectory();
        List<String> classes = new ArrayList<>();
        classes.addAll(compiledClassesUnder(root, "org/babblelang/ir"));
        classes.addAll(compiledClassesUnder(root, "org/babblelang/opt"));
        classes.addAll(compiledClassesUnder(root, "org/babblelang/compiler"));
        Assertions.assertFalse(classes.isEmpty());

        List<String> violations = new ArrayList<>();
        for (String name : classes) {
            String top = topLevelClass(name);
            if (top.equals("org/babblelang/ir/hir/HirBuilder")
                    || top.equals("org/babblelang/compiler/BabbleCompiler")) {
                continue;
            }
            try (InputStream in = Files.newInputStream(root.resolve(name + ".class"))) {
                for (String ref : RuntimeBoundaryTestCase.referencedClasses(in)) {
                    if (topLevelClass(ref).equals("org/babblelang/analysis/ScopeAnalysis")) {
                        violations.add(name + " -> " + ref);
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), violations);
    }

    @Test
    public void refactoredCompilerClassesRespectSizeCeiling() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String relativePath : SIZE_CAPPED_SOURCES) {
            Path path = Path.of(relativePath);
            Assertions.assertTrue(Files.isRegularFile(path), "Missing source file : " + relativePath);
            long lines;
            try (Stream<String> stream = Files.lines(path)) {
                lines = stream.count();
            }
            if (lines > MAX_REFACTORED_LOC) {
                violations.add(relativePath + " has " + lines + " LOC (ceiling is " + MAX_REFACTORED_LOC + ")");
            }
        }
        Assertions.assertEquals(List.of(), violations);
    }

    @Test
    public void effectCheckerIsolatesReflection() throws Exception {
        Path root = classesDirectory();
        List<String> violations = new ArrayList<>();
        for (String name : compiledClassesUnder(root, "org/babblelang/analysis")) {
            if (!topLevelClass(name).equals("org/babblelang/analysis/EffectChecker")) {
                continue;
            }
            try (InputStream in = Files.newInputStream(root.resolve(name + ".class"))) {
                for (String ref : RuntimeBoundaryTestCase.referencedClasses(in)) {
                    if (ref.startsWith("java/lang/reflect/")) {
                        violations.add(name + " -> " + ref);
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), violations);
    }
}
