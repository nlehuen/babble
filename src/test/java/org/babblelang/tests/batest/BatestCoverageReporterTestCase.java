package org.babblelang.tests.batest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                BatestCoverageReporter.scopeOf("org.babblelang.runtime.Bootstrap"));
        Assertions.assertEquals(BatestCoverageReporter.Scope.COMPILER,
                BatestCoverageReporter.scopeOf("org.babblelang.runtime.IntCell"));

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

        // Verify failure triggers AssertionError
        Files.writeString(reportDir.resolve("outcomes-compile.jsonl"),
                "{\"file\":\"a.batest\",\"index\":1,\"line\":1,\"expected\":\"5\",\"kind\":\"OK\",\"text\":\"6\",\"passed\":false}\n",
                StandardCharsets.UTF_8);
        Assertions.assertThrows(AssertionError.class,
                () -> BatestCoverageReporter.run(reportDir, xmlI, xmlC, tmp.resolve("src"), "", ""));
    }
}
