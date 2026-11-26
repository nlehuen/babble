package org.babblelang.tests.batest;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.babblelang.analysis.CompilationTarget;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.MapModuleSource;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTier;
import org.babblelang.tests.NodeWasmRunner;
import org.babblelang.wasm.WasmCompiler;
import org.babblelang.wasm.WasmProgramLinker;
import org.babblelang.wasm.WatPrinter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 4th-mode WebAssembly 3.0 parity runner for {@code src/test/batest/**&#47;*.batest.md} ({@code
 * BIP025-I5}, {@code BIP025-D10}).
 *
 * <p>Automatically discovers every {@code .batest.md} case, lowers it to target-neutral HIR,
 * filters by {@link org.babblelang.wasm.WasmPortabilityChecker}, compiles portable cases to
 * verified Wasm 3.0 ({@code wasm-js}), executes them in {@link NodeWasmRunner} (V8), and checks
 * exact behavioral parity (stdout, stderr, normalized errors) plus {@code wat} IR blocks. Runs once
 * per Maven build during the {@code compiled-tests} ({@code -O1}) Surefire execution.
 */
public class WasmBatestTestCase {

    @TestFactory
    public Stream<DynamicNode> wasmBatestSuite() throws IOException {
        BabbleScriptEngine probe =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        if (probe.getMode() != BabbleScriptEngine.Mode.COMPILE
                || BabbleCompiler.resolveOptLevel(probe) != 1) {
            return Stream.empty();
        }
        if (!NodeWasmRunner.isNodeAvailable()) {
            return Stream.of(
                    DynamicTest.dynamicTest(
                            "Node.js runtime required for Wasm 4-mode parity",
                            () -> Assumptions.assumeTrue(false, "Node.js not available")));
        }

        String filterRaw = System.getProperty("batest.filter");
        BatestFilter filter = BatestFilter.parse(filterRaw);
        BatestShard shard = BatestShard.parse(System.getProperty("batest.shard"));
        Path root = BatestTestCase.BATEST_ROOT;
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files =
                    walk.filter(Files::isRegularFile)
                            .filter(BatestTestCase::isBatestFile)
                            .sorted(
                                    Comparator.comparing(
                                            p -> root.relativize(p).toString().replace('\\', '/')))
                            .toList();
        }

        List<DynamicNode> containers = new ArrayList<>();
        for (Path path : files) {
            String rel = root.relativize(path).toString().replace('\\', '/');
            if (!shard.matchesFile(rel) || !filter.matchesFile(rel)) {
                continue;
            }
            BatestFile batestFile = BatestParser.parse(path, rel);
            List<DynamicTest> caseTests = new ArrayList<>();
            for (BatestFile.BatestCase testCase : batestFile.cases()) {
                if (!filter.matches(batestFile, testCase) || testCase.session()) {
                    continue;
                }
                caseTests.add(
                        DynamicTest.dynamicTest(
                                testCase.displayName(),
                                path.toUri(),
                                () -> executeWasmCase(batestFile, testCase)));
            }
            if (!caseTests.isEmpty()) {
                containers.add(
                        DynamicContainer.dynamicContainer(rel, path.toUri(), caseTests.stream()));
            }
        }
        return containers.stream();
    }

    private static void executeWasmCase(BatestFile file, BatestFile.BatestCase testCase) {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        int activeOpt = BabbleCompiler.resolveOptLevel(engine);

        Map<String, String> effectiveModules = file.modules();
        if (!testCase.caseModules().isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(file.modules());
            merged.putAll(testCase.caseModules());
            effectiveModules = merged;
        }
        engine.put(BabbleScriptEngine.MODULE_SOURCE, new MapModuleSource(effectiveModules));

        StringWriter errWriter = new StringWriter();
        engine.getContext().setErrorWriter(errWriter);

        String source =
                file.preamble().isEmpty()
                        ? testCase.code()
                        : file.preamble() + "\n" + testCase.code();

        // 1. Lower to target-neutral HIR (front-end compile errors are identical to JVM)
        List<BabbleScriptEngine.HirProgramModule> hirMods;
        try {
            hirMods = engine.compileSourceToHir(source, "case.ba", CompilationTarget.WASM_JS);
        } catch (Throwable t) {
            List<String> warnings = BatestRunner.extractNormalizedWarnings(errWriter.toString());
            BatestRunner.CaseOutcome outcome = BatestRunner.classifyFailure(t, warnings, "");
            String behavioralError = BatestRunner.evaluateBehavioral(testCase, outcome);
            if (behavioralError != null) {
                Assertions.fail(
                        file.relativePath()
                                + " :: "
                                + testCase.displayName()
                                + " [wasm-frontend]\n"
                                + behavioralError);
            }
            return;
        }

        List<String> warnings = BatestRunner.extractNormalizedWarnings(errWriter.toString());

        // 2. Compile to WebAssembly 3.0 (wasm-js) — auto-skip non-portable JVM-interop cases
        List<WasmProgramLinker.ModuleInput> inputs =
                hirMods.stream()
                        .map(m ->
                                new WasmProgramLinker.ModuleInput(
                                        m.id().key(),
                                        m.id().displayName(),
                                        m.hir(),
                                        m.staticImportKeys()))
                        .toList();
        WasmCompiler.Options opts =
                new WasmCompiler.Options(CompilationTarget.WASM_JS, true, false, false, false);
        WasmCompiler.CompileResult compiled = WasmCompiler.compile(inputs, opts, "case.wasm");
        if (!compiled.ok()) {
            boolean expectsWat =
                    testCase.irExpectations().stream().anyMatch(e -> e.tier() == IrTier.WAT);
            if (expectsWat) {
                Assertions.fail(
                        file.relativePath()
                                + " :: "
                                + testCase.displayName()
                                + " declares wat expectations but failed Wasm portability : "
                                + compiled.portabilityErrors());
            }
            // Non-portable (uses java.*, Java interfaces, dynamic import, etc.) — skip cleanly
            return;
        }

        // 3. Execute in persistent Node.js V8 worker
        NodeWasmRunner.RunOutcome run =
                NodeWasmRunner.runJs(compiled.wasmBytes(), compiled.jsGlue(), List.of(), null);
        BatestRunner.CaseOutcome outcome = toCaseOutcome(run, warnings);

        String behavioralError = BatestRunner.evaluateBehavioral(testCase, outcome);
        if (behavioralError != null) {
            Assertions.fail(
                    file.relativePath()
                            + " :: "
                            + testCase.displayName()
                            + " [wasm-js -O"
                            + activeOpt
                            + "]\n"
                            + behavioralError);
        }

        // 4. Verify any `wat` IR blocks declared on this case (BIP025-I5, BIP029)
        List<BatestFile.IrExpectation> watExpectations =
                testCase.irExpectations().stream().filter(e -> e.tier() == IrTier.WAT).toList();
        if (!watExpectations.isEmpty()) {
            IrSnapshot watSnap =
                    new IrSnapshot(
                            IrTier.WAT,
                            null,
                            "case.wasm",
                            activeOpt,
                            IrSnapshot.STAGE_FINAL,
                            WatPrinter.asDumpable(compiled.module()));
            List<IrAssertionChecker.IrMismatch> mismatches =
                    IrAssertionChecker.checkAll(watExpectations, List.of(watSnap), activeOpt, 0);
            if (!mismatches.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(file.relativePath())
                        .append(" :: ")
                        .append(testCase.displayName())
                        .append(" [wat -O")
                        .append(activeOpt)
                        .append("]\n");
                for (IrAssertionChecker.IrMismatch m : mismatches) {
                    sb.append(m.message()).append('\n');
                }
                Assertions.fail(sb.toString());
            }
        }
    }

    private static BatestRunner.CaseOutcome toCaseOutcome(
            NodeWasmRunner.RunOutcome run, List<String> warnings) {
        String normOut = BatestRunner.normalizeStdout(run.stdout());
        List<String> allErrLines = new ArrayList<>(warnings);
        if (run.stderr() != null && !run.stderr().isBlank()) {
            String cleanStderr = run.stderr().replace("\r\n", "\n").replace('\r', '\n').strip();
            if (!cleanStderr.isEmpty()) {
                allErrLines.add(cleanStderr);
            }
        }
        String normErr = String.join("\n", allErrLines);
        if (run.ok()) {
            return new BatestRunner.CaseOutcome(
                    BatestRunner.Kind.OK, normOut, normOut, normErr, null, null);
        }
        String rawErr = run.error() != null ? run.error() : "Uncaught Babble exception";
        String normalizedErr;
        if (rawErr.contains("Maximum call stack size exceeded")) {
            normalizedErr = "Stack overflow";
        } else {
            normalizedErr =
                    BatestRunner.normalizeMessage(rawErr.replaceAll("mem:case\\.ba#[^, )]+", "T#"));
        }
        return new BatestRunner.CaseOutcome(
                BatestRunner.Kind.USER_ERROR, normalizedErr, normOut, normErr, normalizedErr, null);
    }
}
