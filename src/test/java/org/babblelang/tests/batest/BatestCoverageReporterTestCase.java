package org.babblelang.tests.batest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link BatestCoverageReporter} scope classification, XML aggregation,
 * combined shared-runtime union math, and strict failure enforcement.
 */
public class BatestCoverageReporterTestCase {

    @Test
    public void testScopeClassificationAndExclusions() {
        Assertions.assertEquals(BatestCoverageReporter.Scope.INTERPRETER,
                BatestCoverageReporter.scopeOf("org.babblelang.engine.impl.Interpreter"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.INTERPRETER,
                BatestCoverageReporter.scopeOf("org.babblelang.engine.impl.Function"));

        Assertions.assertEquals(BatestCoverageReporter.Scope.COMPILER,
                BatestCoverageReporter.scopeOf("org.babblelang.compiler.CodeGenerator"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.COMPILER,
                BatestCoverageReporter.scopeOf("org.babblelang.ir.hir.HirBuilder"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.COMPILER,
                BatestCoverageReporter.scopeOf("org.babblelang.runtime.Bootstrap"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.COMPILER,
                BatestCoverageReporter.scopeOf("org.babblelang.runtime.IntCell"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.OPTIMIZER,
                BatestCoverageReporter.scopeOf("org.babblelang.opt.PassManager"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.OPTIMIZER,
                BatestCoverageReporter.scopeOf("org.babblelang.opt.hir.HirEGraphSimplifyPass"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.OPTIMIZER,
                BatestCoverageReporter.scopeOf("org.babblelang.ir.egraph.EGraph"));

        Assertions.assertEquals(BatestCoverageReporter.Scope.FRONT_END,
                BatestCoverageReporter.scopeOf("org.babblelang.analysis.TypeChecker"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.FRONT_END,
                BatestCoverageReporter.scopeOf("org.babblelang.analysis.EffectChecker"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.FRONT_END,
                BatestCoverageReporter.scopeOf("org.babblelang.effects.Effect"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.FRONT_END,
                BatestCoverageReporter.scopeOf("org.babblelang.engine.BabbleScriptEngine"));

        Assertions.assertEquals(BatestCoverageReporter.Scope.SHARED_RUNTIME,
                BatestCoverageReporter.scopeOf("org.babblelang.runtime.Operators"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.SHARED_RUNTIME,
                BatestCoverageReporter.scopeOf("org.babblelang.engine.impl.natives.java.JavaMethod"));

        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.Main"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.Repl"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.format.Formatter"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.compiler.Babblec"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.compiler.Debugging"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.ir.hir.HirPrinter"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.runtime.Launcher"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.engine.FileModuleSource"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.EXCLUDED,
                BatestCoverageReporter.scopeOf("org.babblelang.parser.BabbleParser"));
    }

    @Test
    public void testSyntheticReportGenerationAndUnionAndFailureExit(@TempDir Path tmp) throws Exception {
        Path reportDir = tmp.resolve("report");
        Files.createDirectories(reportDir);

        String interpXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="interpret">
                  <package name="org/babblelang/engine/impl">
                    <class name="org/babblelang/engine/impl/Interpreter" sourcefilename="Interpreter.java">
                      <method name="visitFile" desc="()V" line="10">
                        <counter type="LINE" missed="0" covered="2"/>
                        <counter type="BRANCH" missed="0" covered="2"/>
                      </method>
                    </class>
                    <sourcefile name="Interpreter.java">
                      <line nr="10" mi="0" ci="3" mb="0" cb="2"/>
                      <line nr="11" mi="0" ci="2" mb="0" cb="0"/>
                      <counter type="LINE" missed="0" covered="2"/>
                      <counter type="BRANCH" missed="0" covered="2"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java"/>
                    <sourcefile name="Operators.java">
                      <line nr="20" mi="0" ci="4" mb="1" cb="1"/>
                      <line nr="21" mi="3" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="3" covered="1"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        String compileXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="compile">
                  <package name="org/babblelang/compiler">
                    <class name="org/babblelang/compiler/CodeGenerator" sourcefilename="CodeGenerator.java"/>
                    <sourcefile name="CodeGenerator.java">
                      <line nr="5" mi="0" ci="2" mb="0" cb="0"/>
                      <counter type="LINE" missed="0" covered="1"/>
                      <counter type="BRANCH" missed="0" covered="0"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java"/>
                    <sourcefile name="Operators.java">
                      <line nr="20" mi="2" ci="0" mb="2" cb="0"/>
                      <line nr="21" mi="0" ci="3" mb="0" cb="2"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="2" covered="2"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        Path xmlI = tmp.resolve("jacoco-i.xml");
        Path xmlC = tmp.resolve("jacoco-c.xml");
        Files.writeString(xmlI, interpXml, StandardCharsets.UTF_8);
        Files.writeString(xmlC, compileXml, StandardCharsets.UTF_8);

        Files.writeString(reportDir.resolve("outcomes-interpret.jsonl"),
                "{\"file\":\"a.batest\",\"index\":1,\"line\":1,\"expected\":\"5\",\"kind\":\"OK\",\"text\":\"5\",\"passed\":true}\n",
                StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("outcomes-compile.jsonl"),
                "{\"file\":\"a.batest\",\"index\":1,\"line\":1,\"expected\":\"5\",\"kind\":\"OK\",\"text\":\"5\",\"passed\":true}\n",
                StandardCharsets.UTF_8);

        BatestCoverageReporter.run(reportDir, xmlI, xmlC, tmp.resolve("src"), "", "");
        String summary = Files.readString(reportDir.resolve("summary.json"), StandardCharsets.UTF_8);
        Assertions.assertTrue(summary.contains("\"sharedRuntimeCombined\": {\"linesCovered\": 2, \"linesTotal\": 2"), summary);
        Assertions.assertTrue(summary.contains("\"branchesCovered\": 3, \"branchesTotal\": 4"), summary);
        Assertions.assertTrue(Files.isRegularFile(reportDir.resolve("uncovered.txt")));

        // Verify branch threshold enforcement (sharedRuntimeCombined is 3/4 = 75.0% < 80.0%)
        Assertions.assertThrows(AssertionError.class,
                () -> BatestCoverageReporter.run(reportDir, xmlI, xmlC, tmp.resolve("src"), "", "", 80.0));

        // Verify failure triggers AssertionError
        Files.writeString(reportDir.resolve("outcomes-compile.jsonl"),
                "{\"file\":\"a.batest\",\"index\":1,\"line\":1,\"expected\":\"5\",\"kind\":\"OK\",\"text\":\"6\",\"passed\":false}\n",
                StandardCharsets.UTF_8);
        Assertions.assertThrows(AssertionError.class,
                () -> BatestCoverageReporter.run(reportDir, xmlI, xmlC, tmp.resolve("src"), "", ""));
    }

    @Test
    public void testThreeWayCompileO0IsolationAndParity(@TempDir Path tmp) throws Exception {
        Path reportDir = tmp.resolve("report3");
        Files.createDirectories(reportDir);

        String interpXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="interpret">
                  <package name="org/babblelang/engine/impl">
                    <class name="org/babblelang/engine/impl/Interpreter" sourcefilename="Interpreter.java"/>
                    <sourcefile name="Interpreter.java">
                      <line nr="10" mi="0" ci="2" mb="0" cb="2"/>
                      <counter type="LINE" missed="0" covered="1"/>
                      <counter type="BRANCH" missed="0" covered="2"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java"/>
                    <sourcefile name="Operators.java">
                      <line nr="20" mi="0" ci="2" mb="1" cb="1"/>
                      <line nr="21" mi="2" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="3" covered="1"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        String compileXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="compile">
                  <package name="org/babblelang/compiler">
                    <class name="org/babblelang/compiler/BabbleCompiler" sourcefilename="BabbleCompiler.java"/>
                    <sourcefile name="BabbleCompiler.java">
                      <line nr="5" mi="0" ci="2" mb="0" cb="2"/>
                      <line nr="6" mi="1" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="2" covered="2"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/opt">
                    <class name="org/babblelang/opt/PassManager" sourcefilename="PassManager.java"/>
                    <sourcefile name="PassManager.java">
                      <line nr="12" mi="0" ci="3" mb="1" cb="3"/>
                      <counter type="LINE" missed="0" covered="1"/>
                      <counter type="BRANCH" missed="1" covered="3"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java"/>
                    <sourcefile name="Operators.java">
                      <line nr="20" mi="2" ci="0" mb="2" cb="0"/>
                      <line nr="21" mi="2" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="2" covered="0"/>
                      <counter type="BRANCH" missed="4" covered="0"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        String compileO0Xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="compile-O0">
                  <package name="org/babblelang/compiler">
                    <class name="org/babblelang/compiler/BabbleCompiler" sourcefilename="BabbleCompiler.java"/>
                    <sourcefile name="BabbleCompiler.java">
                      <line nr="5" mi="2" ci="0" mb="2" cb="0"/>
                      <line nr="6" mi="0" ci="2" mb="0" cb="1"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="3" covered="1"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/opt">
                    <class name="org/babblelang/opt/PassManager" sourcefilename="PassManager.java"/>
                    <sourcefile name="PassManager.java">
                      <line nr="12" mi="3" ci="0" mb="4" cb="0"/>
                      <counter type="LINE" missed="1" covered="0"/>
                      <counter type="BRANCH" missed="4" covered="0"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java"/>
                    <sourcefile name="Operators.java">
                      <line nr="20" mi="2" ci="0" mb="2" cb="0"/>
                      <line nr="21" mi="0" ci="2" mb="0" cb="2"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="2" covered="2"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        String optXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="opt">
                  <package name="org/babblelang/opt/hir">
                    <class name="org/babblelang/opt/hir/ConstFoldPass" sourcefilename="ConstFoldPass.java"/>
                    <sourcefile name="ConstFoldPass.java">
                      <line nr="10" mi="0" ci="4" mb="0" cb="4"/>
                      <counter type="LINE" missed="0" covered="1"/>
                      <counter type="BRANCH" missed="0" covered="4"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        Path xmlI = tmp.resolve("jacoco-i.xml");
        Path xmlC = tmp.resolve("jacoco-c.xml");
        Path xmlC0 = tmp.resolve("jacoco-c0.xml");
        Path xmlOpt = tmp.resolve("jacoco-opt.xml");
        Files.writeString(xmlI, interpXml, StandardCharsets.UTF_8);
        Files.writeString(xmlC, compileXml, StandardCharsets.UTF_8);
        Files.writeString(xmlC0, compileO0Xml, StandardCharsets.UTF_8);
        Files.writeString(xmlOpt, optXml, StandardCharsets.UTF_8);

        String ok = "{\"file\":\"a.batest\",\"index\":1,\"line\":1,\"expected\":\"5\",\"kind\":\"OK\",\"text\":\"5\",\"passed\":true}\n";
        Files.writeString(reportDir.resolve("outcomes-interpret.jsonl"), ok, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("outcomes-compile.jsonl"), ok, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("outcomes-compile-O0.jsonl"), ok, StandardCharsets.UTF_8);

        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, tmp.resolve("src"), "", "", 0.0);
        String summary = Files.readString(reportDir.resolve("summary.json"), StandardCharsets.UTF_8);
        Assertions.assertTrue(summary.contains("\"compileO0Passed\": 1"), summary);
        Assertions.assertTrue(summary.contains("\"compiler\": {\"linesCovered\": 1, \"linesTotal\": 2, \"linePct\": 50.00, \"branchesCovered\": 1, \"branchesTotal\": 4"), summary);
        Assertions.assertTrue(summary.contains("\"optimizer\": {\"linesCovered\": 1, \"linesTotal\": 1, \"linePct\": 100.00, \"branchesCovered\": 3, \"branchesTotal\": 4"), summary);
        Assertions.assertTrue(summary.contains("\"sharedRuntimeCombined\": {\"linesCovered\": 2, \"linesTotal\": 2, \"linePct\": 100.00, \"branchesCovered\": 3, \"branchesTotal\": 4"), summary);

        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"), "", "", 0.0);
        String optSummary = Files.readString(reportDir.resolve("summary.json"), StandardCharsets.UTF_8);
        Assertions.assertTrue(optSummary.contains("\"optimizer\": {\"linesCovered\": 1, \"linesTotal\": 1, \"linePct\": 100.00, \"branchesCovered\": 4, \"branchesTotal\": 4"), optSummary);

        // Verify compile -O0 parity mismatch triggers AssertionError
        Files.writeString(reportDir.resolve("outcomes-compile-O0.jsonl"),
                "{\"file\":\"a.batest\",\"index\":1,\"line\":1,\"expected\":\"5\",\"kind\":\"OK\",\"text\":\"7\",\"passed\":false}\n",
                StandardCharsets.UTF_8);
        Assertions.assertThrows(AssertionError.class,
                () -> BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"), "", "", 0.0));
    }

    @Test
    public void testImpactScoreBranchesNeededAndOptionParsing() {
        // Zero-safe product (missedBr+1)*(missedLn+1)-1 avoids collapsing 0-line or 0-branch files to 0
        Assertions.assertEquals(0L, BatestCoverageReporter.impactScore(
                new BatestCoverageReporter.Counter(0, 10), new BatestCoverageReporter.Counter(0, 10)));
        Assertions.assertEquals(30L, BatestCoverageReporter.impactScore(
                new BatestCoverageReporter.Counter(30, 10), new BatestCoverageReporter.Counter(0, 10)));
        Assertions.assertEquals(15L, BatestCoverageReporter.impactScore(
                new BatestCoverageReporter.Counter(0, 0), new BatestCoverageReporter.Counter(15, 5)));
        Assertions.assertEquals(230L, BatestCoverageReporter.impactScore(
                new BatestCoverageReporter.Counter(10, 5), new BatestCoverageReporter.Counter(20, 5)));

        // Exact branch deficit math
        Assertions.assertEquals(0, BatestCoverageReporter.branchesNeeded(
                new BatestCoverageReporter.Counter(25, 75), 75.0));
        Assertions.assertEquals(1, BatestCoverageReporter.branchesNeeded(
                new BatestCoverageReporter.Counter(26, 74), 75.0));
        Assertions.assertEquals(179, BatestCoverageReporter.branchesNeeded(
                new BatestCoverageReporter.Counter(268, 91), 75.0));
        Assertions.assertEquals(0, BatestCoverageReporter.branchesNeeded(
                new BatestCoverageReporter.Counter(10, 0), 0.0));
        Assertions.assertEquals(0, BatestCoverageReporter.branchesNeeded(
                new BatestCoverageReporter.Counter(0, 0), 75.0));

        // SortMode and top parsing
        Assertions.assertEquals(BatestCoverageReporter.SortMode.SCORE, BatestCoverageReporter.SortMode.parse(""));
        Assertions.assertEquals(BatestCoverageReporter.SortMode.SCORE, BatestCoverageReporter.SortMode.parse("score"));
        Assertions.assertEquals(BatestCoverageReporter.SortMode.BRANCHES, BatestCoverageReporter.SortMode.parse("branches"));
        Assertions.assertEquals(BatestCoverageReporter.SortMode.LINES, BatestCoverageReporter.SortMode.parse("lines"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestCoverageReporter.SortMode.parse("invalid"));

        Assertions.assertEquals(10, BatestCoverageReporter.parseTop(""));
        Assertions.assertEquals(0, BatestCoverageReporter.parseTop("all"));
        Assertions.assertEquals(25, BatestCoverageReporter.parseTop("25"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestCoverageReporter.parseTop("-1"));
    }

    @Test
    public void testPerScopeRankingSortModesMethodMergingAndMultiInspect(@TempDir Path tmp) throws Exception {
        Path reportDir = tmp.resolve("report-ranked");
        Files.createDirectories(reportDir);

        // Interpreter: 1 uncovered class (Interpreter) + 1 branchless class with missed lines (TailCall)
        // Shared Runtime: Operators has method `add` 100% covered in interpret & 0% covered in compile,
        // and `sub` uncovered in both — verifies combineShared merges 100%-covered methods accurately.
        String interpXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="interpret">
                  <package name="org/babblelang/engine/impl">
                    <class name="org/babblelang/engine/impl/Interpreter" sourcefilename="Interpreter.java">
                      <method name="visitSmall" desc="()V" line="10">
                        <counter type="LINE" missed="1" covered="5"/>
                        <counter type="BRANCH" missed="1" covered="5"/>
                      </method>
                      <method name="visitBig" desc="()V" line="30">
                        <counter type="LINE" missed="12" covered="2"/>
                        <counter type="BRANCH" missed="10" covered="2"/>
                      </method>
                    </class>
                    <class name="org/babblelang/engine/impl/TailCall" sourcefilename="TailCall.java">
                      <method name="&lt;init&gt;" desc="()V" line="9">
                        <counter type="LINE" missed="5" covered="0"/>
                      </method>
                    </class>
                    <sourcefile name="Interpreter.java">
                      <line nr="10" mi="0" ci="2" mb="1" cb="1"/>
                      <line nr="30" mi="2" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="13" covered="7"/>
                      <counter type="BRANCH" missed="11" covered="7"/>
                    </sourcefile>
                    <sourcefile name="TailCall.java">
                      <line nr="9" mi="3" ci="0" mb="0" cb="0"/>
                      <counter type="LINE" missed="5" covered="0"/>
                      <counter type="BRANCH" missed="0" covered="0"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java">
                      <method name="add" desc="(II)I" line="10">
                        <counter type="LINE" missed="0" covered="4"/>
                        <counter type="BRANCH" missed="0" covered="2"/>
                      </method>
                      <method name="sub" desc="(II)I" line="20">
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="BRANCH" missed="2" covered="0"/>
                      </method>
                    </class>
                    <sourcefile name="Operators.java">
                      <line nr="10" mi="0" ci="4" mb="0" cb="2"/>
                      <line nr="20" mi="4" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="1" covered="1"/>
                      <counter type="BRANCH" missed="2" covered="2"/>
                    </sourcefile>
                  </package>
                </report>
                """;

        // Compiler (-O0): 3 classes to test outlier resistance and all 3 sort modes:
        //   - TinyOutlier (0% branches: 2 missed br, 2 missed ln -> score 8)
        //   - BalancedBig (80% branches: 10 missed br, 10 missed ln -> score 120)
        //   - BranchHeavy (25 missed br, 1 missed ln -> score 51)
        //   - LineHeavy   (1 missed br, 25 missed ln -> score 51)
        String compileO0Xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="compile-O0">
                  <package name="org/babblelang/compiler">
                    <class name="org/babblelang/compiler/TinyOutlier" sourcefilename="TinyOutlier.java"/>
                    <class name="org/babblelang/compiler/BalancedBig" sourcefilename="BalancedBig.java"/>
                    <class name="org/babblelang/compiler/BranchHeavy" sourcefilename="BranchHeavy.java"/>
                    <class name="org/babblelang/compiler/LineHeavy" sourcefilename="LineHeavy.java"/>
                    <sourcefile name="TinyOutlier.java">
                      <line nr="5" mi="2" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="2" covered="0"/>
                      <counter type="BRANCH" missed="2" covered="0"/>
                    </sourcefile>
                    <sourcefile name="BalancedBig.java">
                      <line nr="15" mi="2" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="10" covered="40"/>
                      <counter type="BRANCH" missed="10" covered="40"/>
                    </sourcefile>
                    <sourcefile name="BranchHeavy.java">
                      <line nr="25" mi="1" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="1" covered="49"/>
                      <counter type="BRANCH" missed="25" covered="25"/>
                    </sourcefile>
                    <sourcefile name="LineHeavy.java">
                      <line nr="35" mi="2" ci="0" mb="1" cb="1"/>
                      <counter type="LINE" missed="25" covered="25"/>
                      <counter type="BRANCH" missed="1" covered="49"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java">
                      <method name="add" desc="(II)I" line="10">
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="BRANCH" missed="2" covered="0"/>
                      </method>
                      <method name="sub" desc="(II)I" line="20">
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="BRANCH" missed="2" covered="0"/>
                      </method>
                    </class>
                    <sourcefile name="Operators.java">
                      <line nr="10" mi="4" ci="0" mb="2" cb="0"/>
                      <line nr="20" mi="4" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="2" covered="0"/>
                      <counter type="BRANCH" missed="4" covered="0"/>
                    </sourcefile>
                  </package>
                </report>
                """;

        // Compile (-O1): FrontEnd class (100% covered) + Optimizer class (uncovered)
        String compileXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="compile">
                  <package name="org/babblelang/analysis">
                    <class name="org/babblelang/analysis/TypeChecker" sourcefilename="TypeChecker.java"/>
                    <sourcefile name="TypeChecker.java">
                      <line nr="10" mi="0" ci="5" mb="0" cb="4"/>
                      <counter type="LINE" missed="0" covered="1"/>
                      <counter type="BRANCH" missed="0" covered="4"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/runtime">
                    <class name="org/babblelang/runtime/Operators" sourcefilename="Operators.java">
                      <method name="add" desc="(II)I" line="10">
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="BRANCH" missed="2" covered="0"/>
                      </method>
                      <method name="sub" desc="(II)I" line="20">
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="BRANCH" missed="2" covered="0"/>
                      </method>
                    </class>
                    <sourcefile name="Operators.java">
                      <line nr="10" mi="4" ci="0" mb="2" cb="0"/>
                      <line nr="20" mi="4" ci="0" mb="2" cb="0"/>
                      <counter type="LINE" missed="2" covered="0"/>
                      <counter type="BRANCH" missed="4" covered="0"/>
                    </sourcefile>
                  </package>
                </report>
                """;

        String optXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="opt">
                  <package name="org/babblelang/opt">
                    <class name="org/babblelang/opt/PassManager" sourcefilename="PassManager.java">
                      <method name="optimize" desc="()V" line="50">
                        <counter type="LINE" missed="0" covered="5"/>
                        <counter type="BRANCH" missed="6" covered="4"/>
                      </method>
                    </class>
                    <sourcefile name="PassManager.java">
                      <line nr="50" mi="0" ci="5" mb="6" cb="4"/>
                      <counter type="LINE" missed="0" covered="5"/>
                      <counter type="BRANCH" missed="6" covered="4"/>
                    </sourcefile>
                  </package>
                </report>
                """;

        Path xmlI = tmp.resolve("i.xml");
        Path xmlC = tmp.resolve("c.xml");
        Path xmlC0 = tmp.resolve("c0.xml");
        Path xmlOpt = tmp.resolve("opt.xml");
        Files.writeString(xmlI, interpXml, StandardCharsets.UTF_8);
        Files.writeString(xmlC, compileXml, StandardCharsets.UTF_8);
        Files.writeString(xmlC0, compileO0Xml, StandardCharsets.UTF_8);
        Files.writeString(xmlOpt, optXml, StandardCharsets.UTF_8);

        String ok = "{\"file\":\"a.batest\",\"index\":1,\"line\":1,\"expected\":\"5\",\"kind\":\"OK\",\"text\":\"5\",\"passed\":true}\n";
        Files.writeString(reportDir.resolve("outcomes-interpret.jsonl"), ok, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("outcomes-compile.jsonl"), ok, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("outcomes-compile-O0.jsonl"), ok, StandardCharsets.UTF_8);

        // 1. Default SCORE sort with top=2 per scope and multi-class inspect
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "TailCall,PassManager", 0.0, 2, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(buf, true, StandardCharsets.UTF_8));
        String console = buf.toString(StandardCharsets.UTF_8);

        // Per-scope sections exist for all 5 buckets
        Assertions.assertTrue(console.contains("-- Interpreter (impl.*) :"), console);
        Assertions.assertTrue(console.contains("-- Compiler (-O0) :"), console);
        Assertions.assertTrue(console.contains("-- Optimizer (-O1) :"), console);
        Assertions.assertTrue(console.contains("-- Shared Front-End (analysis) :"), console);
        Assertions.assertTrue(console.contains("-- Shared Runtime (combined) :"), console);
        Assertions.assertTrue(console.contains("(100% covered - no missed lines or branches)"), console);

        // Outlier resistance: BalancedBig (score 120) ranks #1 ahead of BranchHeavy (score 51);
        // TinyOutlier (0%, score 8) is truncated past top=2!
        int idxBalanced = console.indexOf("compiler.BalancedBig");
        int idxBranchHeavy = console.indexOf("compiler.BranchHeavy");
        Assertions.assertTrue(idxBalanced > 0 && idxBranchHeavy > idxBalanced, console);
        Assertions.assertFalse(console.contains("compiler.TinyOutlier"), console);
        Assertions.assertTrue(console.contains("... and 2 more uncovered files (3 missed br, 27 missed ln)"), console);

        // Branchless class (TailCall) is included with n/a branch pct; Interpreter's methods ranked visitBig before visitSmall
        Assertions.assertTrue(console.contains("engine.impl.TailCall") && console.contains("(   n/a)"), console);
        Assertions.assertTrue(console.contains("visitBig:L30(10br/12ln), visitSmall:L10(1br/1ln)"), console);

        // Partial-branch-only class (PassManager: 6 missed br, 0 missed ln) is included (not collapsed to 0)
        Assertions.assertTrue(console.contains("opt.PassManager") && console.contains("optimize:L50(6br)"), console);

        // Shared runtime method merging: `add` was 100% covered in interpret, so ONLY `sub` appears as uncovered!
        Assertions.assertTrue(console.contains("runtime.Operators") && console.contains("sub:L20(2br/4ln)"), console);
        Assertions.assertFalse(console.contains("add:L10"), console);

        // Multi-class inspect prints both requested classes
        Assertions.assertTrue(console.contains("=== INSPECT: TailCall ==="), console);
        Assertions.assertTrue(console.contains("=== INSPECT: PassManager ==="), console);

        // uncovered.txt is grouped by scope and ranks BalancedBig before BranchHeavy, LineHeavy, TinyOutlier
        String uncoveredTxt = Files.readString(reportDir.resolve("uncovered.txt"), StandardCharsets.UTF_8);
        Assertions.assertTrue(uncoveredTxt.contains("# SCOPE: Compiler (-O0)"), uncoveredTxt);
        Assertions.assertTrue(uncoveredTxt.indexOf("=== #1 org.babblelang.compiler.BalancedBig") <
                uncoveredTxt.indexOf("=== #2 org.babblelang.compiler.BranchHeavy"), uncoveredTxt);
        Assertions.assertFalse(uncoveredTxt.contains("    add "), uncoveredTxt);

        // summary.json contains hotspotsByScope
        String summaryJson = Files.readString(reportDir.resolve("summary.json"), StandardCharsets.UTF_8);
        Assertions.assertTrue(summaryJson.contains("\"hotspotsByScope\":"), summaryJson);
        Assertions.assertTrue(summaryJson.contains("\"compiler\": ["), summaryJson);

        // 2. SortMode.BRANCHES ranks BranchHeavy #1 (25 missed br)
        ByteArrayOutputStream bufBr = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 0.0, 0, BatestCoverageReporter.SortMode.BRANCHES,
                new PrintStream(bufBr, true, StandardCharsets.UTF_8));
        String consoleBr = bufBr.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleBr.indexOf("compiler.BranchHeavy") < consoleBr.indexOf("compiler.BalancedBig"), consoleBr);
        Assertions.assertTrue(consoleBr.contains("compiler.TinyOutlier"), consoleBr);

        // 3. SortMode.LINES ranks LineHeavy #1 (25 missed ln)
        ByteArrayOutputStream bufLn = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 0.0, 0, BatestCoverageReporter.SortMode.LINES,
                new PrintStream(bufLn, true, StandardCharsets.UTF_8));
        String consoleLn = bufLn.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleLn.indexOf("compiler.LineHeavy") < consoleLn.indexOf("compiler.BalancedBig"), consoleLn);

        // 4. Gate column shows PASS and NEED +N br when minBranchPct > 0
        ByteArrayOutputStream bufGate = new ByteArrayOutputStream();
        Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 75.0, 2, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufGate, true, StandardCharsets.UTF_8)));
        String consoleGate = bufGate.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleGate.contains("Gate (75.0%)"), consoleGate);
        Assertions.assertTrue(consoleGate.contains("PASS"), consoleGate);
        Assertions.assertTrue(consoleGate.contains("NEED +"), consoleGate);
    }
}
