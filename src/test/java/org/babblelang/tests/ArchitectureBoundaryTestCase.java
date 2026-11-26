package org.babblelang.tests;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.babblelang.runtime.Namespace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Compiler architecture boundary and file-size ceiling tests ({@code BIP027-D9}).
 *
 * <p>Prevents architectural regression by enforcing:
 *
 * <ol>
 *   <li>Zero reverse dependencies from {@code analysis}, {@code ir}, {@code opt}, or {@code
 *       compiler} into {@code org.babblelang.engine.impl} (e.g. {@code Interpreter}).
 *   <li>ANTLR isolation: zero {@code org.antlr} or {@code org.babblelang.parser} references in
 *       {@code ir} (outside {@code HirBuilder}), {@code opt}, {@code compiler} (outside the {@code
 *       BabbleCompiler} entry point), and ANTLR-free {@code analysis} core classes.
 *   <li>{@code ScopeAnalysis} isolation: zero {@code ScopeAnalysis} references in {@code ir}
 *       (outside {@code HirBuilder}), {@code opt}, or {@code compiler} (outside {@code
 *       BabbleCompiler}).
 *   <li>Universal hard physical-LOC ceiling (≤ {@value #HARD_MAX_LOC} LOC) across all production
 *       source files under {@code src/main}, with a shrinking grandfather ratchet for pre-existing
 *       oversized files.
 * </ol>
 */
public class ArchitectureBoundaryTestCase {
    static final int HARD_MAX_LOC = 2_000;
    static final int MAX_GRANDFATHERED_SLACK = 50;

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".java", ".g4", ".ba");

    private static final Set<String> ANTLR_FREE_ANALYSIS_CORES =
            Set.of(
                    "org/babblelang/analysis/CompilationTarget",
                    "org/babblelang/analysis/PrimitiveKind",
                    "org/babblelang/analysis/LoweringTargets",
                    "org/babblelang/analysis/TypeAlgebra",
                    "org/babblelang/analysis/JavaTypeBridge",
                    "org/babblelang/analysis/TypeDisplay",
                    "org/babblelang/analysis/OverloadSolver",
                    "org/babblelang/analysis/Reachability",
                    "org/babblelang/analysis/JavaEffectResolver",
                    "org/babblelang/analysis/EffectFixpointDriver",
                    "org/babblelang/analysis/SymbolicEffectSummary",
                    "org/babblelang/analysis/ModalKindTable",
                    "org/babblelang/analysis/Purity",
                    "org/babblelang/analysis/HostEffects",
                    "org/babblelang/analysis/AlgebraicVerifier",
                    "org/babblelang/analysis/SizeChangeTermination",
                    "org/babblelang/analysis/MatchExhaustiveness");

    /**
     * Temporary high-water-mark ceilings for pre-existing {@code src/main} files above {@value
     * #HARD_MAX_LOC} physical LOC. Periodic refactorings may only lower or remove entries; once a
     * file drops to ≤ {@value #HARD_MAX_LOC} LOC, {@link
     * #productionSourcesRespectHardSizeCeiling()} fails until its stale exemption is removed.
     */
    private static final Map<String, Integer> GRANDFATHERED_CEILINGS =
            Map.ofEntries(
                    Map.entry("src/main/java/org/babblelang/wasm/WasmFuncEmitter.java", 2_814),
                    Map.entry("src/main/java/org/babblelang/ir/hir/HirBuilder.java", 2_797),
                    Map.entry("src/main/java/org/babblelang/opt/hir/ScalarReplacePass.java", 2_550),
                    Map.entry("src/main/java/org/babblelang/compiler/MirEmitter.java", 2_244),
                    Map.entry("src/main/java/org/babblelang/wasm/WasmCoreEmitter.java", 2_463),
                    Map.entry("src/main/java/org/babblelang/analysis/EffectBodyWalker.java", 2_430),
                    Map.entry("src/main/java/org/babblelang/compiler/HirEmitter.java", 2_357),
                    Map.entry("src/main/java/org/babblelang/wasm/WasmCallEmitter.java", 2_380),
                    Map.entry("src/main/java/org/babblelang/compiler/CallEmitter.java", 2_195),
                    Map.entry(
                            "src/main/java/org/babblelang/analysis/CallEffectAnalyzer.java", 2_146),
                    Map.entry("src/main/java/org/babblelang/wasm/WasmObjectSystem.java", 2_109));

    private static Path classesDirectory() throws URISyntaxException {
        Path directory =
                Path.of(Namespace.class.getResource("Namespace.class").toURI()).getParent();
        for (int i = 0; i < Namespace.class.getPackageName().split("\\.").length; i++) {
            directory = directory.getParent();
        }
        return directory;
    }

    private static List<String> compiledClassesUnder(Path root, String packagePrefix)
            throws Exception {
        Path dir = root.resolve(packagePrefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(f -> f.toString().endsWith(".class"))
                    .forEach(f -> {
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
        classes.addAll(compiledClassesUnder(root, "org/babblelang/wasm"));
        Assertions.assertFalse(classes.isEmpty());

        List<String> violations = new ArrayList<>();
        for (String name : classes) {
            try (InputStream in = Files.newInputStream(root.resolve(name + ".class"))) {
                for (String ref : RuntimeBoundaryTestCase.referencedClasses(in)) {
                    if (ref.startsWith("org/babblelang/engine/impl/")
                            && !RuntimeBoundaryTestCase.isRuntime(ref)) {
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
        classes.addAll(compiledClassesUnder(root, "org/babblelang/wasm"));
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
        classes.addAll(compiledClassesUnder(root, "org/babblelang/wasm"));
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

    private static boolean isProductionSource(Path path) {
        String name = path.getFileName().toString();
        for (String ext : SOURCE_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void productionSourcesRespectHardSizeCeiling() throws Exception {
        Path srcMain = Path.of("src/main");
        Assertions.assertTrue(Files.isDirectory(srcMain), "Missing source root : " + srcMain);

        Map<String, Long> lineCounts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(srcMain)) {
            files.filter(Files::isRegularFile)
                    .filter(ArchitectureBoundaryTestCase::isProductionSource)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        String rel = path.toString().replace('\\', '/');
                        try (Stream<String> stream = Files.lines(path)) {
                            lineCounts.put(rel, stream.count());
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read " + rel, e);
                        }
                    });
        }
        Assertions.assertFalse(lineCounts.isEmpty());

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lineCounts.entrySet()) {
            String rel = entry.getKey();
            long lines = entry.getValue();
            int ceiling = GRANDFATHERED_CEILINGS.getOrDefault(rel, HARD_MAX_LOC);
            if (lines > ceiling) {
                String label =
                        GRANDFATHERED_CEILINGS.containsKey(rel)
                                ? "grandfathered ceiling"
                                : "hard limit";
                violations.add(rel + " has " + lines + " LOC (" + label + " is " + ceiling + ")");
            }
        }
        for (Map.Entry<String, Integer> entry : new TreeMap<>(GRANDFATHERED_CEILINGS).entrySet()) {
            String rel = entry.getKey();
            int ceiling = entry.getValue();
            Long lines = lineCounts.get(rel);
            if (lines == null) {
                violations.add("Stale GRANDFATHERED_CEILINGS entry (missing file) : " + rel);
            } else if (lines <= HARD_MAX_LOC) {
                violations.add(
                        "Stale GRANDFATHERED_CEILINGS entry ("
                                + lines
                                + " <= "
                                + HARD_MAX_LOC
                                + " LOC, remove exemption) : "
                                + rel);
            } else if (ceiling <= HARD_MAX_LOC) {
                violations.add(
                        "Invalid GRANDFATHERED_CEILINGS entry (ceiling "
                                + ceiling
                                + " <= "
                                + HARD_MAX_LOC
                                + ") : "
                                + rel);
            } else if (ceiling - lines > MAX_GRANDFATHERED_SLACK) {
                violations.add(
                        "Loose GRANDFATHERED_CEILINGS entry ("
                                + lines
                                + " LOC vs ceiling "
                                + ceiling
                                + ", slack > "
                                + MAX_GRANDFATHERED_SLACK
                                + "; tighten ceiling to "
                                + lines
                                + ") : "
                                + rel);
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

    @Test
    public void wasmBackendFreeOfAsm() throws Exception {
        Path root = classesDirectory();
        List<String> classes = compiledClassesUnder(root, "org/babblelang/wasm");
        Assertions.assertFalse(classes.isEmpty());
        List<String> violations = new ArrayList<>();
        for (String name : classes) {
            try (InputStream in = Files.newInputStream(root.resolve(name + ".class"))) {
                for (String ref : RuntimeBoundaryTestCase.referencedClasses(in)) {
                    if (ref.startsWith("org/objectweb/asm/")) {
                        violations.add(name + " -> " + ref);
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), violations);
    }
}
