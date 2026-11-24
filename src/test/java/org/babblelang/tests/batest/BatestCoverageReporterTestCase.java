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
        Assertions.assertEquals(BatestCoverageReporter.Scope.OPTIMIZER,
                BatestCoverageReporter.scopeOf("org.babblelang.ir.egraph.AlgebraicRules"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.OPTIMIZER,
                BatestCoverageReporter.scopeOf("org.babblelang.ir.egraph.ENode"));

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
        Assertions.assertTrue(consoleGate.contains("Gate (75.0% br)"), consoleGate);
        Assertions.assertTrue(consoleGate.contains("PASS"), consoleGate);
        Assertions.assertTrue(consoleGate.contains("NEED +"), consoleGate);
    }

    @Test
    public void testLinesAndBranchesNeededEdgeCasesAndDefaults() {
        Assertions.assertEquals(80.0, BatestCoverageReporter.DEFAULT_MIN_LINE_PCT);
        Assertions.assertEquals(80.0, BatestCoverageReporter.DEFAULT_MIN_BRANCH_PCT);

        int[][] cases = {
                // {missed, covered, minPctTimes10, expectedNeeded}
                {0, 0, 800, 0},
                {10, 0, 0, 0},
                {10, 0, -50, 0},
                {1, 4, 800, 0},       // 4/5 = 80.0%
                {20, 80, 800, 0},     // 80/100 = 80.0%
                {19, 81, 800, 0},     // 81/100 = 81.0%
                {0, 10, 800, 0},      // 100.0%
                {2001, 7999, 800, 1}, // 7999/10000 = 79.99%
                {2, 3, 800, 1},       // 3/5 = 60.0%
                {3, 0, 800, 3},       // 0/3 -> ceil(2.4) = 3
                {2, 5, 800, 1},       // 5/7 -> ceil(5.6) = 6
                {4, 2, 800, 3},       // 2/6 -> ceil(4.8) = 5
                {10, 3, 800, 8},      // 3/13 -> ceil(10.4) = 11
                {1, 3, 800, 1},       // 3/4 = 75.0% -> ceil(3.2) = 4
                {1, 0, 800, 1}        // 0/1 -> ceil(0.8) = 1
        };
        for (int[] c : cases) {
            BatestCoverageReporter.Counter counter = new BatestCoverageReporter.Counter(c[0], c[1]);
            double minPct = c[2] / 10.0;
            int expected = c[3];
            Assertions.assertEquals(expected, BatestCoverageReporter.linesNeeded(counter, minPct),
                    "linesNeeded failed for " + counter + " @ " + minPct);
            Assertions.assertEquals(expected, BatestCoverageReporter.branchesNeeded(counter, minPct),
                    "branchesNeeded failed for " + counter + " @ " + minPct);
        }
    }

    @Test
    public void testDualGateLineOnlyBranchOnlyAndCombinedFailures(@TempDir Path tmp) throws Exception {
        Path reportDir = tmp.resolve("report-dual");
        writePassingOutcomes(reportDir);
        Path xmlC = tmp.resolve("c.xml");
        Path xmlC0 = tmp.resolve("c0.xml");
        Path xmlOpt = tmp.resolve("opt.xml");
        Files.writeString(xmlC, xmlWithSingleClass("compile", "org/babblelang/analysis", "TypeChecker", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Files.writeString(xmlC0, xmlWithSingleClass("compile-O0", "org/babblelang/compiler", "BabbleCompiler", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Files.writeString(xmlOpt, xmlWithSingleClass("opt", "org/babblelang/opt", "PassManager", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Path xmlI = tmp.resolve("i.xml");

        // 1. Line-only failure: lines 3/5 (60.0%), branches 4/5 (80.0%)
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 2, 3, 1, 4),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufLn = new ByteArrayOutputStream();
        AssertionError errLn = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufLn, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(errLn.getMessage().contains("Line coverage below 80.0% threshold :"), errLn.getMessage());
        Assertions.assertTrue(errLn.getMessage().contains("Interpreter (impl.*)=60.0% (3/5, NEED +1 ln)"), errLn.getMessage());
        Assertions.assertFalse(errLn.getMessage().contains("Branch coverage below"), errLn.getMessage());
        String consoleLn = bufLn.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleLn.contains("Gate (80.0% ln/br)"), consoleLn);
        Assertions.assertTrue(consoleLn.contains("NEED +1 ln"), consoleLn);
        Assertions.assertTrue(consoleLn.contains("PASS"), consoleLn);
        Assertions.assertTrue(consoleLn.contains("[NEED +1 ln]"), consoleLn);
        Assertions.assertTrue(consoleLn.contains("-Dbatest.minLinePct=0 -Dbatest.minBranchPct=0"), consoleLn);
        Assertions.assertFalse(consoleLn.contains("NEED +1 ln, +"), consoleLn);
        Assertions.assertFalse(consoleLn.contains(" br]"), consoleLn);
        String uncoveredLn = Files.readString(reportDir.resolve("uncovered.txt"), StandardCharsets.UTF_8);
        Assertions.assertTrue(uncoveredLn.contains("NEED +1 ln for 80.0%"), uncoveredLn);
        Assertions.assertFalse(uncoveredLn.contains("br for 80.0%"), uncoveredLn);

        // 2. Branch-only failure: lines 4/5 (80.0%), branches 3/5 (60.0%)
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 1, 4, 2, 3),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufBr = new ByteArrayOutputStream();
        AssertionError errBr = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufBr, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(errBr.getMessage().contains("Branch coverage below 80.0% threshold :"), errBr.getMessage());
        Assertions.assertTrue(errBr.getMessage().contains("Interpreter (impl.*)=60.0% (3/5, NEED +1 br)"), errBr.getMessage());
        Assertions.assertFalse(errBr.getMessage().contains("Line coverage below"), errBr.getMessage());
        String consoleBr = bufBr.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleBr.contains("NEED +1 br"), consoleBr);
        Assertions.assertTrue(consoleBr.contains("[NEED +1 br]"), consoleBr);
        Assertions.assertFalse(consoleBr.contains("NEED +1 ln"), consoleBr);
        Assertions.assertFalse(consoleBr.contains(" ln]"), consoleBr);
        String uncoveredBr = Files.readString(reportDir.resolve("uncovered.txt"), StandardCharsets.UTF_8);
        Assertions.assertTrue(uncoveredBr.contains("NEED +1 br for 80.0%"), uncoveredBr);
        Assertions.assertFalse(uncoveredBr.contains("ln for 80.0%"), uncoveredBr);

        // 3. Simultaneous line + branch failure: lines 3/5 (60.0%), branches 2/5 (40.0%)
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 2, 3, 3, 2),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufBoth = new ByteArrayOutputStream();
        AssertionError errBoth = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufBoth, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(errBoth.getMessage().contains("Line coverage below 80.0% threshold :"), errBoth.getMessage());
        Assertions.assertTrue(errBoth.getMessage().contains("Interpreter (impl.*)=60.0% (3/5, NEED +1 ln)"), errBoth.getMessage());
        Assertions.assertTrue(errBoth.getMessage().contains("Branch coverage below 80.0% threshold :"), errBoth.getMessage());
        Assertions.assertTrue(errBoth.getMessage().contains("Interpreter (impl.*)=40.0% (2/5, NEED +2 br)"), errBoth.getMessage());
        String consoleBoth = bufBoth.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleBoth.contains("NEED +1 ln, +2 br"), consoleBoth);
        Assertions.assertTrue(consoleBoth.contains("[NEED +2 br]"), consoleBoth);
        Assertions.assertTrue(consoleBoth.contains("[NEED +1 ln]"), consoleBoth);
        String uncoveredBoth = Files.readString(reportDir.resolve("uncovered.txt"), StandardCharsets.UTF_8);
        Assertions.assertTrue(uncoveredBoth.contains("NEED +2 br for 80.0%"), uncoveredBoth);
        Assertions.assertTrue(uncoveredBoth.contains("NEED +1 ln for 80.0%"), uncoveredBoth);

        // 4. Unequal line/branch thresholds header check: 85.0% ln, 75.0% br
        ByteArrayOutputStream bufUnequal = new ByteArrayOutputStream();
        Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 85.0, 75.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufUnequal, true, StandardCharsets.UTF_8)));
        String consoleUnequal = bufUnequal.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleUnequal.contains("Gate (85.0% ln, 75.0% br)"), consoleUnequal);
    }

    @Test
    public void testDualGateExactBoundary80PctAnd7999Pct(@TempDir Path tmp) throws Exception {
        Path reportDir = tmp.resolve("report-boundary");
        writePassingOutcomes(reportDir);
        Path xmlI = tmp.resolve("i.xml");
        Path xmlC = tmp.resolve("c.xml");
        Path xmlC0 = tmp.resolve("c0.xml");
        Path xmlOpt = tmp.resolve("opt.xml");
        Files.writeString(xmlC, xmlWithSingleClass("compile", "org/babblelang/analysis", "TypeChecker", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Files.writeString(xmlOpt, xmlWithSingleClass("opt", "org/babblelang/opt", "PassManager", 0, 5, 0, 5),
                StandardCharsets.UTF_8);

        // 1. Exact 80.0% boundary PASS (4/5 and 8000/10000)
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 1, 4, 1, 4),
                StandardCharsets.UTF_8);
        Files.writeString(xmlC0, xmlWithSingleClass("compile-O0", "org/babblelang/compiler", "BabbleCompiler",
                2000, 8000, 2000, 8000), StandardCharsets.UTF_8);
        ByteArrayOutputStream bufPass = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufPass, true, StandardCharsets.UTF_8));
        String consolePass = bufPass.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consolePass.contains("Gate (80.0% ln/br)"), consolePass);
        Assertions.assertTrue(consolePass.contains("PASS"), consolePass);
        Assertions.assertFalse(consolePass.contains("NEED +"), consolePass);

        // 2. 79.99% line FAIL (7999/10000 lines, 8000/10000 branches)
        Files.writeString(xmlC0, xmlWithSingleClass("compile-O0", "org/babblelang/compiler", "BabbleCompiler",
                2001, 7999, 2000, 8000), StandardCharsets.UTF_8);
        ByteArrayOutputStream bufFailLn = new ByteArrayOutputStream();
        AssertionError errLn = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufFailLn, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(errLn.getMessage().contains("Line coverage below 80.0% threshold :"), errLn.getMessage());
        Assertions.assertTrue(errLn.getMessage().contains("(7999/10000, NEED +1 ln)"), errLn.getMessage());
        Assertions.assertFalse(errLn.getMessage().contains("Branch coverage below"), errLn.getMessage());
        Assertions.assertTrue(bufFailLn.toString(StandardCharsets.UTF_8).contains("NEED +1 ln"));

        // 3. 79.99% branch FAIL (8000/10000 lines, 7999/10000 branches)
        Files.writeString(xmlC0, xmlWithSingleClass("compile-O0", "org/babblelang/compiler", "BabbleCompiler",
                2000, 8000, 2001, 7999), StandardCharsets.UTF_8);
        ByteArrayOutputStream bufFailBr = new ByteArrayOutputStream();
        AssertionError errBr = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufFailBr, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(errBr.getMessage().contains("Branch coverage below 80.0% threshold :"), errBr.getMessage());
        Assertions.assertTrue(errBr.getMessage().contains("(7999/10000, NEED +1 br)"), errBr.getMessage());
        Assertions.assertFalse(errBr.getMessage().contains("Line coverage below"), errBr.getMessage());
        Assertions.assertTrue(bufFailBr.toString(StandardCharsets.UTF_8).contains("NEED +1 br"));
    }

    @Test
    public void testZeroThresholdAndFilterBypass(@TempDir Path tmp) throws Exception {
        Path reportDir = tmp.resolve("report-bypass");
        writePassingOutcomes(reportDir);
        Path xmlI = tmp.resolve("i.xml");
        Path xmlC = tmp.resolve("c.xml");
        Path xmlC0 = tmp.resolve("c0.xml");
        Path xmlOpt = tmp.resolve("opt.xml");
        Files.writeString(xmlC, xmlWithSingleClass("compile", "org/babblelang/analysis", "TypeChecker", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Files.writeString(xmlC0, xmlWithSingleClass("compile-O0", "org/babblelang/compiler", "BabbleCompiler", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Files.writeString(xmlOpt, xmlWithSingleClass("opt", "org/babblelang/opt", "PassManager", 0, 5, 0, 5),
                StandardCharsets.UTF_8);

        // 1. Both 0.0: disabled even on low coverage (lines 3/5, branches 2/5)
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 2, 3, 3, 2),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufOff = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 0.0, 0.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufOff, true, StandardCharsets.UTF_8));
        String consoleOff = bufOff.toString(StandardCharsets.UTF_8);
        Assertions.assertFalse(consoleOff.contains("Gate ("), consoleOff);
        Assertions.assertFalse(consoleOff.contains("NEED +"), consoleOff);

        // 2. Branch-only gate (0.0, 80.0): passes when branches >= 80% even if lines < 80%
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 2, 3, 1, 4),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufBrPass = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 0.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufBrPass, true, StandardCharsets.UTF_8));
        String consoleBrPass = bufBrPass.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleBrPass.contains("Gate (80.0% br)"), consoleBrPass);
        Assertions.assertTrue(consoleBrPass.contains("PASS"), consoleBrPass);
        Assertions.assertFalse(consoleBrPass.contains("NEED +"), consoleBrPass);

        // Branch-only gate fails only on branches when both are low
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 2, 3, 3, 2),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufBrFail = new ByteArrayOutputStream();
        AssertionError errBrOnly = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 0.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufBrFail, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(errBrOnly.getMessage().contains("Branch coverage below 80.0% threshold :"), errBrOnly.getMessage());
        Assertions.assertFalse(errBrOnly.getMessage().contains("Line coverage below"), errBrOnly.getMessage());
        String consoleBrFail = bufBrFail.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleBrFail.contains("NEED +2 br"), consoleBrFail);
        Assertions.assertFalse(consoleBrFail.contains("NEED +1 ln"), consoleBrFail);
        Assertions.assertFalse(consoleBrFail.contains(" ln]"), consoleBrFail);

        // 3. Line-only gate (80.0, 0.0): passes when lines >= 80% even if branches < 80%
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 1, 4, 3, 2),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufLnPass = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 0.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufLnPass, true, StandardCharsets.UTF_8));
        String consoleLnPass = bufLnPass.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleLnPass.contains("Gate (80.0% ln)"), consoleLnPass);
        Assertions.assertTrue(consoleLnPass.contains("PASS"), consoleLnPass);
        Assertions.assertFalse(consoleLnPass.contains("NEED +"), consoleLnPass);

        // Line-only gate fails only on lines when both are low
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 2, 3, 3, 2),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufLnFail = new ByteArrayOutputStream();
        AssertionError errLnOnly = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 0.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufLnFail, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(errLnOnly.getMessage().contains("Line coverage below 80.0% threshold :"), errLnOnly.getMessage());
        Assertions.assertFalse(errLnOnly.getMessage().contains("Branch coverage below"), errLnOnly.getMessage());
        String consoleLnFail = bufLnFail.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleLnFail.contains("NEED +1 ln"), consoleLnFail);
        Assertions.assertFalse(consoleLnFail.contains("NEED +2 br"), consoleLnFail);
        Assertions.assertFalse(consoleLnFail.contains(" br]"), consoleLnFail);

        // 4. Filter bypass disables both line and branch gates
        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 3, 2, 4, 1),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream bufFiltered = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "types/", "", 80.0, 80.0, 5, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufFiltered, true, StandardCharsets.UTF_8));
        String consoleFiltered = bufFiltered.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(consoleFiltered.contains("[FILTERED: types/]"), consoleFiltered);
        Assertions.assertFalse(consoleFiltered.contains("Gate ("), consoleFiltered);
        Assertions.assertFalse(consoleFiltered.contains("NEED +"), consoleFiltered);
    }

    @Test
    public void testIrEgraphAttributedToOptimizerScope(@TempDir Path tmp) throws Exception {
        Path reportDir = tmp.resolve("report-egraph");
        writePassingOutcomes(reportDir);
        Path xmlI = tmp.resolve("i.xml");
        Path xmlC = tmp.resolve("c.xml");
        Path xmlC0 = tmp.resolve("c0.xml");
        Path xmlOpt = tmp.resolve("opt.xml");

        Files.writeString(xmlI, xmlWithSingleClass("interpret", "org/babblelang/engine/impl", "Interpreter", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Files.writeString(xmlC, xmlWithSingleClass("compile", "org/babblelang/analysis", "TypeChecker", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        Files.writeString(xmlC0, xmlWithSingleClass("compile-O0", "org/babblelang/ir/hir", "HirBuilder", 0, 5, 0, 5),
                StandardCharsets.UTF_8);
        String optXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="opt">
                  <package name="org/babblelang/opt/hir">
                    <class name="org/babblelang/opt/hir/ConstFoldPass" sourcefilename="ConstFoldPass.java"/>
                    <sourcefile name="ConstFoldPass.java">
                      <line nr="10" mi="1" ci="4" mb="1" cb="4"/>
                      <counter type="LINE" missed="1" covered="4"/>
                      <counter type="BRANCH" missed="1" covered="4"/>
                    </sourcefile>
                  </package>
                  <package name="org/babblelang/ir/egraph">
                    <class name="org/babblelang/ir/egraph/EGraph" sourcefilename="EGraph.java"/>
                    <class name="org/babblelang/ir/egraph/AlgebraicRules" sourcefilename="AlgebraicRules.java"/>
                    <sourcefile name="EGraph.java">
                      <line nr="20" mi="1" ci="4" mb="3" cb="2"/>
                      <counter type="LINE" missed="1" covered="4"/>
                      <counter type="BRANCH" missed="3" covered="2"/>
                    </sourcefile>
                    <sourcefile name="AlgebraicRules.java">
                      <line nr="30" mi="3" ci="2" mb="3" cb="2"/>
                      <counter type="LINE" missed="3" covered="2"/>
                      <counter type="BRANCH" missed="3" covered="2"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        Files.writeString(xmlOpt, optXml, StandardCharsets.UTF_8);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BatestCoverageReporter.run(reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 0.0, 0.0, 10, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(buf, true, StandardCharsets.UTF_8));
        String console = buf.toString(StandardCharsets.UTF_8);
        String summary = Files.readString(reportDir.resolve("summary.json"), StandardCharsets.UTF_8);

        Assertions.assertTrue(summary.contains(
                "\"optimizer\": {\"linesCovered\": 10, \"linesTotal\": 15, \"linePct\": 66.67, \"branchesCovered\": 8, \"branchesTotal\": 15, \"branchPct\": 53.33"),
                summary);
        Assertions.assertTrue(summary.contains("\"org.babblelang.ir.egraph.EGraph\""), summary);
        Assertions.assertTrue(summary.contains("\"org.babblelang.ir.egraph.AlgebraicRules\""), summary);
        Assertions.assertTrue(summary.contains("\"scope\": \"OPTIMIZER\""), summary);
        Assertions.assertTrue(summary.contains(
                "\"compiler\": {\"linesCovered\": 5, \"linesTotal\": 5, \"linePct\": 100.00, \"branchesCovered\": 5, \"branchesTotal\": 5, \"branchPct\": 100.00"),
                summary);

        int optIdx = console.indexOf("-- Optimizer (-O1) :");
        Assertions.assertTrue(optIdx >= 0, console);
        Assertions.assertTrue(console.indexOf("ir.egraph.EGraph", optIdx) > optIdx, console);
        Assertions.assertTrue(console.indexOf("ir.egraph.AlgebraicRules", optIdx) > optIdx, console);

        ByteArrayOutputStream bufGate = new ByteArrayOutputStream();
        AssertionError err = Assertions.assertThrows(AssertionError.class, () -> BatestCoverageReporter.run(
                reportDir, xmlI, xmlC, xmlC0, xmlOpt, tmp.resolve("src"),
                "", "", 80.0, 80.0, 10, BatestCoverageReporter.SortMode.SCORE,
                new PrintStream(bufGate, true, StandardCharsets.UTF_8)));
        Assertions.assertTrue(err.getMessage().contains("Line coverage below 80.0% threshold :"), err.getMessage());
        Assertions.assertTrue(err.getMessage().contains("Optimizer (-O1)=66.7% (10/15, NEED +2 ln)"), err.getMessage());
        Assertions.assertTrue(err.getMessage().contains("Branch coverage below 80.0% threshold :"), err.getMessage());
        Assertions.assertTrue(err.getMessage().contains("Optimizer (-O1)=53.3% (8/15, NEED +4 br)"), err.getMessage());
        Assertions.assertTrue(bufGate.toString(StandardCharsets.UTF_8).contains("NEED +2 ln, +4 br"));
    }

    private static void writePassingOutcomes(Path reportDir) throws Exception {
        Files.createDirectories(reportDir);
        String ok = "{\"file\":\"a.batest.md\",\"index\":1,\"line\":1,\"name\":\"ok\",\"expected\":\"\",\"kind\":\"OK\",\"text\":\"\",\"passed\":true}\n";
        Files.writeString(reportDir.resolve("outcomes-interpret.jsonl"), ok, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("outcomes-compile.jsonl"), ok, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("outcomes-compile-O0.jsonl"), ok, StandardCharsets.UTF_8);
    }

    private static String xmlWithSingleClass(String reportName, String pkgPath, String className,
                                             int lineMissed, int lineCovered, int brMissed, int brCovered) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<report name=\"" + reportName + "\">\n"
                + "  <package name=\"" + pkgPath + "\">\n"
                + "    <class name=\"" + pkgPath + "/" + className + "\" sourcefilename=\"" + className + ".java\"/>\n"
                + "    <sourcefile name=\"" + className + ".java\">\n"
                + "      <line nr=\"10\" mi=\"" + (lineMissed > 0 ? 1 : 0) + "\" ci=\"" + (lineCovered > 0 ? 1 : 0)
                + "\" mb=\"" + brMissed + "\" cb=\"" + brCovered + "\"/>\n"
                + "      <counter type=\"LINE\" missed=\"" + lineMissed + "\" covered=\"" + lineCovered + "\"/>\n"
                + "      <counter type=\"BRANCH\" missed=\"" + brMissed + "\" covered=\"" + brCovered + "\"/>\n"
                + "    </sourcefile>\n"
                + "  </package>\n"
                + "</report>\n";
    }
}
