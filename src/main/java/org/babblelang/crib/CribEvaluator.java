package org.babblelang.crib;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.babblelang.analysis.CompilationTarget;
import org.babblelang.batest.BatestFile;
import org.babblelang.batest.BatestFilter;
import org.babblelang.batest.BatestParser;
import org.babblelang.batest.BatestRunner;
import org.babblelang.batest.IrAssertionChecker;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.ExecutionInterruptedError;
import org.babblelang.engine.MapModuleSource;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTier;
import org.babblelang.wasm.WasmCompiler;
import org.babblelang.wasm.WasmProgramLinker;
import org.babblelang.wasm.WatPrinter;

/**
 * Evaluates a parsed {@link BatestFile} across requested {@link CribMode}s with per-(case, mode)
 * thread-group isolation, timeout watchdogs, IR assertion checks, and automatic cross-mode
 * differential parity diffing ({@code BIP041-D2}, {@code BIP041-D7}, {@code BIP041-D8}).
 */
public final class CribEvaluator {

    private static final AtomicInteger TRIAL_GROUP_SEQ = new AtomicInteger();
    private static final long INTERRUPT_GRACE_MS = 250L;
    private static final long STRAY_THREAD_GRACE_MS = 50L;
    private static volatile Boolean asmVerifyAvailable;

    public record EvalResult(CribProtocol.TrialResponse response, String poisonReason) {}

    private final WasmExecWorker wasmWorker;

    public CribEvaluator(WasmExecWorker wasmWorker) {
        this.wasmWorker = wasmWorker;
    }

    public static boolean isAsmVerifyAvailable() {
        Boolean cached = asmVerifyAvailable;
        if (cached != null) {
            return cached;
        }
        try {
            Class.forName(
                    "org.objectweb.asm.util.CheckClassAdapter",
                    false,
                    CribEvaluator.class.getClassLoader());
            asmVerifyAvailable = true;
        } catch (ClassNotFoundException | LinkageError e) {
            asmVerifyAvailable = false;
        }
        return asmVerifyAvailable;
    }

    /** Evaluates a {@link CribProtocol.TrialRequest} inside the current worker JVM. */
    public EvalResult evaluate(CribProtocol.TrialRequest req) {
        long startNanos = System.nanoTime();
        List<String> requestedLabels = req.modes().stream().map(CribMode::label).toList();

        BatestFile file;
        try {
            BatestParser.ParseOptions parseOpts =
                    req.strict()
                            ? BatestParser.ParseOptions.STRICT
                            : BatestParser.ParseOptions.RELAXED;
            file = BatestParser.parseString(req.batestContent(), req.sourceName(), parseOpts);
        } catch (IllegalArgumentException e) {
            long elapsedMs = nanosToMs(System.nanoTime() - startNanos);
            return new EvalResult(
                    new CribProtocol.TrialResponse(
                            false,
                            elapsedMs,
                            requestedLabels,
                            req.verify(),
                            req.parity(),
                            req.strict(),
                            false,
                            null,
                            e.getMessage(),
                            null,
                            List.of()),
                    null);
        }

        BatestFilter filter = BatestFilter.parse(req.filter());
        List<BatestFile.BatestCase> matchedCases = new ArrayList<>();
        for (BatestFile.BatestCase tc : file.cases()) {
            if (filter.matches(file, tc)) {
                matchedCases.add(tc);
            }
        }
        if (filter.isActive() && matchedCases.isEmpty()) {
            long elapsedMs = nanosToMs(System.nanoTime() - startNanos);
            return new EvalResult(
                    new CribProtocol.TrialResponse(
                            false,
                            elapsedMs,
                            requestedLabels,
                            req.verify(),
                            req.parity(),
                            req.strict(),
                            false,
                            null,
                            "Filter matched zero cases : " + req.filter(),
                            null,
                            List.of()),
                    null);
        }

        boolean needsAsmVerify =
                req.verify()
                        && req.modes().stream()
                                .anyMatch(m -> !m.isWasm() && m != CribMode.INTERPRET);
        if (needsAsmVerify && !isAsmVerifyAvailable()) {
            long elapsedMs = nanosToMs(System.nanoTime() - startNanos);
            return new EvalResult(
                    new CribProtocol.TrialResponse(
                            false,
                            elapsedMs,
                            requestedLabels,
                            req.verify(),
                            req.parity(),
                            req.strict(),
                            false,
                            null,
                            "verify=true requested but org.objectweb.asm.util.CheckClassAdapter (asm-util) is not on the classpath",
                            null,
                            List.of()),
                    null);
        }

        List<CribProtocol.CaseVerdict> caseVerdicts = new ArrayList<>(matchedCases.size());
        String poisonReason = null;
        int passedCases = 0;
        int failedCases = 0;
        int parityDivergences = 0;
        int internalCrashes = 0;
        int timeouts = 0;

        for (BatestFile.BatestCase testCase : matchedCases) {
            Map<String, CribProtocol.ModeVerdict> modeVerdicts = new LinkedHashMap<>();
            boolean casePassed = true;

            for (CribMode mode : req.modes()) {
                if (poisonReason != null && poisonReason.startsWith("Uninterruptible")) {
                    // An uninterruptible infinite loop is already spinning in this worker JVM;
                    // abort remaining modes promptly so the coordinator can destroyForcibly().
                    modeVerdicts.put(
                            mode.label(),
                            new CribProtocol.ModeVerdict(
                                    CribProtocol.STATUS_WORKER_CRASHED,
                                    null,
                                    "",
                                    "",
                                    "Skipped because an earlier mode left an uninterruptible timed-out thread",
                                    null,
                                    List.of(),
                                    null,
                                    0L));
                    casePassed = false;
                    continue;
                }

                ModeExecution exec = executeSingleMode(file, testCase, mode, req);
                CribProtocol.ModeVerdict verdict = exec.verdict();
                modeVerdicts.put(mode.label(), verdict);
                if (exec.poisonReason() != null && poisonReason == null) {
                    poisonReason = exec.poisonReason();
                }

                switch (verdict.status()) {
                    case CribProtocol.STATUS_PASS, CribProtocol.STATUS_SKIPPED_NON_PORTABLE -> {}
                    case CribProtocol.STATUS_INTERNAL_CRASH -> {
                        casePassed = false;
                        internalCrashes++;
                    }
                    case CribProtocol.STATUS_TIMEOUT -> {
                        casePassed = false;
                        timeouts++;
                    }
                    default -> casePassed = false;
                }
            }

            boolean parityOk = true;
            String parityDiff = null;
            if (req.parity()) {
                parityDiff = computeParityDiff(modeVerdicts);
                if (parityDiff != null) {
                    parityOk = false;
                    casePassed = false;
                    parityDivergences++;
                }
            }

            if (casePassed) {
                passedCases++;
            } else {
                failedCases++;
            }
            caseVerdicts.add(
                    new CribProtocol.CaseVerdict(
                            testCase.name(),
                            testCase.index(),
                            testCase.line(),
                            casePassed,
                            parityOk,
                            parityDiff,
                            modeVerdicts));
        }

        long elapsedMs = nanosToMs(System.nanoTime() - startNanos);
        boolean allOk = failedCases == 0;
        CribProtocol.TrialSummary summary =
                new CribProtocol.TrialSummary(
                        matchedCases.size(),
                        passedCases,
                        failedCases,
                        parityDivergences,
                        internalCrashes,
                        timeouts);
        CribProtocol.TrialResponse response =
                new CribProtocol.TrialResponse(
                        allOk,
                        elapsedMs,
                        requestedLabels,
                        req.verify(),
                        req.parity(),
                        req.strict(),
                        poisonReason != null,
                        poisonReason,
                        null,
                        summary,
                        caseVerdicts);
        return new EvalResult(response, poisonReason);
    }

    private record ModeExecution(CribProtocol.ModeVerdict verdict, String poisonReason) {}

    private ModeExecution executeSingleMode(
            BatestFile file,
            BatestFile.BatestCase testCase,
            CribMode mode,
            CribProtocol.TrialRequest req) {
        long startNanos = System.nanoTime();
        if (mode.isWasm()) {
            return executeWasmMode(file, testCase, mode, req, startNanos);
        }

        ThreadGroup group = new ThreadGroup("crib-trial-" + TRIAL_GROUP_SEQ.incrementAndGet());
        AtomicReference<CribProtocol.ModeVerdict> verdictRef = new AtomicReference<>();
        AtomicReference<String> poisonRef = new AtomicReference<>();
        ClassLoader baseTccl = CribEvaluator.class.getClassLoader();

        Thread execThread =
                new Thread(
                        group,
                        () -> {
                            Thread.currentThread().setContextClassLoader(baseTccl);
                            try {
                                verdictRef.set(runJvmMode(file, testCase, mode, req, startNanos));
                            } catch (ExecutionInterruptedError interrupted) {
                                long ms = nanosToMs(System.nanoTime() - startNanos);
                                verdictRef.set(
                                        new CribProtocol.ModeVerdict(
                                                CribProtocol.STATUS_TIMEOUT,
                                                null,
                                                "",
                                                "",
                                                "Timed out after " + req.timeoutMs() + "ms",
                                                null,
                                                List.of(),
                                                null,
                                                ms));
                            } catch (OutOfMemoryError oom) {
                                long ms = nanosToMs(System.nanoTime() - startNanos);
                                poisonRef.compareAndSet(null, "OutOfMemoryError during evaluation");
                                verdictRef.set(
                                        new CribProtocol.ModeVerdict(
                                                CribProtocol.STATUS_INTERNAL_CRASH,
                                                BatestRunner.Kind.INTERNAL_CRASH.name(),
                                                "",
                                                "",
                                                null,
                                                "INTERNAL_CRASH",
                                                List.of(),
                                                stackTrace(oom),
                                                ms));
                            } catch (Throwable t) {
                                long ms = nanosToMs(System.nanoTime() - startNanos);
                                verdictRef.set(
                                        new CribProtocol.ModeVerdict(
                                                CribProtocol.STATUS_INTERNAL_CRASH,
                                                BatestRunner.Kind.INTERNAL_CRASH.name(),
                                                "",
                                                "",
                                                null,
                                                "INTERNAL_CRASH",
                                                List.of(),
                                                stackTrace(t),
                                                ms));
                            } finally {
                                Thread.currentThread().setContextClassLoader(baseTccl);
                                // Clear interrupt status on the worker thread before exiting.
                                Thread.interrupted();
                            }
                        },
                        "crib-exec-" + mode.label());
        execThread.setDaemon(true);
        execThread.start();

        boolean timedOut = false;
        try {
            execThread.join(req.timeoutMs());
            if (execThread.isAlive()) {
                timedOut = true;
                execThread.interrupt();
                execThread.join(INTERRUPT_GRACE_MS);
                if (execThread.isAlive()) {
                    poisonRef.compareAndSet(
                            null,
                            "Uninterruptible timed-out thread in "
                                    + mode.label()
                                    + " after "
                                    + req.timeoutMs()
                                    + "ms");
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            execThread.interrupt();
            timedOut = true;
        }

        // Audit any stray threads spawned inside the trial's ThreadGroup.
        String strayPoison = auditAndCleanThreadGroup(group, execThread);
        if (strayPoison != null) {
            poisonRef.compareAndSet(null, strayPoison);
        }

        CribProtocol.ModeVerdict verdict = verdictRef.get();
        if (verdict == null
                || (timedOut && !CribProtocol.STATUS_TIMEOUT.equals(verdict.status()))) {
            long ms = nanosToMs(System.nanoTime() - startNanos);
            verdict =
                    new CribProtocol.ModeVerdict(
                            CribProtocol.STATUS_TIMEOUT,
                            null,
                            "",
                            "",
                            "Timed out after " + req.timeoutMs() + "ms",
                            null,
                            List.of(),
                            null,
                            ms);
        }
        return new ModeExecution(verdict, poisonRef.get());
    }

    private static String auditAndCleanThreadGroup(ThreadGroup group, Thread execThread) {
        int active = group.activeCount();
        if (active == 0) {
            return null;
        }
        Thread[] snapshot = new Thread[active + 8];
        int count = group.enumerate(snapshot, true);
        boolean foundStray = false;
        for (int i = 0; i < count; i++) {
            Thread t = snapshot[i];
            if (t != null && t != execThread && t.isAlive()) {
                foundStray = true;
                t.interrupt();
            }
        }
        if (!foundStray) {
            return null;
        }
        long deadline = System.nanoTime() + STRAY_THREAD_GRACE_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            boolean anyAlive = false;
            for (int i = 0; i < count; i++) {
                Thread t = snapshot[i];
                if (t != null && t != execThread && t.isAlive()) {
                    anyAlive = true;
                    break;
                }
            }
            if (!anyAlive) {
                break;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "Trial spawned stray background thread(s) in " + group.getName();
    }

    private CribProtocol.ModeVerdict runJvmMode(
            BatestFile file,
            BatestFile.BatestCase testCase,
            CribMode mode,
            CribProtocol.TrialRequest req,
            long startNanos) {
        BatestRunner.RunResult run =
                BatestRunner.runWithIr(
                        file,
                        testCase,
                        mode.engineMode(),
                        mode.optLevelOrNull(),
                        mode == CribMode.INTERPRET ? null : req.verify());
        BatestRunner.CaseOutcome outcome = run.outcome();
        long elapsedMs = nanosToMs(System.nanoTime() - startNanos);

        if (outcome.kind() == BatestRunner.Kind.INTERNAL_CRASH) {
            return new CribProtocol.ModeVerdict(
                    CribProtocol.STATUS_INTERNAL_CRASH,
                    outcome.kind().name(),
                    outcome.stdout(),
                    outcome.stderr(),
                    outcome.error(),
                    "INTERNAL_CRASH",
                    List.of(),
                    stackTrace(outcome.crash()),
                    elapsedMs);
        }

        String behavioralError = BatestRunner.evaluateBehavioral(testCase, outcome);
        List<String> irErrors = new ArrayList<>();
        if (behavioralError == null
                && req.checkIr()
                && run.compiledMode()
                && !testCase.irExpectations().isEmpty()) {
            List<IrAssertionChecker.IrMismatch> mismatches =
                    IrAssertionChecker.checkAll(
                            testCase.irExpectations(), run.capturedIr(), run.activeOptLevel());
            for (IrAssertionChecker.IrMismatch m : mismatches) {
                irErrors.add(m.message());
            }
        }

        boolean passed = behavioralError == null && irErrors.isEmpty();
        return new CribProtocol.ModeVerdict(
                passed ? CribProtocol.STATUS_PASS : CribProtocol.STATUS_FAIL,
                outcome.kind().name(),
                outcome.stdout(),
                outcome.stderr(),
                outcome.error(),
                behavioralError,
                irErrors,
                null,
                elapsedMs);
    }

    private ModeExecution executeWasmMode(
            BatestFile file,
            BatestFile.BatestCase testCase,
            CribMode mode,
            CribProtocol.TrialRequest req,
            long startNanos) {
        if (wasmWorker == null || !WasmExecWorker.isNodeAvailable()) {
            long ms = nanosToMs(System.nanoTime() - startNanos);
            return new ModeExecution(
                    new CribProtocol.ModeVerdict(
                            CribProtocol.STATUS_FAIL,
                            null,
                            "",
                            "",
                            "Node.js runtime unavailable for " + mode.label(),
                            "Node.js runtime unavailable for " + mode.label(),
                            List.of(),
                            null,
                            ms),
                    null);
        }
        if (testCase.session()) {
            long ms = nanosToMs(System.nanoTime() - startNanos);
            return new ModeExecution(
                    new CribProtocol.ModeVerdict(
                            CribProtocol.STATUS_SKIPPED_NON_PORTABLE,
                            null,
                            "",
                            "",
                            null,
                            null,
                            List.of(),
                            null,
                            ms),
                    null);
        }

        try {
            BabbleScriptEngine engine =
                    (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
            engine.put(BabbleScriptEngine.MODE, "compile");
            engine.put(BabbleCompiler.OPT_LEVEL, mode.optLevel());
            engine.put(BabbleCompiler.VERIFY, req.verify());

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

            List<BabbleScriptEngine.HirProgramModule> hirMods;
            try {
                hirMods = engine.compileSourceToHir(source, "case.ba", CompilationTarget.WASM_JS);
            } catch (Throwable t) {
                List<String> warnings =
                        BatestRunner.extractNormalizedWarnings(errWriter.toString());
                BatestRunner.CaseOutcome outcome = BatestRunner.classifyFailure(t, warnings, "");
                return new ModeExecution(
                        toModeVerdict(testCase, outcome, List.of(), startNanos), null);
            }

            List<String> warnings = BatestRunner.extractNormalizedWarnings(errWriter.toString());
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
                    new WasmCompiler.Options(
                            CompilationTarget.WASM_JS, req.verify(), false, false, false);
            WasmCompiler.CompileResult compiled = WasmCompiler.compile(inputs, opts, "case.wasm");
            if (!compiled.ok()) {
                boolean expectsWat =
                        testCase.irExpectations().stream().anyMatch(e -> e.tier() == IrTier.WAT);
                long ms = nanosToMs(System.nanoTime() - startNanos);
                if (expectsWat) {
                    String err =
                            "Case declares wat expectations but failed Wasm portability : "
                                    + compiled.portabilityErrors();
                    return new ModeExecution(
                            new CribProtocol.ModeVerdict(
                                    CribProtocol.STATUS_FAIL,
                                    null,
                                    "",
                                    "",
                                    err,
                                    err,
                                    List.of(),
                                    null,
                                    ms),
                            null);
                }
                return new ModeExecution(
                        new CribProtocol.ModeVerdict(
                                CribProtocol.STATUS_SKIPPED_NON_PORTABLE,
                                null,
                                "",
                                "",
                                null,
                                null,
                                List.of(),
                                null,
                                ms),
                        null);
            }

            WasmExecWorker.RunOutcome run =
                    wasmWorker.runJs(
                            compiled.wasmBytes(), compiled.jsGlue(), List.of(), req.timeoutMs());
            if (run.timedOut()) {
                long ms = nanosToMs(System.nanoTime() - startNanos);
                return new ModeExecution(
                        new CribProtocol.ModeVerdict(
                                CribProtocol.STATUS_TIMEOUT,
                                null,
                                BatestRunner.normalizeStdout(run.stdout()),
                                run.stderr(),
                                run.error(),
                                null,
                                List.of(),
                                null,
                                ms),
                        null);
            }

            BatestRunner.CaseOutcome outcome = toWasmCaseOutcome(run, warnings);
            List<String> irErrors = new ArrayList<>();
            String behavioralError = BatestRunner.evaluateBehavioral(testCase, outcome);
            if (behavioralError == null && req.checkIr()) {
                List<BatestFile.IrExpectation> watExps =
                        testCase.irExpectations().stream()
                                .filter(e -> e.tier() == IrTier.WAT)
                                .toList();
                if (!watExps.isEmpty()) {
                    IrSnapshot watSnap =
                            new IrSnapshot(
                                    IrTier.WAT,
                                    null,
                                    "case.wasm",
                                    mode.optLevel(),
                                    IrSnapshot.STAGE_FINAL,
                                    WatPrinter.asDumpable(compiled.module()));
                    for (IrAssertionChecker.IrMismatch m :
                            IrAssertionChecker.checkAll(
                                    watExps, List.of(watSnap), mode.optLevel(), 0)) {
                        irErrors.add(m.message());
                    }
                }
            }
            return new ModeExecution(toModeVerdict(testCase, outcome, irErrors, startNanos), null);
        } catch (OutOfMemoryError oom) {
            long ms = nanosToMs(System.nanoTime() - startNanos);
            return new ModeExecution(
                    new CribProtocol.ModeVerdict(
                            CribProtocol.STATUS_INTERNAL_CRASH,
                            BatestRunner.Kind.INTERNAL_CRASH.name(),
                            "",
                            "",
                            null,
                            "INTERNAL_CRASH",
                            List.of(),
                            stackTrace(oom),
                            ms),
                    "OutOfMemoryError during Wasm compilation");
        } catch (Throwable t) {
            long ms = nanosToMs(System.nanoTime() - startNanos);
            return new ModeExecution(
                    new CribProtocol.ModeVerdict(
                            CribProtocol.STATUS_INTERNAL_CRASH,
                            BatestRunner.Kind.INTERNAL_CRASH.name(),
                            "",
                            "",
                            null,
                            "INTERNAL_CRASH",
                            List.of(),
                            stackTrace(t),
                            ms),
                    null);
        }
    }

    private static CribProtocol.ModeVerdict toModeVerdict(
            BatestFile.BatestCase testCase,
            BatestRunner.CaseOutcome outcome,
            List<String> irErrors,
            long startNanos) {
        long ms = nanosToMs(System.nanoTime() - startNanos);
        if (outcome.kind() == BatestRunner.Kind.INTERNAL_CRASH) {
            return new CribProtocol.ModeVerdict(
                    CribProtocol.STATUS_INTERNAL_CRASH,
                    outcome.kind().name(),
                    outcome.stdout(),
                    outcome.stderr(),
                    outcome.error(),
                    "INTERNAL_CRASH",
                    List.of(),
                    stackTrace(outcome.crash()),
                    ms);
        }
        String behavioralError = BatestRunner.evaluateBehavioral(testCase, outcome);
        boolean passed = behavioralError == null && irErrors.isEmpty();
        return new CribProtocol.ModeVerdict(
                passed ? CribProtocol.STATUS_PASS : CribProtocol.STATUS_FAIL,
                outcome.kind().name(),
                outcome.stdout(),
                outcome.stderr(),
                outcome.error(),
                behavioralError,
                irErrors,
                null,
                ms);
    }

    private static BatestRunner.CaseOutcome toWasmCaseOutcome(
            WasmExecWorker.RunOutcome run, List<String> warnings) {
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

    /**
     * Compares normalized behavioral outcomes across all non-skipped, non-timed-out modes. Returns
     * {@code null} when every executed mode agrees, or a concise diff description otherwise.
     */
    static String computeParityDiff(Map<String, CribProtocol.ModeVerdict> verdicts) {
        record OutcomeKey(String kind, String stdout, String stderr, String error) {}

        Map<OutcomeKey, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, CribProtocol.ModeVerdict> e : verdicts.entrySet()) {
            CribProtocol.ModeVerdict v = e.getValue();
            if (CribProtocol.STATUS_SKIPPED_NON_PORTABLE.equals(v.status())
                    || CribProtocol.STATUS_TIMEOUT.equals(v.status())
                    || CribProtocol.STATUS_WORKER_CRASHED.equals(v.status())
                    || v.kind() == null) {
                continue;
            }
            OutcomeKey key = new OutcomeKey(v.kind(), v.stdout(), v.stderr(), v.error());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e.getKey());
        }
        if (groups.size() <= 1) {
            return null;
        }
        List<Map.Entry<OutcomeKey, List<String>>> entries = new ArrayList<>(groups.entrySet());
        OutcomeKey first = entries.get(0).getKey();
        OutcomeKey second = entries.get(1).getKey();

        List<String> diffFields = new ArrayList<>();
        if (!Objects.equals(first.kind(), second.kind())) diffFields.add("kind");
        if (!Objects.equals(first.stdout(), second.stdout())) diffFields.add("stdout");
        if (!Objects.equals(first.stderr(), second.stderr())) diffFields.add("stderr");
        if (!Objects.equals(first.error(), second.error())) diffFields.add("error");
        String channel = diffFields.isEmpty() ? "outcome" : String.join("/", diffFields);

        StringBuilder sb = new StringBuilder();
        sb.append(channel).append(" differs : ");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(" vs ");
            OutcomeKey k = entries.get(i).getKey();
            sb.append(String.join(",", entries.get(i).getValue())).append(" produced <");
            if (diffFields.size() == 1) {
                sb.append(
                        switch (diffFields.get(0)) {
                            case "kind" -> k.kind();
                            case "stdout" -> k.stdout();
                            case "stderr" -> k.stderr();
                            case "error" -> String.valueOf(k.error());
                            default -> k.toString();
                        });
            } else {
                sb.append("kind=")
                        .append(k.kind())
                        .append(", stdout=")
                        .append(k.stdout())
                        .append(", stderr=")
                        .append(k.stderr())
                        .append(", error=")
                        .append(k.error());
            }
            sb.append('>');
        }
        return sb.toString();
    }

    private static long nanosToMs(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static String stackTrace(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
