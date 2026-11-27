package org.babblelang.tests.batest;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Generates unified 3-mode test, parity, and JaCoCo coverage reports for {@code -Pbatest} (BIP009
 * §5.2, §5.3). Reads {@code outcomes-*.jsonl} and the isolated {@code jacoco.xml} reports, writes
 * {@code summary.json} and {@code uncovered.txt}, prints a per-scope ranked console summary (plus
 * {@code -Dbatest.inspect=<Class[,Class2]>} details when requested), and fails the build if any
 * case failed, diverged, or missed the per-scope line/branch gate.
 */
public final class BatestCoverageReporter {

    static final int DEFAULT_TOP_PER_SCOPE = 10;
    static final double DEFAULT_MIN_LINE_PCT = 80.0;
    static final double DEFAULT_MIN_BRANCH_PCT = 80.0;

    public enum Scope {
        INTERPRETER("Interpreter (impl.*)", "interpreter"),
        COMPILER("Compiler (-O0)", "compiler"),
        OPTIMIZER("Optimizer (-O1)", "optimizer"),
        OPTIMIZER_O2("Optimizer (-O2)", "optimizerO2"),
        FRONT_END("Shared Front-End (analysis)", "frontEnd"),
        SHARED_RUNTIME("Shared Runtime (combined)", "sharedRuntime"),
        EXCLUDED("Excluded", "excluded");

        final String label;
        final String jsonKey;

        Scope(String label, String jsonKey) {
            this.label = label;
            this.jsonKey = jsonKey;
        }
    }

    static final List<Scope> REPORT_SCOPES =
            List.of(
                    Scope.INTERPRETER,
                    Scope.COMPILER,
                    Scope.OPTIMIZER,
                    Scope.OPTIMIZER_O2,
                    Scope.FRONT_END,
                    Scope.SHARED_RUNTIME);

    public enum SortMode {
        SCORE("score = (missedBr+1)*(missedLn+1)-1"),
        BRANCHES("missed branches"),
        LINES("missed lines");

        final String description;

        SortMode(String description) {
            this.description = description;
        }

        static SortMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return SCORE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "score", "impact" -> SCORE;
                case "branches", "branch" -> BRANCHES;
                case "lines", "line" -> LINES;
                default ->
                        throw new IllegalArgumentException(
                                "Unknown batest.sort mode : "
                                        + raw
                                        + " (expected score, branches, or lines)");
            };
        }
    }

    private static final Set<String> EXCLUDED_CLASSES =
            Set.of(
                    "org.babblelang.Main",
                    "org.babblelang.Repl",
                    "org.babblelang.Babblefmt",
                    "org.babblelang.analysis.AxiomChecker",
                    "org.babblelang.analysis.AxiomDumper",
                    "org.babblelang.analysis.BytecodeEffectAnalyzer",
                    "org.babblelang.compiler.Babblec",
                    "org.babblelang.compiler.FileModuleCompiler",
                    "org.babblelang.compiler.Debugging",
                    "org.babblelang.ir.CliIrDumper",
                    "org.babblelang.ir.IrCanonicalSupport",
                    "org.babblelang.ir.IrObserver",
                    "org.babblelang.ir.IrSnapshot",
                    "org.babblelang.ir.IrTarget",
                    "org.babblelang.ir.IrTier",
                    "org.babblelang.ir.hir.HirPrinter",
                    "org.babblelang.ir.hir.HirCanonicalPrinter",
                    "org.babblelang.runtime.Launcher",
                    "org.babblelang.runtime.ProgramIndex",
                    "org.babblelang.engine.FileModuleSource",
                    "org.babblelang.engine.ClassPathModuleSource");

    private static final Set<String> COMPILER_RUNTIME_CLASSES =
            Set.of(
                    "org.babblelang.runtime.Bootstrap",
                    "org.babblelang.runtime.CompiledFunction",
                    "org.babblelang.runtime.CompiledMethod",
                    "org.babblelang.runtime.Frames",
                    "org.babblelang.runtime.Cell",
                    "org.babblelang.runtime.ByteCell",
                    "org.babblelang.runtime.ShortCell",
                    "org.babblelang.runtime.IntCell",
                    "org.babblelang.runtime.LongCell",
                    "org.babblelang.runtime.FloatCell",
                    "org.babblelang.runtime.DoubleCell",
                    "org.babblelang.runtime.BoolCell",
                    "org.babblelang.runtime.CharCell");

    public record CaseKey(String file, int index, int line) implements Comparable<CaseKey> {
        @Override
        public int compareTo(CaseKey o) {
            int c = this.file.compareTo(o.file);
            if (c != 0) {
                return c;
            }
            return Integer.compare(this.index, o.index);
        }
    }

    public record RecordedOutcome(
            CaseKey key,
            String name,
            String expected,
            String kind,
            String text,
            boolean passed,
            String crash,
            String irError,
            double durationMs) {
        public RecordedOutcome(
                CaseKey key,
                String name,
                String expected,
                String kind,
                String text,
                boolean passed,
                String crash,
                String irError) {
            this(key, name, expected, kind, text, passed, crash, irError, 0.0);
        }

        public RecordedOutcome(
                CaseKey key,
                String expected,
                String kind,
                String text,
                boolean passed,
                String crash) {
            this(key, "", expected, kind, text, passed, crash, null, 0.0);
        }
    }

    public record FileTiming(
            String file,
            int cases,
            double totalMs,
            double interpretMs,
            double compileO0Ms,
            double compileO1Ms,
            double compileO2Ms) {}

    record Counter(int missed, int covered) {
        int total() {
            return missed + covered;
        }

        double pct() {
            int t = total();
            return t == 0 ? 100.0 : (covered * 100.0) / t;
        }

        Counter plus(Counter other) {
            return new Counter(this.missed + other.missed, this.covered + other.covered);
        }
    }

    static long impactScore(Counter branches, Counter lines) {
        return (long) (branches.missed() + 1) * (lines.missed() + 1) - 1L;
    }

    static int countNeeded(Counter counter, double minPct) {
        if (minPct <= 0.0 || counter.total() == 0 || counter.pct() >= minPct) {
            return 0;
        }
        int target = (int) Math.ceil((minPct * counter.total()) / 100.0);
        while (target < counter.total() && (target * 100.0) / counter.total() < minPct) {
            target++;
        }
        while (target > 0 && ((target - 1) * 100.0) / counter.total() >= minPct) {
            target--;
        }
        return Math.max(0, target - counter.covered());
    }

    static int linesNeeded(Counter lines, double minLinePct) {
        return countNeeded(lines, minLinePct);
    }

    static int branchesNeeded(Counter branches, double minBranchPct) {
        return countNeeded(branches, minBranchPct);
    }

    static Comparator<FileCoverage> fileComparator(SortMode sortMode) {
        return switch (sortMode) {
            case SCORE ->
                    Comparator.comparingLong(
                                    (FileCoverage fc) -> impactScore(fc.branches, fc.lines))
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingInt(
                                                    (FileCoverage fc) -> fc.branches.missed())
                                            .reversed())
                            .thenComparing(
                                    Comparator.comparingInt((FileCoverage fc) -> fc.lines.missed())
                                            .reversed())
                            .thenComparing(fc -> fc.qualifiedClass);
            case BRANCHES ->
                    Comparator.comparingInt((FileCoverage fc) -> fc.branches.missed())
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingInt((FileCoverage fc) -> fc.lines.missed())
                                            .reversed())
                            .thenComparing(fc -> fc.qualifiedClass);
            case LINES ->
                    Comparator.comparingInt((FileCoverage fc) -> fc.lines.missed())
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingInt(
                                                    (FileCoverage fc) -> fc.branches.missed())
                                            .reversed())
                            .thenComparing(fc -> fc.qualifiedClass);
        };
    }

    static Comparator<MethodCoverage> methodComparator(SortMode sortMode) {
        return switch (sortMode) {
            case SCORE ->
                    Comparator.comparingLong(
                                    (MethodCoverage m) -> impactScore(m.branches(), m.lines()))
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingInt(
                                                    (MethodCoverage m) -> m.branches().missed())
                                            .reversed())
                            .thenComparing(
                                    Comparator.comparingInt(
                                                    (MethodCoverage m) -> m.lines().missed())
                                            .reversed())
                            .thenComparingInt(MethodCoverage::line)
                            .thenComparing(MethodCoverage::name);
            case BRANCHES ->
                    Comparator.comparingInt((MethodCoverage m) -> m.branches().missed())
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingInt(
                                                    (MethodCoverage m) -> m.lines().missed())
                                            .reversed())
                            .thenComparingInt(MethodCoverage::line)
                            .thenComparing(MethodCoverage::name);
            case LINES ->
                    Comparator.comparingInt((MethodCoverage m) -> m.lines().missed())
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingInt(
                                                    (MethodCoverage m) -> m.branches().missed())
                                            .reversed())
                            .thenComparingInt(MethodCoverage::line)
                            .thenComparing(MethodCoverage::name);
        };
    }

    record LineInfo(int line, int mi, int ci, int mb, int cb) {
        boolean isMissedLine() {
            return ci == 0 && mi > 0;
        }

        boolean isPartialBranch() {
            return mb > 0;
        }
    }

    record MethodCoverage(String name, String desc, int line, Counter lines, Counter branches) {
        boolean hasUncovered() {
            return lines.missed() > 0 || branches.missed() > 0;
        }
    }

    static final class FileCoverage {
        final String qualifiedClass;
        final String packagePath;
        final String sourceFile;
        final Scope scope;
        Counter lines = new Counter(0, 0);
        Counter branches = new Counter(0, 0);
        final TreeMap<Integer, LineInfo> lineMap = new TreeMap<>();
        final List<MethodCoverage> methods = new ArrayList<>();

        FileCoverage(String qualifiedClass, String packagePath, String sourceFile, Scope scope) {
            this.qualifiedClass = qualifiedClass;
            this.packagePath = packagePath;
            this.sourceFile = sourceFile;
            this.scope = scope;
        }

        String shortName() {
            String prefix = "org.babblelang.";
            return qualifiedClass.startsWith(prefix)
                    ? qualifiedClass.substring(prefix.length())
                    : qualifiedClass;
        }

        boolean hasUncovered() {
            return lines.missed() > 0 || branches.missed() > 0;
        }

        List<MethodCoverage> uncoveredMethods(SortMode sortMode) {
            return methods.stream()
                    .filter(MethodCoverage::hasUncovered)
                    .sorted(methodComparator(sortMode))
                    .toList();
        }
    }

    record FailureEntry(
            CaseKey key,
            String label,
            String expected,
            RecordedOutcome interpret,
            RecordedOutcome compile,
            RecordedOutcome compileO0,
            RecordedOutcome compileO2) {}

    private BatestCoverageReporter() {}

    public static void main(String[] args) throws Exception {
        Path reportDir = Path.of(System.getProperty("batest.report", "target/batest-report"));
        Path xmlInterpret =
                Path.of(
                        System.getProperty(
                                "batest.jacocoInterpret",
                                "target/site/jacoco-interpret/jacoco.xml"));
        Path xmlCompile =
                Path.of(
                        System.getProperty(
                                "batest.jacocoCompile", "target/site/jacoco-compile/jacoco.xml"));
        Path xmlCompileO0 =
                Path.of(
                        System.getProperty(
                                "batest.jacocoCompileO0",
                                "target/site/jacoco-compile-O0/jacoco.xml"));
        Path xmlOpt =
                Path.of(
                        System.getProperty(
                                "batest.jacocoOpt", "target/site/jacoco-opt/jacoco.xml"));
        Path xmlOptO2 =
                Path.of(
                        System.getProperty(
                                "batest.jacocoOptO2", "target/site/jacoco-opt-O2/jacoco.xml"));
        Path sourceRoot = Path.of(System.getProperty("batest.sourceRoot", "src/main/java"));
        String filter = System.getProperty("batest.filter", "").trim();
        String inspect = System.getProperty("batest.inspect", "").trim();
        double minLinePct =
                Double.parseDouble(
                        System.getProperty(
                                        "batest.minLinePct", String.valueOf(DEFAULT_MIN_LINE_PCT))
                                .trim());
        double minBranchPct =
                Double.parseDouble(
                        System.getProperty(
                                        "batest.minBranchPct",
                                        String.valueOf(DEFAULT_MIN_BRANCH_PCT))
                                .trim());
        int topPerScope =
                parseTop(System.getProperty("batest.top", String.valueOf(DEFAULT_TOP_PER_SCOPE)));
        SortMode sortMode = SortMode.parse(System.getProperty("batest.sort", "score"));

        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                xmlCompileO0,
                xmlOpt,
                xmlOptO2,
                sourceRoot,
                filter,
                inspect,
                minLinePct,
                minBranchPct,
                topPerScope,
                sortMode,
                System.out);
    }

    static int parseTop(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TOP_PER_SCOPE;
        }
        String trimmed = raw.trim();
        if ("all".equalsIgnoreCase(trimmed)) {
            return 0;
        }
        int parsed = Integer.parseInt(trimmed);
        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "Invalid batest.top value : " + raw + " (expected >= 0 or 'all')");
        }
        return parsed;
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path sourceRoot,
            String filter,
            String inspect)
            throws Exception {
        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                null,
                null,
                null,
                sourceRoot,
                filter,
                inspect,
                0.0,
                0.0,
                DEFAULT_TOP_PER_SCOPE,
                SortMode.SCORE,
                System.out);
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path sourceRoot,
            String filter,
            String inspect,
            double minBranchPct)
            throws Exception {
        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                null,
                null,
                null,
                sourceRoot,
                filter,
                inspect,
                0.0,
                minBranchPct,
                DEFAULT_TOP_PER_SCOPE,
                SortMode.SCORE,
                System.out);
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path xmlCompileO0,
            Path sourceRoot,
            String filter,
            String inspect,
            double minBranchPct)
            throws Exception {
        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                xmlCompileO0,
                null,
                null,
                sourceRoot,
                filter,
                inspect,
                0.0,
                minBranchPct,
                DEFAULT_TOP_PER_SCOPE,
                SortMode.SCORE,
                System.out);
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path xmlCompileO0,
            Path xmlOpt,
            Path sourceRoot,
            String filter,
            String inspect,
            double minBranchPct)
            throws Exception {
        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                xmlCompileO0,
                xmlOpt,
                null,
                sourceRoot,
                filter,
                inspect,
                0.0,
                minBranchPct,
                DEFAULT_TOP_PER_SCOPE,
                SortMode.SCORE,
                System.out);
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path xmlCompileO0,
            Path xmlOpt,
            Path sourceRoot,
            String filter,
            String inspect,
            double minLinePct,
            double minBranchPct)
            throws Exception {
        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                xmlCompileO0,
                xmlOpt,
                null,
                sourceRoot,
                filter,
                inspect,
                minLinePct,
                minBranchPct,
                DEFAULT_TOP_PER_SCOPE,
                SortMode.SCORE,
                System.out);
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path xmlCompileO0,
            Path xmlOpt,
            Path sourceRoot,
            String filter,
            String inspect,
            double minBranchPct,
            int topPerScope,
            SortMode sortMode,
            PrintStream out)
            throws Exception {
        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                xmlCompileO0,
                xmlOpt,
                null,
                sourceRoot,
                filter,
                inspect,
                0.0,
                minBranchPct,
                topPerScope,
                sortMode,
                out);
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path xmlCompileO0,
            Path xmlOpt,
            Path sourceRoot,
            String filter,
            String inspect,
            double minLinePct,
            double minBranchPct,
            int topPerScope,
            SortMode sortMode,
            PrintStream out)
            throws Exception {
        run(
                reportDir,
                xmlInterpret,
                xmlCompile,
                xmlCompileO0,
                xmlOpt,
                null,
                sourceRoot,
                filter,
                inspect,
                minLinePct,
                minBranchPct,
                topPerScope,
                sortMode,
                out);
    }

    static void run(
            Path reportDir,
            Path xmlInterpret,
            Path xmlCompile,
            Path xmlCompileO0,
            Path xmlOpt,
            Path xmlMir,
            Path sourceRoot,
            String filter,
            String inspect,
            double minLinePct,
            double minBranchPct,
            int topPerScope,
            SortMode sortMode,
            PrintStream out)
            throws Exception {
        Files.createDirectories(reportDir);

        Map<CaseKey, RecordedOutcome> interpretOutcomes =
                readOutcomes(reportDir.resolve("outcomes-interpret.jsonl"));
        Map<CaseKey, RecordedOutcome> compileOutcomes =
                readOutcomes(reportDir.resolve("outcomes-compile.jsonl"));
        Path compileO0Path = reportDir.resolve("outcomes-compile-O0.jsonl");
        boolean hasCompileO0Outcomes = Files.isRegularFile(compileO0Path);
        Map<CaseKey, RecordedOutcome> compileO0Outcomes =
                hasCompileO0Outcomes ? readOutcomes(compileO0Path) : Map.of();
        Path compileO2Path = reportDir.resolve("outcomes-compile-O2.jsonl");
        boolean hasCompileO2Outcomes = Files.isRegularFile(compileO2Path);
        Map<CaseKey, RecordedOutcome> compileO2Outcomes =
                hasCompileO2Outcomes ? readOutcomes(compileO2Path) : Map.of();

        Set<CaseKey> allKeys = new LinkedHashSet<>();
        allKeys.addAll(interpretOutcomes.keySet());
        allKeys.addAll(compileOutcomes.keySet());
        if (hasCompileO0Outcomes) {
            allKeys.addAll(compileO0Outcomes.keySet());
        }
        if (hasCompileO2Outcomes) {
            allKeys.addAll(compileO2Outcomes.keySet());
        }
        List<CaseKey> sortedKeys = new ArrayList<>(allKeys);
        sortedKeys.sort(Comparator.naturalOrder());

        Set<String> filesSeen = new LinkedHashSet<>();
        int interpPassed = 0;
        int compilePassed = 0;
        int compileO0Passed = 0;
        int compileO2Passed = 0;
        int parityMatched = 0;
        List<FailureEntry> failures = new ArrayList<>();

        for (CaseKey key : sortedKeys) {
            filesSeen.add(key.file());
            RecordedOutcome interp = interpretOutcomes.get(key);
            RecordedOutcome comp = compileOutcomes.get(key);
            RecordedOutcome compO0 = hasCompileO0Outcomes ? compileO0Outcomes.get(key) : null;
            RecordedOutcome compO2 = hasCompileO2Outcomes ? compileO2Outcomes.get(key) : null;

            if (interp != null && interp.passed()) {
                interpPassed++;
            }
            if (comp != null && comp.passed()) {
                compilePassed++;
            }
            if (compO0 != null && compO0.passed()) {
                compileO0Passed++;
            }
            if (compO2 != null && compO2.passed()) {
                compileO2Passed++;
            }

            boolean interpCrash = interp != null && "INTERNAL_CRASH".equals(interp.kind());
            boolean compCrash = comp != null && "INTERNAL_CRASH".equals(comp.kind());
            boolean compO0Crash = compO0 != null && "INTERNAL_CRASH".equals(compO0.kind());
            boolean compO2Crash = compO2 != null && "INTERNAL_CRASH".equals(compO2.kind());
            boolean parity =
                    interp != null
                            && comp != null
                            && !interpCrash
                            && !compCrash
                            && interp.kind().equals(comp.kind())
                            && interp.text().equals(comp.text())
                            && (!hasCompileO0Outcomes
                                    || (compO0 != null
                                            && !compO0Crash
                                            && interp.kind().equals(compO0.kind())
                                            && interp.text().equals(compO0.text())))
                            && (!hasCompileO2Outcomes
                                    || (compO2 != null
                                            && !compO2Crash
                                            && interp.kind().equals(compO2.kind())
                                            && interp.text().equals(compO2.text())));
            if (parity) {
                parityMatched++;
            }

            boolean allPass =
                    interp != null
                            && comp != null
                            && interp.passed()
                            && comp.passed()
                            && (!hasCompileO0Outcomes || (compO0 != null && compO0.passed()))
                            && (!hasCompileO2Outcomes || (compO2 != null && compO2.passed()))
                            && parity;
            if (!allPass) {
                String label;
                boolean hasIrErr =
                        (comp != null && comp.irError() != null && !comp.irError().isEmpty())
                                || (compO0 != null
                                        && compO0.irError() != null
                                        && !compO0.irError().isEmpty())
                                || (compO2 != null
                                        && compO2.irError() != null
                                        && !compO2.irError().isEmpty());
                if (interpCrash || compCrash || compO0Crash || compO2Crash) {
                    label = "INTERNAL CRASH";
                } else if (interp == null
                        || comp == null
                        || (hasCompileO0Outcomes && compO0 == null)
                        || (hasCompileO2Outcomes && compO2 == null)) {
                    label = "MISSING EXECUTION";
                } else if (!parity) {
                    label = "PARITY MISMATCH";
                } else if (hasIrErr && interp.passed()) {
                    label = "IR GOLDEN MISMATCH";
                } else if (!comp.passed()
                        && interp.passed()
                        && (!hasCompileO0Outcomes || compO0.passed())
                        && (!hasCompileO2Outcomes || compO2.passed())) {
                    label = "COMPILE MISMATCH";
                } else if (hasCompileO0Outcomes
                        && !compO0.passed()
                        && interp.passed()
                        && comp.passed()
                        && (!hasCompileO2Outcomes || compO2.passed())) {
                    label = "COMPILE -O0 MISMATCH";
                } else if (hasCompileO2Outcomes
                        && !compO2.passed()
                        && interp.passed()
                        && comp.passed()
                        && (!hasCompileO0Outcomes || compO0.passed())) {
                    label = "COMPILE -O2 MISMATCH";
                } else if (!interp.passed()
                        && comp.passed()
                        && (!hasCompileO0Outcomes || compO0.passed())
                        && (!hasCompileO2Outcomes || compO2.passed())) {
                    label = "INTERPRET MISMATCH";
                } else {
                    label = "EXPECTATION MISMATCH";
                }
                String expected =
                        interp != null
                                ? interp.expected()
                                : (comp != null
                                        ? comp.expected()
                                        : (compO0 != null ? compO0.expected() : compO2.expected()));
                failures.add(new FailureEntry(key, label, expected, interp, comp, compO0, compO2));
            }
        }

        Map<String, FileCoverage> interpCov =
                Files.isRegularFile(xmlInterpret) ? parseJacocoXml(xmlInterpret) : Map.of();
        Map<String, FileCoverage> compileCov =
                Files.isRegularFile(xmlCompile) ? parseJacocoXml(xmlCompile) : Map.of();
        boolean hasCompileO0Cov = xmlCompileO0 != null && Files.isRegularFile(xmlCompileO0);
        Map<String, FileCoverage> compileO0Cov =
                hasCompileO0Cov ? parseJacocoXml(xmlCompileO0) : Map.of();
        boolean hasOptCov = xmlOpt != null && Files.isRegularFile(xmlOpt);
        Map<String, FileCoverage> optCov = hasOptCov ? parseJacocoXml(xmlOpt) : Map.of();
        boolean hasMirCov = xmlMir != null && Files.isRegularFile(xmlMir);
        Map<String, FileCoverage> mirCov = hasMirCov ? parseJacocoXml(xmlMir) : Map.of();

        Map<String, FileCoverage> compilerSourceCov = hasCompileO0Cov ? compileO0Cov : compileCov;
        Map<String, FileCoverage> optSourceCov = hasOptCov ? optCov : compileCov;
        Map<String, FileCoverage> frontSourceCov =
                compileCov.isEmpty() && hasCompileO0Cov ? compileO0Cov : compileCov;

        Counter[] scopeInterpLines = new Counter[] {new Counter(0, 0)};
        Counter[] scopeInterpBranches = new Counter[] {new Counter(0, 0)};
        Counter[] scopeCompileLines = new Counter[] {new Counter(0, 0)};
        Counter[] scopeCompileBranches = new Counter[] {new Counter(0, 0)};
        Counter[] scopeOptLines = new Counter[] {new Counter(0, 0)};
        Counter[] scopeOptBranches = new Counter[] {new Counter(0, 0)};
        Counter[] scopeMirLines = new Counter[] {new Counter(0, 0)};
        Counter[] scopeMirBranches = new Counter[] {new Counter(0, 0)};
        Counter[] scopeFrontLines = new Counter[] {new Counter(0, 0)};
        Counter[] scopeFrontBranches = new Counter[] {new Counter(0, 0)};
        Counter[] sharedInterpLines = new Counter[] {new Counter(0, 0)};
        Counter[] sharedInterpBranches = new Counter[] {new Counter(0, 0)};
        Counter[] sharedCompileLines = new Counter[] {new Counter(0, 0)};
        Counter[] sharedCompileBranches = new Counter[] {new Counter(0, 0)};
        Counter[] sharedCompileO0Lines = new Counter[] {new Counter(0, 0)};
        Counter[] sharedCompileO0Branches = new Counter[] {new Counter(0, 0)};
        Counter[] sharedUnionLines = new Counter[] {new Counter(0, 0)};
        Counter[] sharedUnionBranches = new Counter[] {new Counter(0, 0)};

        for (FileCoverage fc : interpCov.values()) {
            if (fc.scope == Scope.INTERPRETER) {
                scopeInterpLines[0] = scopeInterpLines[0].plus(fc.lines);
                scopeInterpBranches[0] = scopeInterpBranches[0].plus(fc.branches);
            } else if (fc.scope == Scope.SHARED_RUNTIME) {
                sharedInterpLines[0] = sharedInterpLines[0].plus(fc.lines);
                sharedInterpBranches[0] = sharedInterpBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : compilerSourceCov.values()) {
            if (fc.scope == Scope.COMPILER) {
                scopeCompileLines[0] = scopeCompileLines[0].plus(fc.lines);
                scopeCompileBranches[0] = scopeCompileBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : optSourceCov.values()) {
            if (fc.scope == Scope.OPTIMIZER) {
                scopeOptLines[0] = scopeOptLines[0].plus(fc.lines);
                scopeOptBranches[0] = scopeOptBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : mirCov.values()) {
            if (fc.scope == Scope.OPTIMIZER_O2) {
                scopeMirLines[0] = scopeMirLines[0].plus(fc.lines);
                scopeMirBranches[0] = scopeMirBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : frontSourceCov.values()) {
            if (fc.scope == Scope.FRONT_END) {
                scopeFrontLines[0] = scopeFrontLines[0].plus(fc.lines);
                scopeFrontBranches[0] = scopeFrontBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : compileCov.values()) {
            if (fc.scope == Scope.SHARED_RUNTIME) {
                sharedCompileLines[0] = sharedCompileLines[0].plus(fc.lines);
                sharedCompileBranches[0] = sharedCompileBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : compileO0Cov.values()) {
            if (fc.scope == Scope.SHARED_RUNTIME) {
                sharedCompileO0Lines[0] = sharedCompileO0Lines[0].plus(fc.lines);
                sharedCompileO0Branches[0] = sharedCompileO0Branches[0].plus(fc.branches);
            }
        }

        // Combined shared runtime union + combined view for hotspot ranking
        Map<String, FileCoverage> sharedCombinedMap = new TreeMap<>();
        Set<String> allSharedNames = new LinkedHashSet<>();
        for (FileCoverage fc : interpCov.values()) {
            if (fc.scope == Scope.SHARED_RUNTIME) {
                allSharedNames.add(fc.qualifiedClass);
            }
        }
        for (FileCoverage fc : compileCov.values()) {
            if (fc.scope == Scope.SHARED_RUNTIME) {
                allSharedNames.add(fc.qualifiedClass);
            }
        }
        for (FileCoverage fc : compileO0Cov.values()) {
            if (fc.scope == Scope.SHARED_RUNTIME) {
                allSharedNames.add(fc.qualifiedClass);
            }
        }
        for (String name : allSharedNames) {
            FileCoverage iFc = interpCov.get(name);
            FileCoverage cFc = compileCov.get(name);
            FileCoverage c0Fc = compileO0Cov.get(name);
            FileCoverage ref = cFc != null ? cFc : (c0Fc != null ? c0Fc : iFc);
            FileCoverage unionFc = combineShared(ref, iFc, cFc, c0Fc);
            sharedCombinedMap.put(name, unionFc);
            sharedUnionLines[0] = sharedUnionLines[0].plus(unionFc.lines);
            sharedUnionBranches[0] = sharedUnionBranches[0].plus(unionFc.branches);
        }

        // Collect canonical FileCoverage per class in its authoritative scope
        List<FileCoverage> canonicalClasses = new ArrayList<>();
        for (FileCoverage fc : interpCov.values()) {
            if (fc.scope == Scope.INTERPRETER) {
                canonicalClasses.add(fc);
            }
        }
        for (FileCoverage fc : compilerSourceCov.values()) {
            if (fc.scope == Scope.COMPILER) {
                canonicalClasses.add(fc);
            }
        }
        for (FileCoverage fc : optSourceCov.values()) {
            if (fc.scope == Scope.OPTIMIZER) {
                canonicalClasses.add(fc);
            }
        }
        for (FileCoverage fc : mirCov.values()) {
            if (fc.scope == Scope.OPTIMIZER_O2) {
                canonicalClasses.add(fc);
            }
        }
        for (FileCoverage fc : frontSourceCov.values()) {
            if (fc.scope == Scope.FRONT_END) {
                canonicalClasses.add(fc);
            }
        }
        canonicalClasses.addAll(sharedCombinedMap.values());

        EnumMap<Scope, Counter> scopeLines = new EnumMap<>(Scope.class);
        EnumMap<Scope, Counter> scopeBranches = new EnumMap<>(Scope.class);
        scopeLines.put(Scope.INTERPRETER, scopeInterpLines[0]);
        scopeBranches.put(Scope.INTERPRETER, scopeInterpBranches[0]);
        scopeLines.put(Scope.COMPILER, scopeCompileLines[0]);
        scopeBranches.put(Scope.COMPILER, scopeCompileBranches[0]);
        scopeLines.put(Scope.OPTIMIZER, scopeOptLines[0]);
        scopeBranches.put(Scope.OPTIMIZER, scopeOptBranches[0]);
        scopeLines.put(Scope.OPTIMIZER_O2, scopeMirLines[0]);
        scopeBranches.put(Scope.OPTIMIZER_O2, scopeMirBranches[0]);
        scopeLines.put(Scope.FRONT_END, scopeFrontLines[0]);
        scopeBranches.put(Scope.FRONT_END, scopeFrontBranches[0]);
        scopeLines.put(Scope.SHARED_RUNTIME, sharedUnionLines[0]);
        scopeBranches.put(Scope.SHARED_RUNTIME, sharedUnionBranches[0]);

        EnumMap<Scope, Integer> scopeClassCounts = new EnumMap<>(Scope.class);
        EnumMap<Scope, List<FileCoverage>> hotspotsByScope = new EnumMap<>(Scope.class);
        Comparator<FileCoverage> cmp = fileComparator(sortMode);
        for (Scope scope : REPORT_SCOPES) {
            scopeClassCounts.put(scope, 0);
            hotspotsByScope.put(scope, new ArrayList<>());
        }
        for (FileCoverage fc : canonicalClasses) {
            if (hotspotsByScope.containsKey(fc.scope)) {
                scopeClassCounts.merge(fc.scope, 1, Integer::sum);
                if (fc.hasUncovered()) {
                    hotspotsByScope.get(fc.scope).add(fc);
                }
            }
        }
        for (Scope scope : REPORT_SCOPES) {
            hotspotsByScope.get(scope).sort(cmp);
        }

        boolean enforceLineGate = filter.isEmpty() && minLinePct > 0.0;
        boolean enforceBranchGate = filter.isEmpty() && minBranchPct > 0.0;
        boolean enforceGate = enforceLineGate || enforceBranchGate;

        writeUncoveredTxt(
                reportDir.resolve("uncovered.txt"),
                scopeLines,
                scopeBranches,
                hotspotsByScope,
                sortMode,
                enforceGate,
                minLinePct,
                minBranchPct,
                sourceRoot);
        writeSummaryJson(
                reportDir.resolve("summary.json"),
                filter,
                filesSeen.size(),
                sortedKeys.size(),
                hasCompileO0Outcomes,
                hasCompileO2Outcomes,
                interpPassed,
                compilePassed,
                compileO0Passed,
                compileO2Passed,
                parityMatched,
                failures,
                scopeInterpLines[0],
                scopeInterpBranches[0],
                scopeCompileLines[0],
                scopeCompileBranches[0],
                scopeOptLines[0],
                scopeOptBranches[0],
                hasMirCov,
                scopeMirLines[0],
                scopeMirBranches[0],
                scopeFrontLines[0],
                scopeFrontBranches[0],
                sharedInterpLines[0],
                sharedInterpBranches[0],
                hasCompileO0Cov,
                sharedCompileO0Lines[0],
                sharedCompileO0Branches[0],
                sharedCompileLines[0],
                sharedCompileBranches[0],
                sharedUnionLines[0],
                sharedUnionBranches[0],
                canonicalClasses,
                hotspotsByScope,
                sortMode);

        List<FileTiming> slowestFiles =
                computeFileTimings(
                        sortedKeys,
                        interpretOutcomes,
                        compileO0Outcomes,
                        compileOutcomes,
                        compileO2Outcomes);

        printConsoleSummary(
                out,
                filter,
                filesSeen.size(),
                sortedKeys.size(),
                hasCompileO0Outcomes,
                hasCompileO2Outcomes,
                interpPassed,
                compilePassed,
                compileO0Passed,
                compileO2Passed,
                parityMatched,
                failures,
                scopeLines,
                scopeBranches,
                hasMirCov,
                sharedInterpLines[0],
                sharedInterpBranches[0],
                hasCompileO0Cov,
                sharedCompileO0Lines[0],
                sharedCompileO0Branches[0],
                sharedCompileLines[0],
                sharedCompileBranches[0],
                scopeClassCounts,
                hotspotsByScope,
                slowestFiles,
                topPerScope,
                sortMode,
                enforceGate,
                minLinePct,
                minBranchPct,
                reportDir,
                inspect,
                canonicalClasses,
                sourceRoot);

        if (sortedKeys.isEmpty()) {
            throw new IllegalStateException("No .batest cases were recorded in " + reportDir);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " .batest case failure(s)");
        }
        if (enforceGate) {
            List<String> belowLines = new ArrayList<>();
            List<String> belowBranches = new ArrayList<>();
            for (Scope scope : REPORT_SCOPES) {
                if (enforceLineGate) {
                    checkThreshold(
                            belowLines, scope.label, scopeLines.get(scope), minLinePct, "ln");
                }
                if (enforceBranchGate) {
                    checkThreshold(
                            belowBranches,
                            scope.label,
                            scopeBranches.get(scope),
                            minBranchPct,
                            "br");
                }
            }
            if (!belowLines.isEmpty() || !belowBranches.isEmpty()) {
                List<String> parts = new ArrayList<>();
                if (!belowLines.isEmpty()) {
                    parts.add(
                            String.format(
                                    Locale.ROOT,
                                    "Line coverage below %.1f%% threshold : %s",
                                    minLinePct,
                                    String.join(", ", belowLines)));
                }
                if (!belowBranches.isEmpty()) {
                    parts.add(
                            String.format(
                                    Locale.ROOT,
                                    "Branch coverage below %.1f%% threshold : %s",
                                    minBranchPct,
                                    String.join(", ", belowBranches)));
                }
                throw new AssertionError(String.join("; ", parts));
            }
        }
    }

    private static void checkThreshold(
            List<String> below, String scope, Counter counter, double minPct, String unit) {
        if (counter.total() > 0 && counter.pct() < minPct) {
            int needed = countNeeded(counter, minPct);
            below.add(
                    String.format(
                            Locale.ROOT,
                            "%s=%.1f%% (%d/%d, NEED +%d %s)",
                            scope,
                            counter.pct(),
                            counter.covered(),
                            counter.total(),
                            needed,
                            unit));
        }
    }

    private static FileCoverage combineShared(FileCoverage ref, FileCoverage... modes) {
        FileCoverage union =
                new FileCoverage(
                        ref.qualifiedClass, ref.packagePath, ref.sourceFile, Scope.SHARED_RUNTIME);
        Set<Integer> lineNums = new LinkedHashSet<>();
        for (FileCoverage fc : modes) {
            if (fc != null) {
                lineNums.addAll(fc.lineMap.keySet());
            }
        }
        int lineMissed = 0;
        int lineCovered = 0;
        int branchMissed = 0;
        int branchCovered = 0;
        for (int nr : lineNums) {
            int mi = 0;
            boolean hasMi = false;
            int ci = 0;
            int totalInstr = 0;
            boolean coveredLine = false;
            int totalB = 0;
            int cb = 0;
            for (FileCoverage fc : modes) {
                LineInfo li = fc != null ? fc.lineMap.get(nr) : null;
                if (li != null) {
                    if (!hasMi) {
                        mi = li.mi();
                        hasMi = true;
                    }
                    ci = Math.max(ci, li.ci());
                    totalInstr = Math.max(totalInstr, li.mi() + li.ci());
                    coveredLine |= li.ci() > 0;
                    totalB = Math.max(totalB, li.mb() + li.cb());
                    cb = Math.max(cb, li.cb());
                }
            }
            if (totalInstr > 0) {
                if (coveredLine) {
                    lineCovered++;
                } else {
                    lineMissed++;
                }
            }
            int mb = Math.max(0, totalB - cb);
            branchCovered += cb;
            branchMissed += mb;
            union.lineMap.put(
                    nr,
                    new LineInfo(
                            nr, coveredLine ? 0 : mi, coveredLine ? Math.max(1, ci) : 0, mb, cb));
        }
        union.lines = new Counter(lineMissed, lineCovered);
        union.branches = new Counter(branchMissed, branchCovered);

        // Merge method coverage optimistically across all modes (including 100%-covered methods)
        Map<String, MethodCoverage> mergedMethods = new LinkedHashMap<>();
        for (FileCoverage fc : modes) {
            if (fc == null) {
                continue;
            }
            for (MethodCoverage m : fc.methods) {
                String k = m.name() + "@" + m.line() + "#" + m.desc();
                MethodCoverage prev = mergedMethods.get(k);
                if (prev == null) {
                    mergedMethods.put(k, m);
                } else {
                    int totL = Math.max(m.lines().total(), prev.lines().total());
                    int covL = Math.max(m.lines().covered(), prev.lines().covered());
                    int totB = Math.max(m.branches().total(), prev.branches().total());
                    int covB = Math.max(m.branches().covered(), prev.branches().covered());
                    mergedMethods.put(
                            k,
                            new MethodCoverage(
                                    m.name(),
                                    m.desc(),
                                    m.line(),
                                    new Counter(Math.max(0, totL - covL), covL),
                                    new Counter(Math.max(0, totB - covB), covB)));
                }
            }
        }
        union.methods.addAll(mergedMethods.values());
        return union;
    }

    private static final Set<String> O2_OPTIMIZER_CLASSES =
            Set.of(
                    "org.babblelang.opt.hir.HirSccpPass",
                    "org.babblelang.opt.hir.HirAeGraphPass",
                    "org.babblelang.opt.hir.AeGraphState",
                    "org.babblelang.opt.hir.AeGraphBuilder",
                    "org.babblelang.opt.hir.AeGraphExtractor",
                    "org.babblelang.ir.egraph.PipelineRules",
                    "org.babblelang.opt.hir.PipelineRewritePass",
                    "org.babblelang.opt.hir.HirPipelineLifter",
                    "org.babblelang.opt.hir.HirPipelineAnalysis",
                    "org.babblelang.opt.hir.HirPipelineLowerer");

    static Scope scopeOf(String qualifiedClass) {
        if (qualifiedClass.startsWith("org.babblelang.parser.")
                || qualifiedClass.startsWith("org.babblelang.format.")
                || qualifiedClass.startsWith("org.babblelang.batest.")
                || qualifiedClass.startsWith("org.babblelang.crib.")
                || qualifiedClass.startsWith("org.babblelang.devserver.")
                || EXCLUDED_CLASSES.contains(qualifiedClass)) {
            return Scope.EXCLUDED;
        }
        if (qualifiedClass.startsWith("org.babblelang.engine.impl.natives.")) {
            return Scope.SHARED_RUNTIME;
        }
        if (qualifiedClass.startsWith("org.babblelang.engine.impl.")) {
            return Scope.INTERPRETER;
        }
        int dollar = qualifiedClass.indexOf('$');
        String topClass = dollar >= 0 ? qualifiedClass.substring(0, dollar) : qualifiedClass;
        if (O2_OPTIMIZER_CLASSES.contains(topClass)) {
            return Scope.OPTIMIZER_O2;
        }
        if (qualifiedClass.startsWith("org.babblelang.opt.")
                || qualifiedClass.startsWith("org.babblelang.ir.egraph.")
                || "org.babblelang.ir.hir.IrEffect".equals(topClass)
                || "org.babblelang.compiler.HirSlotAllocator".equals(topClass)) {
            return Scope.OPTIMIZER;
        }
        if (qualifiedClass.startsWith("org.babblelang.compiler.")
                || qualifiedClass.startsWith("org.babblelang.ir.")
                || COMPILER_RUNTIME_CLASSES.contains(qualifiedClass)) {
            return Scope.COMPILER;
        }
        if (qualifiedClass.startsWith("org.babblelang.analysis.")
                || qualifiedClass.startsWith("org.babblelang.effects.")
                || qualifiedClass.startsWith("org.babblelang.engine.")) {
            return Scope.FRONT_END;
        }
        if (qualifiedClass.startsWith("org.babblelang.runtime.")) {
            return Scope.SHARED_RUNTIME;
        }
        return Scope.EXCLUDED;
    }

    static Map<String, FileCoverage> parseJacocoXml(Path xmlPath) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setValidating(false);
        Document doc = dbf.newDocumentBuilder().parse(xmlPath.toFile());

        Map<String, FileCoverage> result = new TreeMap<>();
        NodeList packages = doc.getElementsByTagName("package");
        for (int p = 0; p < packages.getLength(); p++) {
            Element pkg = (Element) packages.item(p);
            String pkgInternal = pkg.getAttribute("name");
            String pkgDot = pkgInternal.isEmpty() ? "" : pkgInternal.replace('/', '.') + ".";

            Map<String, FileCoverage> bySource = new HashMap<>();

            // Read sourcefile elements for exact per-line mi/ci/mb/cb
            NodeList children = pkg.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element el)) {
                    continue;
                }
                if ("sourcefile".equals(el.getTagName())) {
                    String srcName = el.getAttribute("name");
                    String baseName =
                            srcName.endsWith(".java")
                                    ? srcName.substring(0, srcName.length() - ".java".length())
                                    : srcName;
                    String qualified = pkgDot + baseName;
                    Scope scope = scopeOf(qualified);
                    if (scope == Scope.EXCLUDED) {
                        continue;
                    }
                    FileCoverage fc =
                            bySource.computeIfAbsent(
                                    srcName,
                                    s -> new FileCoverage(qualified, pkgInternal, srcName, scope));
                    NodeList sfKids = el.getChildNodes();
                    for (int k = 0; k < sfKids.getLength(); k++) {
                        if (!(sfKids.item(k) instanceof Element child)) {
                            continue;
                        }
                        if ("line".equals(child.getTagName())) {
                            int nr = parseIntAttr(child, "nr");
                            int mi = parseIntAttr(child, "mi");
                            int ci = parseIntAttr(child, "ci");
                            int mb = parseIntAttr(child, "mb");
                            int cb = parseIntAttr(child, "cb");
                            fc.lineMap.put(nr, new LineInfo(nr, mi, ci, mb, cb));
                        } else if ("counter".equals(child.getTagName())) {
                            String type = child.getAttribute("type");
                            int missed = parseIntAttr(child, "missed");
                            int covered = parseIntAttr(child, "covered");
                            if ("LINE".equals(type)) {
                                fc.lines = new Counter(missed, covered);
                            } else if ("BRANCH".equals(type)) {
                                fc.branches = new Counter(missed, covered);
                            }
                        }
                    }
                    result.put(qualified, fc);
                }
            }

            // Read method-level counters inside <class>
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element el) || !"class".equals(el.getTagName())) {
                    continue;
                }
                String srcName = el.getAttribute("sourcefilename");
                FileCoverage fc = bySource.get(srcName);
                if (fc == null) {
                    continue;
                }
                String clsSimple = el.getAttribute("name");
                int slash = clsSimple.lastIndexOf('/');
                if (slash >= 0) {
                    clsSimple = clsSimple.substring(slash + 1);
                }
                NodeList clsKids = el.getChildNodes();
                for (int k = 0; k < clsKids.getLength(); k++) {
                    if (!(clsKids.item(k) instanceof Element mEl)
                            || !"method".equals(mEl.getTagName())) {
                        continue;
                    }
                    String mName = mEl.getAttribute("name");
                    String mDesc = mEl.getAttribute("desc");
                    int mLine = parseIntAttr(mEl, "line");
                    Counter mLines = new Counter(0, 0);
                    Counter mBranches = new Counter(0, 0);
                    NodeList mCounters = mEl.getChildNodes();
                    for (int c = 0; c < mCounters.getLength(); c++) {
                        if (mCounters.item(c) instanceof Element cEl
                                && "counter".equals(cEl.getTagName())) {
                            String type = cEl.getAttribute("type");
                            int missed = parseIntAttr(cEl, "missed");
                            int covered = parseIntAttr(cEl, "covered");
                            if ("LINE".equals(type)) {
                                mLines = new Counter(missed, covered);
                            } else if ("BRANCH".equals(type)) {
                                mBranches = new Counter(missed, covered);
                            }
                        }
                    }
                    if (mLines.total() > 0 || mBranches.total() > 0) {
                        String displayMethod =
                                clsSimple.contains("$") ? clsSimple + "." + mName : mName;
                        fc.methods.add(
                                new MethodCoverage(
                                        displayMethod,
                                        mDesc == null ? "" : mDesc,
                                        mLine,
                                        mLines,
                                        mBranches));
                    }
                }
            }
        }
        return result;
    }

    private static int parseIntAttr(Element el, String attr) {
        String v = el.getAttribute(attr);
        return v == null || v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    private static void printConsoleSummary(
            PrintStream out,
            String filter,
            int fileCount,
            int caseCount,
            boolean hasCompileO0Outcomes,
            boolean hasCompileO2Outcomes,
            int interpPassed,
            int compilePassed,
            int compileO0Passed,
            int compileO2Passed,
            int parityMatched,
            List<FailureEntry> failures,
            EnumMap<Scope, Counter> scopeLines,
            EnumMap<Scope, Counter> scopeBranches,
            boolean hasMirCov,
            Counter shInterpL,
            Counter shInterpB,
            boolean hasCompileO0Cov,
            Counter shCompO0L,
            Counter shCompO0B,
            Counter shCompL,
            Counter shCompB,
            EnumMap<Scope, Integer> scopeClassCounts,
            EnumMap<Scope, List<FileCoverage>> hotspotsByScope,
            List<FileTiming> slowestFiles,
            int topPerScope,
            SortMode sortMode,
            boolean enforceGate,
            double minLinePct,
            double minBranchPct,
            Path reportDir,
            String inspect,
            List<FileCoverage> canonicalClasses,
            Path sourceRoot)
            throws IOException {
        if (!filter.isEmpty()) {
            out.println("[FILTERED: " + filter + "]");
        }
        out.println(
                "=== BATEST RESULTS =============================================================");
        out.printf(Locale.ROOT, "Files : %-5d Cases : %d%n", fileCount, caseCount);
        out.printf(
                Locale.ROOT,
                "Interpret : %d / %d passed (%5.1f%%)%n",
                interpPassed,
                caseCount,
                pct(interpPassed, caseCount));
        out.printf(
                Locale.ROOT,
                "Compile   : %d / %d passed (%5.1f%%)%n",
                compilePassed,
                caseCount,
                pct(compilePassed, caseCount));
        if (hasCompileO0Outcomes) {
            out.printf(
                    Locale.ROOT,
                    "Compile -O0 : %d / %d passed (%5.1f%%)%n",
                    compileO0Passed,
                    caseCount,
                    pct(compileO0Passed, caseCount));
        }
        if (hasCompileO2Outcomes) {
            out.printf(
                    Locale.ROOT,
                    "Compile -O2 : %d / %d passed (%5.1f%%)%n",
                    compileO2Passed,
                    caseCount,
                    pct(compileO2Passed, caseCount));
        }
        int divergences = caseCount - parityMatched;
        out.printf(
                Locale.ROOT,
                "Parity    : %d / %d matched (%d %s)%n",
                parityMatched,
                caseCount,
                divergences,
                divergences == 1 ? "divergence" : "divergences");

        if (!failures.isEmpty()) {
            out.println();
            out.println("FAILURES (" + failures.size() + ") :");
            int shown = 0;
            for (FailureEntry f : failures) {
                if (shown >= 15) {
                    out.println("  ... and " + (failures.size() - shown) + " more failures");
                    break;
                }
                shown++;
                String caseName =
                        f.interpret() != null && !f.interpret().name().isEmpty()
                                ? f.interpret().name()
                                : (f.compile() != null && !f.compile().name().isEmpty()
                                        ? f.compile().name()
                                        : (f.compileO0() != null && !f.compileO0().name().isEmpty()
                                                ? f.compileO0().name()
                                                : (f.compileO2() != null
                                                        ? f.compileO2().name()
                                                        : "")));
                String caseLabel = caseName.isEmpty() ? "case #" + f.key().index() : caseName;
                out.printf(
                        "  [%s] %s:%d (%s)%n",
                        f.label(), f.key().file(), f.key().line(), caseLabel);
                out.println("    expected  : " + singleLine(f.expected()));
                if (f.interpret() != null) {
                    out.println(
                            "    interpret : "
                                    + singleLine(f.interpret().text())
                                    + " ["
                                    + f.interpret().kind()
                                    + "]");
                }
                if (f.compile() != null) {
                    out.println(
                            "    compile   : "
                                    + singleLine(f.compile().text())
                                    + " ["
                                    + f.compile().kind()
                                    + "]");
                    if (f.compile().irError() != null && !f.compile().irError().isEmpty()) {
                        out.println("    compile-ir: " + singleLine(f.compile().irError()));
                    }
                }
                if (f.compileO0() != null) {
                    out.println(
                            "    compile-O0: "
                                    + singleLine(f.compileO0().text())
                                    + " ["
                                    + f.compileO0().kind()
                                    + "]");
                    if (f.compileO0().irError() != null && !f.compileO0().irError().isEmpty()) {
                        out.println("    compile-O0-ir: " + singleLine(f.compileO0().irError()));
                    }
                }
                if (f.compileO2() != null) {
                    out.println(
                            "    compile-O2: "
                                    + singleLine(f.compileO2().text())
                                    + " ["
                                    + f.compileO2().kind()
                                    + "]");
                    if (f.compileO2().irError() != null && !f.compileO2().irError().isEmpty()) {
                        out.println("    compile-O2-ir: " + singleLine(f.compileO2().irError()));
                    }
                }
            }
        }

        out.println();
        out.println(
                "=== COVERAGE SUMMARY (.batest suite) ===========================================");
        if (enforceGate) {
            String gateCol = formatGateHeader(minLinePct, minBranchPct);
            out.printf(
                    Locale.ROOT,
                    "Scope                        Lines                 Branches              %s%n",
                    gateCol);
            out.println(
                    "---------------------------  --------------------  --------------------  "
                            + "-".repeat(Math.max(24, gateCol.length())));
        } else {
            out.println("Scope                        Lines                 Branches");
            out.println("---------------------------  --------------------  --------------------");
        }
        printScopeRow(
                out,
                Scope.INTERPRETER.label,
                scopeLines.get(Scope.INTERPRETER),
                scopeBranches.get(Scope.INTERPRETER),
                enforceGate,
                minLinePct,
                minBranchPct);
        printScopeRow(
                out,
                Scope.COMPILER.label,
                scopeLines.get(Scope.COMPILER),
                scopeBranches.get(Scope.COMPILER),
                enforceGate,
                minLinePct,
                minBranchPct);
        printScopeRow(
                out,
                Scope.OPTIMIZER.label,
                scopeLines.get(Scope.OPTIMIZER),
                scopeBranches.get(Scope.OPTIMIZER),
                enforceGate,
                minLinePct,
                minBranchPct);
        if (hasMirCov) {
            printScopeRow(
                    out,
                    Scope.OPTIMIZER_O2.label,
                    scopeLines.get(Scope.OPTIMIZER_O2),
                    scopeBranches.get(Scope.OPTIMIZER_O2),
                    enforceGate,
                    minLinePct,
                    minBranchPct);
        }
        printScopeRow(
                out,
                Scope.FRONT_END.label,
                scopeLines.get(Scope.FRONT_END),
                scopeBranches.get(Scope.FRONT_END),
                enforceGate,
                minLinePct,
                minBranchPct);
        printScopeRow(out, "Shared Runtime (interpret)", shInterpL, shInterpB, false, 0.0, 0.0);
        if (hasCompileO0Cov) {
            printScopeRow(
                    out, "Shared Runtime (compile-O0)", shCompO0L, shCompO0B, false, 0.0, 0.0);
        }
        printScopeRow(out, "Shared Runtime (compile-O1)", shCompL, shCompB, false, 0.0, 0.0);
        printScopeRow(
                out,
                Scope.SHARED_RUNTIME.label,
                scopeLines.get(Scope.SHARED_RUNTIME),
                scopeBranches.get(Scope.SHARED_RUNTIME),
                enforceGate,
                minLinePct,
                minBranchPct);

        out.println();
        String topLabel = topPerScope > 0 ? "top " + topPerScope + "/scope" : "all/scope";
        out.printf(
                Locale.ROOT,
                "=== UNCOVERED HOTSPOTS BY SCOPE (%s, ranked by %s) ===%n",
                topLabel,
                sortMode.description);

        for (Scope scope : REPORT_SCOPES) {
            int totalClasses = scopeClassCounts.getOrDefault(scope, 0);
            List<FileCoverage> list = hotspotsByScope.get(scope);
            if (totalClasses == 0 && list.isEmpty()) {
                continue;
            }
            Counter scL = scopeLines.get(scope);
            Counter scB = scopeBranches.get(scope);
            int neededBr = enforceGate ? branchesNeeded(scB, minBranchPct) : 0;
            int neededLn = enforceGate ? linesNeeded(scL, minLinePct) : 0;
            String brNeedTag =
                    neededBr > 0 ? String.format(Locale.ROOT, " [NEED +%d br]", neededBr) : "";
            String lnNeedTag =
                    neededLn > 0 ? String.format(Locale.ROOT, " [NEED +%d ln]", neededLn) : "";
            out.println();
            out.printf(
                    Locale.ROOT,
                    "-- %s : %d missed br%s, %d missed ln%s (%d uncovered %s) --%n",
                    scope.label,
                    scB.missed(),
                    brNeedTag,
                    scL.missed(),
                    lnNeedTag,
                    list.size(),
                    list.size() == 1 ? "file" : "files");
            if (list.isEmpty()) {
                out.println("  (100% covered - no missed lines or branches)");
                continue;
            }
            int limit = topPerScope > 0 ? Math.min(topPerScope, list.size()) : list.size();
            int nameWidth = 28;
            for (int i = 0; i < limit; i++) {
                nameWidth = Math.max(nameWidth, list.get(i).shortName().length());
            }
            nameWidth = Math.min(nameWidth, 42);
            String rowFmt = "  %2d. %-" + nameWidth + "s %4d br (%s) | %4d ln (%s) -> %s%n";
            for (int i = 0; i < limit; i++) {
                FileCoverage fc = list.get(i);
                String targets = formatTopTargets(fc, sortMode, 3);
                out.printf(
                        Locale.ROOT,
                        rowFmt,
                        i + 1,
                        fc.shortName(),
                        fc.branches.missed(),
                        formatPct(fc.branches),
                        fc.lines.missed(),
                        formatPct(fc.lines),
                        targets);
            }
            if (list.size() > limit) {
                int remBr = 0;
                int remLn = 0;
                for (int i = limit; i < list.size(); i++) {
                    remBr += list.get(i).branches.missed();
                    remLn += list.get(i).lines.missed();
                }
                int remFiles = list.size() - limit;
                out.printf(
                        Locale.ROOT,
                        "  ... and %d more uncovered %s (%d missed br, %d missed ln)%n",
                        remFiles,
                        remFiles == 1 ? "file" : "files",
                        remBr,
                        remLn);
            }
        }

        printSlowestFiles(out, slowestFiles, hasCompileO0Outcomes, hasCompileO2Outcomes);

        out.println();
        out.println("Full uncovered line/branch map : " + reportDir.resolve("uncovered.txt"));
        out.println(
                "Inspect class(es) (no re-run)  : mvn -q exec:java@batest-report -Pbatest -Dbatest.inspect=<Class[,Class2]> -Dbatest.minLinePct=0 -Dbatest.minBranchPct=0");

        if (!inspect.isEmpty()) {
            printInspect(out, inspect, canonicalClasses, sortMode, sourceRoot);
        }
    }

    static List<FileTiming> computeFileTimings(
            List<CaseKey> sortedKeys,
            Map<CaseKey, RecordedOutcome> interpretOutcomes,
            Map<CaseKey, RecordedOutcome> compileO0Outcomes,
            Map<CaseKey, RecordedOutcome> compileO1Outcomes,
            Map<CaseKey, RecordedOutcome> compileO2Outcomes) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CaseKey key : sortedKeys) {
            String file = key.file();
            counts.merge(file, 1, Integer::sum);
            double[] arr = sums.computeIfAbsent(file, k -> new double[4]);
            RecordedOutcome interp = interpretOutcomes.get(key);
            RecordedOutcome compO0 = compileO0Outcomes.get(key);
            RecordedOutcome compO1 = compileO1Outcomes.get(key);
            RecordedOutcome compO2 = compileO2Outcomes.get(key);
            if (interp != null) {
                arr[0] += interp.durationMs();
            }
            if (compO0 != null) {
                arr[1] += compO0.durationMs();
            }
            if (compO1 != null) {
                arr[2] += compO1.durationMs();
            }
            if (compO2 != null) {
                arr[3] += compO2.durationMs();
            }
        }
        List<FileTiming> list = new ArrayList<>(sums.size());
        for (Map.Entry<String, double[]> e : sums.entrySet()) {
            double[] arr = e.getValue();
            double total = arr[0] + arr[1] + arr[2] + arr[3];
            list.add(
                    new FileTiming(
                            e.getKey(),
                            counts.getOrDefault(e.getKey(), 0),
                            total,
                            arr[0],
                            arr[1],
                            arr[2],
                            arr[3]));
        }
        list.sort(
                Comparator.comparingDouble(FileTiming::totalMs)
                        .reversed()
                        .thenComparing(FileTiming::file));
        return list;
    }

    private static void printSlowestFiles(
            PrintStream out,
            List<FileTiming> slowestFiles,
            boolean hasCompileO0,
            boolean hasCompileO2) {
        if (slowestFiles.isEmpty() || slowestFiles.get(0).totalMs() <= 0.0) {
            return;
        }
        int limit = Math.min(DEFAULT_TOP_PER_SCOPE, slowestFiles.size());
        int nameWidth = 30;
        for (int i = 0; i < limit; i++) {
            nameWidth = Math.max(nameWidth, slowestFiles.get(i).file().length());
        }
        nameWidth = Math.min(nameWidth, 56);
        out.println();
        out.printf(
                Locale.ROOT,
                "=== SLOWEST .batest.md FILES (top %d by wall-clock time) ========================%n",
                limit);
        for (int i = 0; i < limit; i++) {
            FileTiming ft = slowestFiles.get(i);
            StringBuilder breakdown = new StringBuilder();
            breakdown.append(
                    String.format(Locale.ROOT, "interp %s", formatSeconds(ft.interpretMs())));
            if (hasCompileO0) {
                breakdown.append(
                        String.format(Locale.ROOT, " | -O0 %s", formatSeconds(ft.compileO0Ms())));
            }
            breakdown.append(
                    String.format(Locale.ROOT, " | -O1 %s", formatSeconds(ft.compileO1Ms())));
            if (hasCompileO2) {
                breakdown.append(
                        String.format(Locale.ROOT, " | -O2 %s", formatSeconds(ft.compileO2Ms())));
            }
            breakdown.append(String.format(Locale.ROOT, " | %d cases", ft.cases()));
            out.printf(
                    Locale.ROOT,
                    "  %2d. %-" + nameWidth + "s %7s  (%s)%n",
                    i + 1,
                    ft.file(),
                    formatSeconds(ft.totalMs()),
                    breakdown);
        }
    }

    private static String formatSeconds(double ms) {
        return String.format(Locale.ROOT, "%.2fs", ms / 1000.0);
    }

    private static String formatGateHeader(double minLinePct, double minBranchPct) {
        if (minLinePct > 0.0 && minBranchPct > 0.0) {
            if (Double.compare(minLinePct, minBranchPct) == 0) {
                return String.format(Locale.ROOT, "Gate (%.1f%% ln/br)", minLinePct);
            }
            return String.format(
                    Locale.ROOT, "Gate (%.1f%% ln, %.1f%% br)", minLinePct, minBranchPct);
        }
        if (minLinePct > 0.0) {
            return String.format(Locale.ROOT, "Gate (%.1f%% ln)", minLinePct);
        }
        return String.format(Locale.ROOT, "Gate (%.1f%% br)", minBranchPct);
    }

    static String formatGateStatus(
            Counter lines, Counter branches, double minLinePct, double minBranchPct) {
        int lnNeed = linesNeeded(lines, minLinePct);
        int brNeed = branchesNeeded(branches, minBranchPct);
        if (lnNeed > 0 && brNeed > 0) {
            return String.format(Locale.ROOT, "NEED +%d ln, +%d br", lnNeed, brNeed);
        }
        if (lnNeed > 0) {
            return String.format(Locale.ROOT, "NEED +%d ln", lnNeed);
        }
        if (brNeed > 0) {
            return String.format(Locale.ROOT, "NEED +%d br", brNeed);
        }
        return "PASS";
    }

    private static void printScopeRow(
            PrintStream out,
            String name,
            Counter lines,
            Counter branches,
            boolean showGate,
            double minLinePct,
            double minBranchPct) {
        if (showGate) {
            String gate = formatGateStatus(lines, branches, minLinePct, minBranchPct);
            out.printf(
                    Locale.ROOT,
                    "%-27s  %s  %s  %s%n",
                    name,
                    formatCounter(lines),
                    formatCounter(branches),
                    gate);
        } else {
            out.printf(
                    Locale.ROOT,
                    "%-27s  %s  %s%n",
                    name,
                    formatCounter(lines),
                    formatCounter(branches));
        }
    }

    private static String formatCounter(Counter c) {
        return String.format(Locale.ROOT, "%5.1f%% (%5d/%-5d)", c.pct(), c.covered(), c.total());
    }

    private static String formatPct(Counter c) {
        return c.total() == 0 ? "   n/a" : String.format(Locale.ROOT, "%5.1f%%", c.pct());
    }

    private static String formatTopTargets(FileCoverage fc, SortMode sortMode, int limit) {
        List<MethodCoverage> uncovered = fc.uncoveredMethods(sortMode);
        if (uncovered.isEmpty()) {
            return formatLineRanges(fc, limit);
        }
        int shown = Math.min(limit, uncovered.size());
        List<String> parts = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            MethodCoverage m = uncovered.get(i);
            int mb = m.branches().missed();
            int ml = m.lines().missed();
            String spec;
            if (mb > 0 && ml > 0) {
                spec = mb + "br/" + ml + "ln";
            } else if (mb > 0) {
                spec = mb + "br";
            } else {
                spec = ml + "ln";
            }
            parts.add(m.name() + ":L" + m.line() + "(" + spec + ")");
        }
        String joined = String.join(", ", parts);
        if (uncovered.size() > shown) {
            return joined + ", ... (+" + (uncovered.size() - shown) + ")";
        }
        return joined;
    }

    private static String formatLineRanges(FileCoverage fc, int limit) {
        List<Integer> lines = new ArrayList<>();
        for (LineInfo li : fc.lineMap.values()) {
            if (li.isMissedLine() || li.isPartialBranch()) {
                lines.add(li.line());
            }
        }
        if (lines.isEmpty()) {
            return "(none)";
        }
        List<String> ranges = collapseRanges(lines);
        if (ranges.size() > limit) {
            return String.join(", ", ranges.subList(0, limit)) + ", ...";
        }
        return String.join(", ", ranges);
    }

    private static List<String> collapseRanges(List<Integer> sortedLines) {
        List<String> out = new ArrayList<>();
        int start = sortedLines.get(0);
        int prev = start;
        for (int i = 1; i < sortedLines.size(); i++) {
            int cur = sortedLines.get(i);
            if (cur == prev + 1) {
                prev = cur;
            } else {
                out.add(start == prev ? "L" + start : "L" + start + "-" + prev);
                start = cur;
                prev = cur;
            }
        }
        out.add(start == prev ? "L" + start : "L" + start + "-" + prev);
        return out;
    }

    private static void writeUncoveredTxt(
            Path outPath,
            EnumMap<Scope, Counter> scopeLines,
            EnumMap<Scope, Counter> scopeBranches,
            EnumMap<Scope, List<FileCoverage>> hotspotsByScope,
            SortMode sortMode,
            boolean enforceGate,
            double minLinePct,
            double minBranchPct,
            Path sourceRoot)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Uncovered lines and branches grouped by scope, ranked by ")
                .append(sortMode.description)
                .append("\n\n");
        for (Scope scope : REPORT_SCOPES) {
            List<FileCoverage> list = hotspotsByScope.get(scope);
            if (list.isEmpty()) {
                continue;
            }
            Counter scL = scopeLines.get(scope);
            Counter scB = scopeBranches.get(scope);
            int neededBr = enforceGate ? branchesNeeded(scB, minBranchPct) : 0;
            int neededLn = enforceGate ? linesNeeded(scL, minLinePct) : 0;
            String brNeedTag =
                    neededBr > 0
                            ? String.format(
                                    Locale.ROOT, ", NEED +%d br for %.1f%%", neededBr, minBranchPct)
                            : "";
            String lnNeedTag =
                    neededLn > 0
                            ? String.format(
                                    Locale.ROOT, ", NEED +%d ln for %.1f%%", neededLn, minLinePct)
                            : "";
            sb.append(
                    "################################################################################\n");
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "# SCOPE: %s — branches %.1f%% (%d/%d, %d missed%s), lines %.1f%% (%d/%d, %d missed%s)%n",
                            scope.label,
                            scB.pct(),
                            scB.covered(),
                            scB.total(),
                            scB.missed(),
                            brNeedTag,
                            scL.pct(),
                            scL.covered(),
                            scL.total(),
                            scL.missed(),
                            lnNeedTag));
            sb.append(
                    "################################################################################\n\n");

            int rank = 0;
            for (FileCoverage fc : list) {
                rank++;
                long score = impactScore(fc.branches, fc.lines);
                sb.append("=== #")
                        .append(rank)
                        .append(' ')
                        .append(fc.qualifiedClass)
                        .append(" [")
                        .append(fc.scope.name())
                        .append("] ")
                        .append("missedBr=")
                        .append(fc.branches.missed())
                        .append(
                                String.format(
                                        Locale.ROOT,
                                        " (%s, %d/%d)",
                                        formatPct(fc.branches).trim(),
                                        fc.branches.covered(),
                                        fc.branches.total()))
                        .append(", missedLn=")
                        .append(fc.lines.missed())
                        .append(
                                String.format(
                                        Locale.ROOT,
                                        " (%s, %d/%d)",
                                        formatPct(fc.lines).trim(),
                                        fc.lines.covered(),
                                        fc.lines.total()))
                        .append(", score=")
                        .append(score)
                        .append(" ===\n");
                List<MethodCoverage> uncoveredMethods = fc.uncoveredMethods(sortMode);
                if (!uncoveredMethods.isEmpty()) {
                    sb.append("  Uncovered methods (ranked):\n");
                    for (MethodCoverage m : uncoveredMethods) {
                        sb.append(
                                String.format(
                                        Locale.ROOT,
                                        "    %-35s L%-5d missedBranches=%-3d missedLines=%-3d score=%d%n",
                                        m.name(),
                                        m.line(),
                                        m.branches().missed(),
                                        m.lines().missed(),
                                        impactScore(m.branches(), m.lines())));
                    }
                }
                List<String> srcLines = loadSourceLines(sourceRoot, fc);
                sb.append("  Missed/partial lines:\n");
                for (LineInfo li : fc.lineMap.values()) {
                    if (li.isMissedLine() || li.isPartialBranch()) {
                        String tag =
                                li.isMissedLine()
                                        ? "MISS  "
                                        : String.format("BR %d/%d", li.cb(), li.cb() + li.mb());
                        String text =
                                (srcLines != null && li.line() >= 1 && li.line() <= srcLines.size())
                                        ? srcLines.get(li.line() - 1)
                                        : "";
                        sb.append(String.format("    L%-5d [%s] %s%n", li.line(), tag, text));
                    }
                }
                sb.append('\n');
            }
        }
        Files.writeString(outPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void printInspect(
            PrintStream out,
            String rawQuery,
            List<FileCoverage> classes,
            SortMode sortMode,
            Path sourceRoot)
            throws IOException {
        for (String token : rawQuery.split(",")) {
            String query = token.trim();
            if (query.isEmpty()) {
                continue;
            }
            out.println();
            out.println(
                    "=== INSPECT: " + query + " ================================================");
            List<FileCoverage> matches =
                    classes.stream()
                            .filter(fc ->
                                    fc.qualifiedClass.equals(query)
                                            || fc.shortName().equals(query)
                                            || fc.qualifiedClass.endsWith("." + query))
                            .toList();
            if (matches.isEmpty()) {
                out.println("No tracked class matched '" + query + "'.");
                continue;
            }
            for (FileCoverage fc : matches) {
                out.printf(
                        Locale.ROOT,
                        "%s (%s) : branches %s (%d/%d, %d missed), lines %s (%d/%d, %d missed)%n",
                        fc.qualifiedClass,
                        fc.scope.name(),
                        formatPct(fc.branches).trim(),
                        fc.branches.covered(),
                        fc.branches.total(),
                        fc.branches.missed(),
                        formatPct(fc.lines).trim(),
                        fc.lines.covered(),
                        fc.lines.total(),
                        fc.lines.missed());
                List<MethodCoverage> uncoveredMethods = fc.uncoveredMethods(sortMode);
                if (!uncoveredMethods.isEmpty()) {
                    out.println("  Uncovered methods (ranked):");
                    for (MethodCoverage m : uncoveredMethods) {
                        out.printf(
                                Locale.ROOT,
                                "    %-35s L%-5d missedBranches=%-3d missedLines=%d%n",
                                m.name(),
                                m.line(),
                                m.branches().missed(),
                                m.lines().missed());
                    }
                }
                List<String> srcLines = loadSourceLines(sourceRoot, fc);
                int printed = 0;
                for (LineInfo li : fc.lineMap.values()) {
                    if (li.isMissedLine() || li.isPartialBranch()) {
                        String tag =
                                li.isMissedLine()
                                        ? "MISS  "
                                        : String.format("BR %d/%d", li.cb(), li.cb() + li.mb());
                        String text =
                                (srcLines != null && li.line() >= 1 && li.line() <= srcLines.size())
                                        ? srcLines.get(li.line() - 1)
                                        : "";
                        out.printf("  L%-5d [%s] %s%n", li.line(), tag, text);
                        printed++;
                    }
                }
                if (printed == 0) {
                    out.println("  100% covered (no missed lines or branches).");
                }
            }
        }
    }

    private static List<String> loadSourceLines(Path sourceRoot, FileCoverage fc)
            throws IOException {
        Path path =
                fc.packagePath.isEmpty()
                        ? sourceRoot.resolve(fc.sourceFile)
                        : sourceRoot.resolve(fc.packagePath).resolve(fc.sourceFile);
        return Files.isRegularFile(path) ? Files.readAllLines(path, StandardCharsets.UTF_8) : null;
    }

    private static void writeSummaryJson(
            Path outPath,
            String filter,
            int files,
            int cases,
            boolean hasCompileO0Outcomes,
            boolean hasCompileO2Outcomes,
            int interpPassed,
            int compilePassed,
            int compileO0Passed,
            int compileO2Passed,
            int parityMatched,
            List<FailureEntry> failures,
            Counter interpL,
            Counter interpB,
            Counter compL,
            Counter compB,
            Counter optL,
            Counter optB,
            boolean hasMirCov,
            Counter mirL,
            Counter mirB,
            Counter frontL,
            Counter frontB,
            Counter shInterpL,
            Counter shInterpB,
            boolean hasCompileO0Cov,
            Counter shCompO0L,
            Counter shCompO0B,
            Counter shCompL,
            Counter shCompB,
            Counter shUnionL,
            Counter shUnionB,
            List<FileCoverage> canonicalClasses,
            EnumMap<Scope, List<FileCoverage>> hotspotsByScope,
            SortMode sortMode)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"filter\": ").append(jsonString(filter)).append(",\n");
        sb.append("  \"sort\": ")
                .append(jsonString(sortMode.name().toLowerCase(Locale.ROOT)))
                .append(",\n");
        sb.append("  \"files\": ").append(files).append(",\n");
        sb.append("  \"cases\": ").append(cases).append(",\n");
        sb.append("  \"interpretPassed\": ").append(interpPassed).append(",\n");
        sb.append("  \"compilePassed\": ").append(compilePassed).append(",\n");
        if (hasCompileO0Outcomes) {
            sb.append("  \"compileO0Passed\": ").append(compileO0Passed).append(",\n");
        }
        if (hasCompileO2Outcomes) {
            sb.append("  \"compileO2Passed\": ").append(compileO2Passed).append(",\n");
        }
        sb.append("  \"parityMatched\": ").append(parityMatched).append(",\n");
        sb.append("  \"scopes\": {\n");
        sb.append("    \"interpreter\": ").append(scopeJson(interpL, interpB)).append(",\n");
        sb.append("    \"compiler\": ").append(scopeJson(compL, compB)).append(",\n");
        sb.append("    \"optimizer\": ").append(scopeJson(optL, optB)).append(",\n");
        if (hasMirCov) {
            sb.append("    \"optimizerO2\": ").append(scopeJson(mirL, mirB)).append(",\n");
        }
        sb.append("    \"frontEnd\": ").append(scopeJson(frontL, frontB)).append(",\n");
        sb.append("    \"sharedRuntimeInterpret\": ")
                .append(scopeJson(shInterpL, shInterpB))
                .append(",\n");
        if (hasCompileO0Cov) {
            sb.append("    \"sharedRuntimeCompileO0\": ")
                    .append(scopeJson(shCompO0L, shCompO0B))
                    .append(",\n");
        }
        sb.append("    \"sharedRuntimeCompile\": ")
                .append(scopeJson(shCompL, shCompB))
                .append(",\n");
        sb.append("    \"sharedRuntimeCombined\": ")
                .append(scopeJson(shUnionL, shUnionB))
                .append("\n");
        sb.append("  },\n");
        sb.append("  \"failures\": [");
        for (int i = 0; i < failures.size(); i++) {
            FailureEntry f = failures.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\n    {\"file\": ")
                    .append(jsonString(f.key().file()))
                    .append(", \"index\": ")
                    .append(f.key().index())
                    .append(", \"line\": ")
                    .append(f.key().line())
                    .append(", \"type\": ")
                    .append(jsonString(f.label()))
                    .append(", \"expected\": ")
                    .append(jsonString(f.expected()))
                    .append(", \"interpret\": ")
                    .append(f.interpret() == null ? "null" : jsonString(f.interpret().text()))
                    .append(", \"compile\": ")
                    .append(f.compile() == null ? "null" : jsonString(f.compile().text()));
            if (f.compileO0() != null || hasCompileO0Outcomes) {
                sb.append(", \"compileO0\": ")
                        .append(f.compileO0() == null ? "null" : jsonString(f.compileO0().text()));
            }
            if (f.compileO2() != null || hasCompileO2Outcomes) {
                sb.append(", \"compileO2\": ")
                        .append(f.compileO2() == null ? "null" : jsonString(f.compileO2().text()));
            }
            sb.append('}');
        }
        if (!failures.isEmpty()) {
            sb.append("\n  ");
        }
        sb.append("],\n");
        sb.append("  \"hotspotsByScope\": {\n");
        for (int s = 0; s < REPORT_SCOPES.size(); s++) {
            Scope scope = REPORT_SCOPES.get(s);
            List<FileCoverage> list = hotspotsByScope.get(scope);
            sb.append("    ").append(jsonString(scope.jsonKey)).append(": [");
            for (int i = 0; i < list.size(); i++) {
                FileCoverage fc = list.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("\n      {\"class\": ")
                        .append(jsonString(fc.qualifiedClass))
                        .append(", \"branchesMissed\": ")
                        .append(fc.branches.missed())
                        .append(", \"branchesTotal\": ")
                        .append(fc.branches.total())
                        .append(", \"linesMissed\": ")
                        .append(fc.lines.missed())
                        .append(", \"linesTotal\": ")
                        .append(fc.lines.total())
                        .append(", \"score\": ")
                        .append(impactScore(fc.branches, fc.lines))
                        .append('}');
            }
            if (!list.isEmpty()) {
                sb.append("\n    ");
            }
            sb.append(']');
            if (s + 1 < REPORT_SCOPES.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  },\n");
        sb.append("  \"classes\": [");
        for (int i = 0; i < canonicalClasses.size(); i++) {
            FileCoverage fc = canonicalClasses.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\n    {\"class\": ")
                    .append(jsonString(fc.qualifiedClass))
                    .append(", \"scope\": ")
                    .append(jsonString(fc.scope.name()))
                    .append(", \"linesCovered\": ")
                    .append(fc.lines.covered())
                    .append(", \"linesTotal\": ")
                    .append(fc.lines.total())
                    .append(", \"linesMissed\": ")
                    .append(fc.lines.missed())
                    .append(", \"branchesCovered\": ")
                    .append(fc.branches.covered())
                    .append(", \"branchesTotal\": ")
                    .append(fc.branches.total())
                    .append(", \"branchesMissed\": ")
                    .append(fc.branches.missed())
                    .append(", \"score\": ")
                    .append(impactScore(fc.branches, fc.lines))
                    .append('}');
        }
        if (!canonicalClasses.isEmpty()) {
            sb.append("\n  ");
        }
        sb.append("]\n}\n");
        Files.writeString(outPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String scopeJson(Counter lines, Counter branches) {
        return String.format(
                Locale.ROOT,
                "{\"linesCovered\": %d, \"linesTotal\": %d, \"linePct\": %.2f, \"branchesCovered\": %d, \"branchesTotal\": %d, \"branchPct\": %.2f, \"linesMissed\": %d, \"branchesMissed\": %d}",
                lines.covered(),
                lines.total(),
                lines.pct(),
                branches.covered(),
                branches.total(),
                branches.pct(),
                lines.missed(),
                branches.missed());
    }

    private static String jsonString(String s) {
        return "\"" + BatestTestCase.escapeJson(s) + "\"";
    }

    private static String singleLine(String s) {
        return s == null ? "" : s.replace("\n", "\\n");
    }

    private static double pct(int num, int den) {
        return den == 0 ? 100.0 : (num * 100.0) / den;
    }

    static Map<CaseKey, RecordedOutcome> readOutcomes(Path path) throws IOException {
        Map<CaseKey, RecordedOutcome> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return map;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> fields = parseFlatJsonObject(line);
            CaseKey key =
                    new CaseKey(
                            fields.get("file"),
                            Integer.parseInt(fields.get("index")),
                            Integer.parseInt(fields.get("line")));
            double durationMs = 0.0;
            String rawDuration = fields.get("durationMs");
            if (rawDuration != null && !rawDuration.isBlank()) {
                durationMs = Double.parseDouble(rawDuration);
            }
            map.put(
                    key,
                    new RecordedOutcome(
                            key,
                            fields.getOrDefault("name", ""),
                            fields.getOrDefault("expected", ""),
                            fields.getOrDefault("kind", ""),
                            fields.getOrDefault("text", ""),
                            Boolean.parseBoolean(fields.getOrDefault("passed", "false")),
                            fields.get("crash"),
                            fields.get("irError"),
                            durationMs));
        }
        return map;
    }

    static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        int i = 0;
        int n = json.length();
        while (i < n && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= n || json.charAt(i) != '{') {
            throw new IllegalArgumentException("Expected JSON object: " + json);
        }
        i++;
        while (i < n) {
            while (i < n && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) {
                i++;
            }
            if (i < n && json.charAt(i) == '}') {
                break;
            }
            if (json.charAt(i) != '"') {
                throw new IllegalArgumentException("Expected key quote at " + i + " in " + json);
            }
            int[] nextIdx = new int[1];
            String key = readJsonString(json, i, nextIdx);
            i = nextIdx[0];
            while (i < n && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= n || json.charAt(i) != ':') {
                throw new IllegalArgumentException("Expected ':' at " + i + " in " + json);
            }
            i++;
            while (i < n && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            String value;
            if (json.charAt(i) == '"') {
                value = readJsonString(json, i, nextIdx);
                i = nextIdx[0];
            } else {
                int start = i;
                while (i < n
                        && json.charAt(i) != ','
                        && json.charAt(i) != '}'
                        && !Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
                value = json.substring(start, i);
            }
            map.put(key, value);
        }
        return map;
    }

    private static String readJsonString(String json, int startQuote, int[] nextIdx) {
        StringBuilder sb = new StringBuilder();
        int i = startQuote + 1;
        int n = json.length();
        while (i < n) {
            char c = json.charAt(i++);
            if (c == '"') {
                nextIdx[0] = i;
                return sb.toString();
            }
            if (c == '\\' && i < n) {
                char esc = json.charAt(i++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 <= n) {
                            sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string in " + json);
    }
}
