package org.babblelang.tests;

import org.babblelang.analysis.AxiomDumper;
import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.format.Formatter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.SimpleScriptContext;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Enforcement tests for BIP019 ({@code docs/design/bip019_refactoring-axioms.md} §7).
 *
 * <p>Verifies:
 * <ol>
 *   <li>Every curated axiom matches a real public Java executable, belongs to its file's package,
 *       has no duplicates, obeys canonical §3.4 order, and is formatter-clean.</li>
 *   <li>With {@link BuiltinAxioms#EMPTY} and bytecode analysis disabled, the analysis knows nothing
 *       about {@code String}, {@code List}, {@code Math}, {@code Map}, {@code Map.Entry}, or
 *       ordinary built-ins ({@code int}, {@code assert}, {@code same}, {@code classof}).</li>
 *   <li>{@code src/main/java/org/babblelang/axioms/README.md} lists every axiom file and every
 *       {@code QUESTIONABLE} axiom.</li>
 *   <li>Legacy {@code axiom ... on ...} syntax emits a deprecation warning while still working.</li>
 * </ol>
 */
public class AxiomsTestCase {

    private static final Path AXIOMS_ROOT = Path.of("src/main/java/org/babblelang/axioms");

    @Test
    public void testAllCuratedAxiomFilesValidCanonicalAndFormatted() throws Exception {
        Set<String> packages = AxiomDumper.discoverCuratedPackages();
        Assertions.assertFalse(packages.isEmpty(), "Expected curated axiom packages");
        BuiltinAxioms std = BuiltinAxioms.standard();

        for (String pkg : packages) {
            Path file = AXIOMS_ROOT.resolve(pkg.replace('.', '/')).resolve("curated.ba");
            Assertions.assertTrue(Files.isRegularFile(file), "Missing source file for package : " + pkg);
            String source = Files.readString(file);

            // 1. Canonical §3.4 order & package membership:
            List<String> orderViolations = AxiomDumper.checkCanonicalOrder(source, file.toString(), pkg);
            Assertions.assertEquals(List.of(), orderViolations, "Canonical order violation in " + file);

            // 2. Every member axiom matches a real public Java method or constructor:
            List<String> memberErrors = AxiomDumper.validateCuratedPackage(std, pkg);
            Assertions.assertEquals(List.of(), memberErrors, "Stale or mistyped axiom in " + file);

            // 3. Formatter-clean:
            String formatted = Formatter.format(source, file.toString());
            Assertions.assertEquals(source, formatted, "Axiom file is not formatter-clean : " + file);
        }

        // builtins.ba must also parse cleanly and be formatter-clean:
        Path builtins = AXIOMS_ROOT.resolve("builtins.ba");
        Assertions.assertTrue(Files.isRegularFile(builtins), "Missing builtins.ba");
        String builtinsSrc = Files.readString(builtins);
        Assertions.assertEquals(builtinsSrc, Formatter.format(builtinsSrc, builtins.toString()),
                "builtins.ba is not formatter-clean");
        Assertions.assertFalse(std.builtins().isEmpty(), "builtins.ba must declare ordinary built-ins");
    }

    @Test
    public void testEmptyAxiomsAndNoBytecodeAnalysisKnowsNothing() throws Exception {
        BuiltinAxioms empty = BuiltinAxioms.EMPTY;
        Assertions.assertTrue(empty.isEmpty());
        Assertions.assertFalse(empty.nullMarked(String.class));
        Assertions.assertFalse(empty.nullMarked(java.util.List.class));
        Assertions.assertFalse(empty.isDeeplyImmutable(java.util.Map.Entry.class));
        Assertions.assertTrue(empty.builtins().isEmpty());

        Method mathSqrt = Math.class.getMethod("sqrt", double.class);
        Method mathAbs = Math.class.getMethod("abs", double.class);
        Method strTrim = String.class.getMethod("trim");
        Assertions.assertFalse(empty.isFoldable(mathSqrt));
        Assertions.assertFalse(empty.isIdempotent(mathAbs));
        Assertions.assertFalse(empty.isFoldable(strTrim));
        Assertions.assertFalse(empty.isIdempotent(strTrim));

        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.setBuiltinAxioms(BuiltinAxioms.EMPTY);
        engine.enableBytecodeEffectAnalysis(false);

        // String.length, List.add, Math.sqrt, Map.get are unconstrained (rejected in pure fn):
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(
                "def f = fn (s: string): int -> (s.length())"));
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(
                "def f = fn (mut xs: list of int): bool -> (xs.add(1))"));
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(
                "def f = fn (x: double): double -> (java.lang.Math.sqrt(x))"));
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(
                "def f = fn (m: map of (string, int)): int? -> (m.get(\"a\"))"));

        // Nullness is unspecified (String.trim returns string?, not string):
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(
                "def f = fn io (s: string): string -> (s.trim())"));

        // Map.Entry is not deeply immutable:
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(
                "def f = fn (e: entry of (string, int)): string -> (e.getKey())"));

        // Ordinary built-ins (int, assert, same, classof) are unknown:
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval("int(3.5)"));
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval("assert(true)"));
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval("same(1, 1)"));
        Assertions.assertThrows(TypeCheckException.class, () -> engine.eval("classof(1)"));
    }

    @Test
    public void testReadmeListsEveryAxiomFileAndQuestionableAxiom() throws Exception {
        String readme = Files.readString(AXIOMS_ROOT.resolve("README.md"));

        // 1. Every .ba file and every *Axioms.java stub must be mentioned in README.md:
        List<String> missingFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(AXIOMS_ROOT)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                Path rel = AXIOMS_ROOT.relativize(p);
                String relStr = rel.toString().replace('\\', '/');
                if (relStr.endsWith(".ba")) {
                    if (!readme.contains(relStr)) {
                        missingFiles.add(relStr);
                    }
                } else if (relStr.endsWith("Axioms.java") && !relStr.equals("Axioms.java")) {
                    String fileName = p.getFileName().toString();
                    String dirPrefix = rel.getParent() != null ? rel.getParent().toString().replace('\\', '/') : "";
                    if (!readme.contains(relStr) && !(readme.contains(dirPrefix + "/") && readme.contains(fileName))) {
                        missingFiles.add(relStr);
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), missingFiles, "Axiom files missing from axioms/README.md");

        // 2. Every target tagged `;; QUESTIONABLE` in any curated.ba must be indexed in README.md:
        Set<String> missingQuestionable = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(AXIOMS_ROOT)) {
            for (Path p : walk.filter(f -> f.getFileName().toString().equals("curated.ba")).sorted().toList()) {
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains("QUESTIONABLE")) {
                        // Find the next `axiom <target>` line:
                        for (int j = i + 1; j < lines.size(); j++) {
                            String next = lines.get(j).trim();
                            if (next.startsWith("axiom ")) {
                                String after = next.substring("axiom ".length());
                                String target = after.split("[ :]+")[0];
                                if (!readme.contains(target)) {
                                    missingQuestionable.add(AXIOMS_ROOT.relativize(p) + " : " + target);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), new ArrayList<>(missingQuestionable),
                "QUESTIONABLE axioms missing from axioms/README.md");
    }

    @Test
    public void testDeprecatedAxiomOnEmitsWarning() throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        SimpleScriptContext ctx = new SimpleScriptContext();
        StringWriter err = new StringWriter();
        ctx.setErrorWriter(err);
        Object res = engine.eval("""
                axiom pure nothrow on java.lang.System.nanoTime
                def f = fn nothrow (): long -> (java.lang.System.nanoTime())
                f() >= 0
                """, ctx);
        Assertions.assertEquals(true, res);
        Assertions.assertTrue(err.toString().contains("Deprecated syntax : 'axiom ... on ...' is deprecated"), err.toString());
    }

    @Test
    public void testGoldenDumpsMatchCanonicalFormat() {
        String mathDump = AxiomDumper.dumpClass(Math.class, BuiltinAxioms.standard());
        Assertions.assertEquals(List.of(), AxiomDumper.checkCanonicalOrder(mathDump, "Math"));
        Assertions.assertTrue(mathDump.contains("def abs: fn nothrow idempotent foldable (p0: double): double"), mathDump);
        Assertions.assertTrue(mathDump.contains("def sqrt: fn nothrow foldable (p0: double): double"), mathDump);

        String listDump = AxiomDumper.dumpClass(java.util.ArrayList.class, BuiltinAxioms.standard());
        Assertions.assertEquals(List.of(), AxiomDumper.checkCanonicalOrder(listDump, "ArrayList"));
        Assertions.assertTrue(listDump.startsWith("axiom java.util.ArrayList of E: class nullmarked (\n"), listDump);
        Assertions.assertTrue(listDump.contains("def add: fn mut shared shallow (shared p0: any): bool"), listDump);
    }
}
