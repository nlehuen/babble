package org.babblelang.tests.batest;

import org.babblelang.engine.BabbleScriptEngine.Mode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for the {@code .batest.md} v2 format, explicit {@code stdout}/{@code stderr}/{@code error}
 * isolation, {@code DOTALL} {@code regex}, HIR exact/{@code regex}/{@code check} assertions,
 * {@code BatestFilter} {@code @name} selectors, and {@link BatestIrUpdater} ({@code BIP029-I2}).
 */
public class BatestV2HarnessTestCase {

    @Test
    public void testV2ParserAndExplicitStdoutStderrErrorIsolation() {
        String md = """
                # Sample v2 spec

                Free Markdown documentation outside fences.

                ```babble
                ;; babble module=math.ba
                export def add = fn (a: int, b: int): int -> ( a + b )
                ```

                ```babble
                ;; babble preamble
                def inc = fn (n: int): int -> ( n + 1 )
                ```

                ## Silent assert case

                ```babble
                def m = import("math.ba")
                assert(inc(m.add(2, 3)) == 6)
                ```

                ## Explicit stdout case

                ```babble
                println("hello")
                println("world")
                ```

                ```stdout
                hello
                world
                ```

                ## Explicit error cannot be satisfied by printing the error string

                ```babble
                println("Type error : expected int, got string")
                ```

                ```error
                Type error : expected int, got string
                ```

                ## Real compile error succeeds against error block

                ```babble
                def bad: int = "nope"
                ```

                ```error
                Type error : expected int, got string
                ```
                """;

        BatestFile file = BatestParser.parseString(md, "sample.batest.md");
        Assertions.assertEquals(BatestFile.Format.V2, file.format());
        Assertions.assertEquals("def inc = fn (n: int): int -> ( n + 1 )", file.preamble());
        Assertions.assertEquals(4, file.cases().size());
        Assertions.assertEquals("Silent assert case", file.cases().get(0).name());

        for (Mode mode : Mode.values()) {
            // Case 0: silent assert
            var o0 = BatestRunner.run(file, file.cases().get(0), mode);
            Assertions.assertNull(BatestRunner.evaluateBehavioral(file.cases().get(0), o0), mode.name());

            // Case 1: explicit stdout
            var o1 = BatestRunner.run(file, file.cases().get(1), mode);
            Assertions.assertNull(BatestRunner.evaluateBehavioral(file.cases().get(1), o1), mode.name());

            // Case 2: printing the error text must FAIL an error expectation!
            var o2 = BatestRunner.run(file, file.cases().get(2), mode);
            String err2 = BatestRunner.evaluateBehavioral(file.cases().get(2), o2);
            Assertions.assertNotNull(err2, "Printing an error message must not satisfy an error block in " + mode);
            Assertions.assertTrue(err2.contains("expected error"), err2);

            // Case 3: real Type error matches error block
            var o3 = BatestRunner.run(file, file.cases().get(3), mode);
            Assertions.assertNull(BatestRunner.evaluateBehavioral(file.cases().get(3), o3), mode.name());
        }
    }

    @Test
    public void testV2DotallRegexCaseModulesAndSessions() {
        String md = """
                # Regex, case-scoped modules, and sessions

                ## Multi-line DOTALL regex on stdout

                ```babble
                println("line1")
                println("middle 42")
                println("end")
                ```

                ```stdout
                # stdout regex
                line1.*42.*end
                ```

                ## Case-scoped virtual module

                ```babble
                ;; babble module=local.ba
                export def secret = 77
                ```

                ```babble
                def l = import("local.ba")
                assert(l.secret == 77)
                ```

                ## Multi-input session

                ```babble
                ;; babble session
                def x = 10
                ```

                ```babble
                ;; babble session
                assert(x + 5 == 15)
                ```
                """;
        BatestFile file = BatestParser.parseString(md, "features.batest.md");
        Assertions.assertEquals(3, file.cases().size());
        Assertions.assertTrue(file.cases().get(2).session());
        Assertions.assertEquals(2, file.cases().get(2).inputs().size());

        for (Mode mode : Mode.values()) {
            for (BatestFile.BatestCase c : file.cases()) {
                var o = BatestRunner.run(file, c, mode);
                Assertions.assertNull(BatestRunner.evaluateBehavioral(c, o), c.name() + " in " + mode);
            }
        }
    }

    @Test
    public void testV2HirExactRegexAndCheckAssertions() {
        String md = """
                # HIR goldens

                ## Constant fold and algebraic identity

                ```babble
                def f = fn (x: int): int -> ( (x + 0) * 1 + (x - x) )
                assert(f(7) == 7)
                ```

                ```hir
                ;; hir -O0 fn=f
                fn f (x:int) -> int {nothrow idempotent unique}
                  (+:int (*:int (block (+:int var(x) 0:int)) 1:int) (block (-:int var(x) var(x))))
                ```

                ```hir
                ;; hir -O1 fn=f
                fn f (x:int) -> int {nothrow idempotent unique}
                  var(x)
                ```

                ```hir
                ;; hir -O1 fn=f stage=before check
                contains (+:int var(x) 0:int)
                count 1 (-:int var(x) var(x))
                !contains Math.abs
                ```

                ## IEEE-754 double reassociation preserved at -O0 and -O1

                ```babble
                def add3 = fn (x: double): double -> ( x + 1.0 + 2.0 )
                assert(add3(1.0) == 4.0)
                ```

                ```hir
                ;; hir -O0,-O1 fn=add3
                fn add3 (x:double) -> double {nothrow monotone unique}
                  (+:double (+:double var(x) 1.0:double) 2.0:double)
                ```

                ## Regex IR assertion

                ```babble
                def add3r = fn (x: double): double -> ( x + 1.0 + 2.0 )
                assert(add3r(1.0) == 4.0)
                ```

                ```hir
                ;; hir -O1 fn=add3r regex
                fn add3r .*\\(\\+:double .*1\\.0:double\\) 2\\.0:double\\)
                ```
                """;
        BatestFile file = BatestParser.parseString(md, "hir_checks.batest.md");

        for (BatestFile.BatestCase c : file.cases()) {
            var r0 = BatestRunner.runWithIr(file, c, Mode.COMPILE, 0);
            Assertions.assertNull(BatestRunner.evaluateBehavioral(c, r0.outcome()));
            List<IrAssertionChecker.IrMismatch> m0 = IrAssertionChecker.checkAll(c.irExpectations(), r0.capturedIr(), 0, 0);
            Assertions.assertEquals(List.of(), m0, c.name() + " -O0");

            var r1 = BatestRunner.runWithIr(file, c, Mode.COMPILE, 1);
            Assertions.assertNull(BatestRunner.evaluateBehavioral(c, r1.outcome()));
            List<IrAssertionChecker.IrMismatch> m1 = IrAssertionChecker.checkAll(c.irExpectations(), r1.capturedIr(), 1, 0);
            Assertions.assertEquals(List.of(), m1, c.name() + " -O1");
        }

        // Default minDumpSizeForUsingPartialChecks=30 rejects partial 'check' on short (<30-line) HIR dumps.
        BatestFile.BatestCase foldCase = file.cases().get(0);
        var foldRun = BatestRunner.runWithIr(file, foldCase, Mode.COMPILE, 1);
        List<IrAssertionChecker.IrMismatch> rejected = IrAssertionChecker.checkAll(
                foldCase.irExpectations(), foldRun.capturedIr(), 1, IrAssertionChecker.DEFAULT_MIN_DUMP_SIZE_FOR_PARTIAL_CHECKS);
        Assertions.assertEquals(1, rejected.size(), "Short 2-line dump must reject partial check at threshold 30");
        Assertions.assertTrue(rejected.get(0).message().contains("minDumpSizeForUsingPartialChecks=30"),
                rejected.get(0).message());
        // Setting threshold <= actual line count (2) allows the partial check.
        Assertions.assertEquals(List.of(), IrAssertionChecker.checkAll(
                foldCase.irExpectations(), foldRun.capturedIr(), 1, 2));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> IrAssertionChecker.checkAll(foldCase.irExpectations(), foldRun.capturedIr(), 1, -1));
    }

    @Test
    public void testV2ParserValidationErrorsAndFilterByCaseName() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("# Only header\n", "no_cases.batest.md"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## \n```babble\n1\n```\n", "empty_title.batest.md"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n## A\n```babble\n2\n```\n", "dup_title.batest.md"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n", "unclosed.batest.md"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n```unknown\n```\n", "bad_kind.batest.md"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n;; babble session\n1\n```\n```hir\n;; hir -O1 fn=f\n```\n", "session_ir.batest.md"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n```stdout\n# stdout regex\n(bad\n```\n", "bad_regex.batest.md"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n```hir\n;; hir -O1 fn=f check\nunknown_op x\n```\n", "bad_check.batest.md"));

        // Fence-line options are rejected in favor of first-line comment directives.
        IllegalArgumentException fenceOpt = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n```hir -O1 fn=f\n```\n", "fence_opt.batest.md"));
        Assertions.assertTrue(fenceOpt.getMessage().contains(";; hir -O1 fn=f"), fenceOpt.getMessage());

        IllegalArgumentException stdoutFenceOpt = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n```stdout regex\na\n```\n", "stdout_opt.batest.md"));
        Assertions.assertTrue(stdoutFenceOpt.getMessage().contains("# stdout regex"), stdoutFenceOpt.getMessage());

        // Wrong comment token on directive line is rejected clearly.
        IllegalArgumentException wrongTokHir = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n```hir\n# hir -O1 fn=f\n```\n", "wrong_tok_hir.batest.md"));
        Assertions.assertTrue(wrongTokHir.getMessage().contains("wrong comment token '#'"), wrongTokHir.getMessage());

        IllegalArgumentException wrongTokStdout = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("## A\n```babble\n1\n```\n```stdout\n;; stdout regex\na\n```\n", "wrong_tok_out.batest.md"));
        Assertions.assertTrue(wrongTokStdout.getMessage().contains("wrong comment token ';;'"), wrongTokStdout.getMessage());

        String md = "## Alpha test\n```babble\nassert(true)\n```\n## Beta guard\n```babble\nassert(true)\n```\n";
        BatestFile file = BatestParser.parseString(md, "types/sample.batest.md");
        Assertions.assertTrue(BatestFilter.parse("sample@beta").matches(file, file.cases().get(1)));
        Assertions.assertFalse(BatestFilter.parse("sample@beta").matches(file, file.cases().get(0)));
        Assertions.assertTrue(BatestFilter.parse("@alpha").matches(file, file.cases().get(0)));
    }

    @Test
    public void testIrUpdaterAndReporterIrGoldenMismatch(@TempDir Path tempDir) throws Exception {
        String original = """
                ## Fold

                ```babble
                def f = fn (): int -> ( 1 + 2 )
                assert(f() == 3)
                ```

                ```hir
                ;; hir -O1 fn=f
                OLD_STALE_IR
                ```
                """;
        BatestFile file = BatestParser.parseString(original, "upd.batest.md");
        BatestFile.BatestCase c = file.cases().get(0);
        var r1 = BatestRunner.runWithIr(file, c, Mode.COMPILE, 1);
        List<IrAssertionChecker.IrMismatch> mismatches = IrAssertionChecker.checkAll(c.irExpectations(), r1.capturedIr(), 1);
        Assertions.assertEquals(1, mismatches.size());

        Path p = tempDir.resolve("upd.batest.md");
        Files.writeString(p, original, StandardCharsets.UTF_8);
        BatestIrUpdater updater = new BatestIrUpdater();
        updater.record(p, mismatches.get(0).expectation(), mismatches.get(0).actualCanonical());
        String summary = updater.flushAll();
        Assertions.assertTrue(summary.contains("Updated 1 IR golden block(s) in 1 file(s)"), summary);

        String updatedText = Files.readString(p, StandardCharsets.UTF_8);
        Assertions.assertTrue(updatedText.contains("```hir\n;; hir -O1 fn=f\n"),
                "Updater must preserve the first-line ';; hir ...' directive:\n" + updatedText);

        BatestFile reloaded = BatestParser.parse(p, "upd.batest.md");
        List<IrAssertionChecker.IrMismatch> after = IrAssertionChecker.checkAll(
                reloaded.cases().get(0).irExpectations(), r1.capturedIr(), 1);
        Assertions.assertEquals(List.of(), after, "Reloaded updated golden must match cleanly");

        // Verify BatestCoverageReporter classifies IR mismatch separately from behavioral parity.
        Path reportDir = tempDir.resolve("rep");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("outcomes-interpret.jsonl"),
                "{\"file\":\"a.batest.md\",\"index\":1,\"line\":1,\"name\":\"Fold\",\"expected\":\"\",\"kind\":\"OK\",\"text\":\"\",\"passed\":true}\n");
        Files.writeString(reportDir.resolve("outcomes-compile.jsonl"),
                "{\"file\":\"a.batest.md\",\"index\":1,\"line\":1,\"name\":\"Fold\",\"expected\":\"\",\"kind\":\"OK\",\"text\":\"\",\"passed\":false,\"irError\":\"IR mismatch\"}\n");
        Files.writeString(reportDir.resolve("outcomes-compile-O0.jsonl"),
                "{\"file\":\"a.batest.md\",\"index\":1,\"line\":1,\"name\":\"Fold\",\"expected\":\"\",\"kind\":\"OK\",\"text\":\"\",\"passed\":true}\n");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AssertionError ae = Assertions.assertThrows(AssertionError.class, () ->
                BatestCoverageReporter.run(reportDir, reportDir.resolve("missing1.xml"),
                        reportDir.resolve("missing2.xml"), reportDir.resolve("missing3.xml"),
                        reportDir.resolve("missing4.xml"), Path.of("src/main/java"),
                        "", "", 0.0, 5, BatestCoverageReporter.SortMode.SCORE,
                        new PrintStream(baos, true, StandardCharsets.UTF_8)));
        String reportText = baos.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(reportText.contains("0 divergences"), "Behavioral parity must remain 0 divergences:\n" + reportText);
        Assertions.assertTrue(reportText.contains("[IR GOLDEN MISMATCH]"), reportText);
        Assertions.assertTrue(reportText.contains("(Fold)"), reportText);
        Assertions.assertTrue(ae.getMessage().contains("1 .batest case failure"), ae.getMessage());
    }

    @Test
    public void testEveryCompilerBatestCaseHasHirO0O1AndMirO2BeforeAfterExpectations() throws Exception {
        Path compilerDir = BatestTestCase.BATEST_ROOT.resolve("compiler");
        Assertions.assertTrue(Files.isDirectory(compilerDir), "Missing " + compilerDir);
        List<String> violations = new java.util.ArrayList<>();
        int checkedCases = 0;
        try (var walk = Files.walk(compilerDir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(BatestTestCase::isBatestFile)
                    .sorted()
                    .toList();
            for (Path p : files) {
                String rel = BatestTestCase.BATEST_ROOT.relativize(p).toString().replace('\\', '/');
                BatestFile file = BatestParser.parse(p, rel);
                for (BatestFile.BatestCase c : file.cases()) {
                    if (c.expectedError() != null || c.session()) {
                        continue;
                    }
                    checkedCases++;
                    boolean hasO0 = c.irExpectations().stream()
                            .anyMatch(e -> e.tier() == org.babblelang.ir.IrTier.HIR && e.appliesTo(0));
                    boolean hasO1 = c.irExpectations().stream()
                            .anyMatch(e -> e.tier() == org.babblelang.ir.IrTier.HIR && e.appliesTo(1));
                    boolean hasMirBefore = c.irExpectations().stream()
                            .anyMatch(e -> e.tier() == org.babblelang.ir.IrTier.MIR && e.appliesTo(2) && e.matchesStage("before"));
                    boolean hasMirFinal = c.irExpectations().stream()
                            .anyMatch(e -> e.tier() == org.babblelang.ir.IrTier.MIR && e.appliesTo(2) && e.matchesStage("final"));
                    if (!hasO0 || !hasO1 || !hasMirBefore || !hasMirFinal) {
                        violations.add(rel + ":" + c.line() + " (" + c.displayName() + ") missing"
                                + (!hasO0 ? " hir -O0" : "")
                                + (!hasO1 ? " hir -O1" : "")
                                + (!hasMirBefore ? " mir -O2 stage=before" : "")
                                + (!hasMirFinal ? " mir -O2 stage=final" : ""));
                    }
                }
            }
        }
        Assertions.assertTrue(checkedCases > 0, "Expected compiler batest cases under " + compilerDir);
        Assertions.assertEquals(List.of(), violations,
                "Every non-error compiler batest case must assert hir -O0, hir -O1, mir -O2 stage=before, and mir -O2 stage=final:\n"
                        + String.join("\n", violations));
    }

    @Test
    public void testCompilerSuiteParserEnforcementAndMultiStage() {
        String missingMir = """
                # Demo

                ## Missing MIR
                ```babble
                def f = fn (x: int): int -> (x + 1)
                assert(f(1) == 2)
                ```
                ```hir
                ;; hir -O0,-O1 fn=f
                fn f (x:int) -> int
                  (+:int var(x) 1:int)
                ```
                """;
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestV2Parser.parseString(missingMir, "compiler/demo.batest.md"));
        Assertions.assertTrue(ex.getMessage().contains("mir -O2 stage=before"), ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("mir -O2 stage=final"), ex.getMessage());

        String validCombined = """
                # Demo

                ## Combined stage
                ```babble
                def f = fn (x: int): int -> (x + 1)
                assert(f(1) == 2)
                ```
                ```hir
                ;; hir -O0,-O1 fn=f
                fn f (x:int) -> int
                  (+:int var(x) 1:int)
                ```
                ```mir
                ;; mir -O2 fn=f stage=before,final
                fn f(v0: int) -> int:
                  bb0:
                    v1: int = const 1
                    v2: int = add v0, v1 {pure}
                    ret v2
                ```
                """;
        BatestFile parsed = BatestV2Parser.parseString(validCombined, "compiler/demo.batest.md");
        BatestFile.IrExpectation mir = parsed.cases().get(0).irExpectations().get(1);
        Assertions.assertEquals(org.babblelang.ir.IrTier.MIR, mir.tier());
        Assertions.assertTrue(mir.appliesTo(2));
        Assertions.assertTrue(mir.matchesStage("before"));
        Assertions.assertTrue(mir.matchesStage("final"));
        Assertions.assertFalse(mir.matchesStage("after:SccpPass"));
    }
}
