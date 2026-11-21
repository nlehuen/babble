package org.babblelang.tests.batest;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates unified dual-mode test, parity, and JaCoCo coverage reports for {@code -Pbatest}
 * (BIP009 §5.2, §5.3). Reads {@code outcomes-interpret.jsonl}, {@code outcomes-compile.jsonl},
 * and the two isolated {@code jacoco.xml} reports, writes {@code summary.json} and
 * {@code uncovered.txt}, prints a compact console summary (plus {@code -Dbatest.inspect=<Class>}
 * details when requested), and fails the build if any case failed or diverged.
 */
public final class BatestCoverageReporter {

    public enum Scope {
        INTERPRETER("Interpreter (impl.*)"),
        COMPILER("Compiler (codegen+indy)"),
        FRONT_END("Shared Front-End (analysis)"),
        SHARED_RUNTIME("Shared Runtime & Natives"),
        EXCLUDED("Excluded");

        final String label;

        Scope(String label) {
            this.label = label;
        }
    }

    private static final Set<String> EXCLUDED_CLASSES = Set.of(
            "org.babblelang.Main",
            "org.babblelang.Repl",
            "org.babblelang.Babblefmt",
            "org.babblelang.analysis.AxiomChecker",
            "org.babblelang.analysis.BytecodeEffectAnalyzer",
            "org.babblelang.compiler.Babblec",
            "org.babblelang.compiler.FileModuleCompiler",
            "org.babblelang.compiler.Debugging",
            "org.babblelang.runtime.Launcher",
            "org.babblelang.runtime.ProgramIndex",
            "org.babblelang.engine.FileModuleSource",
            "org.babblelang.engine.ClassPathModuleSource"
    );

    private static final Set<String> COMPILER_RUNTIME_CLASSES = Set.of(
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
            "org.babblelang.runtime.CharCell"
    );

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

    public record RecordedOutcome(CaseKey key, String expected, String kind, String text, boolean passed, String crash) {
    }

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

    record LineInfo(int line, int mi, int ci, int mb, int cb) {
        boolean isMissedLine() {
            return ci == 0 && mi > 0;
        }

        boolean isPartialBranch() {
            return mb > 0;
        }
    }

    record MethodCoverage(String name, int line, Counter lines, Counter branches) {
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
            return qualifiedClass.startsWith(prefix) ? qualifiedClass.substring(prefix.length()) : qualifiedClass;
        }
    }

    record FailureEntry(CaseKey key, String label, String expected, RecordedOutcome interpret, RecordedOutcome compile,
                        RecordedOutcome compileO0) {
    }

    private BatestCoverageReporter() {
    }

    public static void main(String[] args) throws Exception {
        Path reportDir = Path.of(System.getProperty("batest.report", "target/batest-report"));
        Path xmlInterpret = Path.of(System.getProperty("batest.jacocoInterpret", "target/site/jacoco-interpret/jacoco.xml"));
        Path xmlCompile = Path.of(System.getProperty("batest.jacocoCompile", "target/site/jacoco-compile/jacoco.xml"));
        Path xmlCompileO0 = Path.of(System.getProperty("batest.jacocoCompileO0", "target/site/jacoco-compile-O0/jacoco.xml"));
        Path sourceRoot = Path.of(System.getProperty("batest.sourceRoot", "src/main/java"));
        String filter = System.getProperty("batest.filter", "").trim();
        String inspect = System.getProperty("batest.inspect", "").trim();
        double minBranchPct = Double.parseDouble(System.getProperty("batest.minBranchPct", "75.0").trim());

        run(reportDir, xmlInterpret, xmlCompile, xmlCompileO0, sourceRoot, filter, inspect, minBranchPct);
    }

    static void run(Path reportDir, Path xmlInterpret, Path xmlCompile, Path sourceRoot,
                    String filter, String inspect) throws Exception {
        run(reportDir, xmlInterpret, xmlCompile, null, sourceRoot, filter, inspect, 0.0);
    }

    static void run(Path reportDir, Path xmlInterpret, Path xmlCompile, Path sourceRoot,
                    String filter, String inspect, double minBranchPct) throws Exception {
        run(reportDir, xmlInterpret, xmlCompile, null, sourceRoot, filter, inspect, minBranchPct);
    }

    static void run(Path reportDir, Path xmlInterpret, Path xmlCompile, Path xmlCompileO0, Path sourceRoot,
                    String filter, String inspect, double minBranchPct) throws Exception {
        Files.createDirectories(reportDir);

        Map<CaseKey, RecordedOutcome> interpretOutcomes = readOutcomes(reportDir.resolve("outcomes-interpret.jsonl"));
        Map<CaseKey, RecordedOutcome> compileOutcomes = readOutcomes(reportDir.resolve("outcomes-compile.jsonl"));
        Path compileO0Path = reportDir.resolve("outcomes-compile-O0.jsonl");
        boolean hasCompileO0Outcomes = Files.isRegularFile(compileO0Path);
        Map<CaseKey, RecordedOutcome> compileO0Outcomes = hasCompileO0Outcomes ? readOutcomes(compileO0Path) : Map.of();

        Set<CaseKey> allKeys = new LinkedHashSet<>();
        allKeys.addAll(interpretOutcomes.keySet());
        allKeys.addAll(compileOutcomes.keySet());
        if (hasCompileO0Outcomes) {
            allKeys.addAll(compileO0Outcomes.keySet());
        }
        List<CaseKey> sortedKeys = new ArrayList<>(allKeys);
        sortedKeys.sort(Comparator.naturalOrder());

        Set<String> filesSeen = new LinkedHashSet<>();
        int interpPassed = 0;
        int compilePassed = 0;
        int compileO0Passed = 0;
        int parityMatched = 0;
        List<FailureEntry> failures = new ArrayList<>();

        for (CaseKey key : sortedKeys) {
            filesSeen.add(key.file());
            RecordedOutcome interp = interpretOutcomes.get(key);
            RecordedOutcome comp = compileOutcomes.get(key);
            RecordedOutcome compO0 = hasCompileO0Outcomes ? compileO0Outcomes.get(key) : null;

            if (interp != null && interp.passed()) {
                interpPassed++;
            }
            if (comp != null && comp.passed()) {
                compilePassed++;
            }
            if (compO0 != null && compO0.passed()) {
                compileO0Passed++;
            }

            boolean interpCrash = interp != null && "INTERNAL_CRASH".equals(interp.kind());
            boolean compCrash = comp != null && "INTERNAL_CRASH".equals(comp.kind());
            boolean compO0Crash = compO0 != null && "INTERNAL_CRASH".equals(compO0.kind());
            boolean parity = interp != null && comp != null && !interpCrash && !compCrash
                    && interp.kind().equals(comp.kind())
                    && interp.text().equals(comp.text())
                    && (!hasCompileO0Outcomes || (compO0 != null && !compO0Crash
                    && interp.kind().equals(compO0.kind())
                    && interp.text().equals(compO0.text())));
            if (parity) {
                parityMatched++;
            }

            boolean allPass = interp != null && comp != null && interp.passed() && comp.passed()
                    && (!hasCompileO0Outcomes || (compO0 != null && compO0.passed()))
                    && parity;
            if (!allPass) {
                String label;
                if (interpCrash || compCrash || compO0Crash) {
                    label = "INTERNAL CRASH";
                } else if (interp == null || comp == null || (hasCompileO0Outcomes && compO0 == null)) {
                    label = "MISSING EXECUTION";
                } else if (!parity) {
                    label = "PARITY MISMATCH";
                } else if (!comp.passed() && interp.passed() && (!hasCompileO0Outcomes || compO0.passed())) {
                    label = "COMPILE MISMATCH";
                } else if (hasCompileO0Outcomes && !compO0.passed() && interp.passed() && comp.passed()) {
                    label = "COMPILE -O0 MISMATCH";
                } else if (!interp.passed() && comp.passed() && (!hasCompileO0Outcomes || compO0.passed())) {
                    label = "INTERPRET MISMATCH";
                } else {
                    label = "EXPECTATION MISMATCH";
                }
                String expected = interp != null ? interp.expected()
                        : (comp != null ? comp.expected() : compO0.expected());
                failures.add(new FailureEntry(key, label, expected, interp, comp, compO0));
            }
        }

        Map<String, FileCoverage> interpCov = Files.isRegularFile(xmlInterpret)
                ? parseJacocoXml(xmlInterpret) : Map.of();
        Map<String, FileCoverage> compileCov = Files.isRegularFile(xmlCompile)
                ? parseJacocoXml(xmlCompile) : Map.of();
        boolean hasCompileO0Cov = xmlCompileO0 != null && Files.isRegularFile(xmlCompileO0);
        Map<String, FileCoverage> compileO0Cov = hasCompileO0Cov ? parseJacocoXml(xmlCompileO0) : Map.of();

        Counter[] scopeInterpLines = new Counter[]{new Counter(0, 0)};
        Counter[] scopeInterpBranches = new Counter[]{new Counter(0, 0)};
        Counter[] scopeCompileLines = new Counter[]{new Counter(0, 0)};
        Counter[] scopeCompileBranches = new Counter[]{new Counter(0, 0)};
        Counter[] scopeCompileO0Lines = new Counter[]{new Counter(0, 0)};
        Counter[] scopeCompileO0Branches = new Counter[]{new Counter(0, 0)};
        Counter[] scopeFrontLines = new Counter[]{new Counter(0, 0)};
        Counter[] scopeFrontBranches = new Counter[]{new Counter(0, 0)};
        Counter[] sharedInterpLines = new Counter[]{new Counter(0, 0)};
        Counter[] sharedInterpBranches = new Counter[]{new Counter(0, 0)};
        Counter[] sharedCompileLines = new Counter[]{new Counter(0, 0)};
        Counter[] sharedCompileBranches = new Counter[]{new Counter(0, 0)};
        Counter[] sharedCompileO0Lines = new Counter[]{new Counter(0, 0)};
        Counter[] sharedCompileO0Branches = new Counter[]{new Counter(0, 0)};
        Counter[] sharedUnionLines = new Counter[]{new Counter(0, 0)};
        Counter[] sharedUnionBranches = new Counter[]{new Counter(0, 0)};

        for (FileCoverage fc : interpCov.values()) {
            if (fc.scope == Scope.INTERPRETER) {
                scopeInterpLines[0] = scopeInterpLines[0].plus(fc.lines);
                scopeInterpBranches[0] = scopeInterpBranches[0].plus(fc.branches);
            } else if (fc.scope == Scope.SHARED_RUNTIME) {
                sharedInterpLines[0] = sharedInterpLines[0].plus(fc.lines);
                sharedInterpBranches[0] = sharedInterpBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : compileCov.values()) {
            if (fc.scope == Scope.COMPILER) {
                scopeCompileLines[0] = scopeCompileLines[0].plus(fc.lines);
                scopeCompileBranches[0] = scopeCompileBranches[0].plus(fc.branches);
            } else if (fc.scope == Scope.FRONT_END) {
                scopeFrontLines[0] = scopeFrontLines[0].plus(fc.lines);
                scopeFrontBranches[0] = scopeFrontBranches[0].plus(fc.branches);
            } else if (fc.scope == Scope.SHARED_RUNTIME) {
                sharedCompileLines[0] = sharedCompileLines[0].plus(fc.lines);
                sharedCompileBranches[0] = sharedCompileBranches[0].plus(fc.branches);
            }
        }

        for (FileCoverage fc : compileO0Cov.values()) {
            if (fc.scope == Scope.COMPILER) {
                scopeCompileO0Lines[0] = scopeCompileO0Lines[0].plus(fc.lines);
                scopeCompileO0Branches[0] = scopeCompileO0Branches[0].plus(fc.branches);
            } else if (fc.scope == Scope.SHARED_RUNTIME) {
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
        for (FileCoverage fc : compileCov.values()) {
            if (fc.scope == Scope.COMPILER || fc.scope == Scope.FRONT_END) {
                canonicalClasses.add(fc);
            }
        }
        canonicalClasses.addAll(sharedCombinedMap.values());

        List<FileCoverage> hotspots = canonicalClasses.stream()
                .filter(fc -> fc.branches.missed() > 0)
                .sorted(Comparator
                        .comparingDouble((FileCoverage fc) -> fc.branches.pct())
                        .thenComparing(Comparator.comparingInt((FileCoverage fc) -> fc.branches.missed()).reversed())
                        .thenComparing(fc -> fc.qualifiedClass))
                .toList();

        writeUncoveredTxt(reportDir.resolve("uncovered.txt"), canonicalClasses, sourceRoot);
        writeSummaryJson(reportDir.resolve("summary.json"), filter, filesSeen.size(), sortedKeys.size(),
                hasCompileO0Outcomes, interpPassed, compilePassed, compileO0Passed, parityMatched, failures,
                scopeInterpLines[0], scopeInterpBranches[0],
                scopeCompileLines[0], scopeCompileBranches[0],
                hasCompileO0Cov, scopeCompileO0Lines[0], scopeCompileO0Branches[0],
                scopeFrontLines[0], scopeFrontBranches[0],
                sharedInterpLines[0], sharedInterpBranches[0],
                sharedCompileLines[0], sharedCompileBranches[0],
                sharedCompileO0Lines[0], sharedCompileO0Branches[0],
                sharedUnionLines[0], sharedUnionBranches[0],
                canonicalClasses);

        printConsoleSummary(filter, filesSeen.size(), sortedKeys.size(),
                hasCompileO0Outcomes, interpPassed, compilePassed, compileO0Passed, parityMatched, failures,
                scopeInterpLines[0], scopeInterpBranches[0],
                scopeCompileLines[0], scopeCompileBranches[0],
                hasCompileO0Cov, scopeCompileO0Lines[0], scopeCompileO0Branches[0],
                scopeFrontLines[0], scopeFrontBranches[0],
                sharedInterpLines[0], sharedInterpBranches[0],
                sharedCompileLines[0], sharedCompileBranches[0],
                sharedCompileO0Lines[0], sharedCompileO0Branches[0],
                sharedUnionLines[0], sharedUnionBranches[0],
                hotspots, reportDir, inspect, canonicalClasses, sourceRoot);

        if (sortedKeys.isEmpty()) {
            throw new IllegalStateException("No .batest cases were recorded in " + reportDir);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " .batest case failure(s)");
        }
        if (filter.isEmpty() && minBranchPct > 0.0) {
            List<String> below = new ArrayList<>();
            checkBranchThreshold(below, "Interpreter (impl.*)", scopeInterpBranches[0], minBranchPct);
            checkBranchThreshold(below, "Compiler (codegen+indy)", scopeCompileBranches[0], minBranchPct);
            if (hasCompileO0Cov) {
                checkBranchThreshold(below, "Compiler (-O0)", scopeCompileO0Branches[0], minBranchPct);
            }
            checkBranchThreshold(below, "Shared Front-End (analysis)", scopeFrontBranches[0], minBranchPct);
            checkBranchThreshold(below, "Shared Runtime (combined)", sharedUnionBranches[0], minBranchPct);
            if (!below.isEmpty()) {
                throw new AssertionError(String.format(Locale.ROOT,
                        "Branch coverage below %.1f%% threshold: %s", minBranchPct, String.join(", ", below)));
            }
        }
    }

    private static void checkBranchThreshold(List<String> below, String scope, Counter branches, double minPct) {
        if (branches.total() > 0 && branches.pct() < minPct) {
            below.add(String.format(Locale.ROOT, "%s=%.1f%% (%d/%d)",
                    scope, branches.pct(), branches.covered(), branches.total()));
        }
    }

    private static FileCoverage combineShared(FileCoverage ref, FileCoverage... modes) {
        FileCoverage union = new FileCoverage(ref.qualifiedClass, ref.packagePath, ref.sourceFile, Scope.SHARED_RUNTIME);
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
            union.lineMap.put(nr, new LineInfo(nr, coveredLine ? 0 : mi, coveredLine ? Math.max(1, ci) : 0, mb, cb));
        }
        union.lines = new Counter(lineMissed, lineCovered);
        union.branches = new Counter(branchMissed, branchCovered);

        // Merge method branch coverage optimistically (max covered per method name+line)
        Map<String, MethodCoverage> mergedMethods = new LinkedHashMap<>();
        for (FileCoverage fc : modes) {
            if (fc == null) {
                continue;
            }
            for (MethodCoverage m : fc.methods) {
                String k = m.name() + "@" + m.line();
                MethodCoverage prev = mergedMethods.get(k);
                if (prev == null) {
                    mergedMethods.put(k, m);
                } else {
                    int totL = Math.max(m.lines().total(), prev.lines().total());
                    int covL = Math.max(m.lines().covered(), prev.lines().covered());
                    int totB = Math.max(m.branches().total(), prev.branches().total());
                    int covB = Math.max(m.branches().covered(), prev.branches().covered());
                    mergedMethods.put(k, new MethodCoverage(m.name(), m.line(),
                            new Counter(Math.max(0, totL - covL), covL),
                            new Counter(Math.max(0, totB - covB), covB)));
                }
            }
        }
        union.methods.addAll(mergedMethods.values());
        return union;
    }

    static Scope scopeOf(String qualifiedClass) {
        if (qualifiedClass.startsWith("org.babblelang.parser.")
                || qualifiedClass.startsWith("org.babblelang.format.")
                || EXCLUDED_CLASSES.contains(qualifiedClass)) {
            return Scope.EXCLUDED;
        }
        if (qualifiedClass.startsWith("org.babblelang.engine.impl.natives.")) {
            return Scope.SHARED_RUNTIME;
        }
        if (qualifiedClass.startsWith("org.babblelang.engine.impl.")) {
            return Scope.INTERPRETER;
        }
        if (qualifiedClass.startsWith("org.babblelang.compiler.")
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
                    String baseName = srcName.endsWith(".java")
                            ? srcName.substring(0, srcName.length() - ".java".length()) : srcName;
                    String qualified = pkgDot + baseName;
                    Scope scope = scopeOf(qualified);
                    if (scope == Scope.EXCLUDED) {
                        continue;
                    }
                    FileCoverage fc = bySource.computeIfAbsent(srcName,
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
                    if (!(clsKids.item(k) instanceof Element mEl) || !"method".equals(mEl.getTagName())) {
                        continue;
                    }
                    String mName = mEl.getAttribute("name");
                    int mLine = parseIntAttr(mEl, "line");
                    Counter mLines = new Counter(0, 0);
                    Counter mBranches = new Counter(0, 0);
                    NodeList mCounters = mEl.getChildNodes();
                    for (int c = 0; c < mCounters.getLength(); c++) {
                        if (mCounters.item(c) instanceof Element cEl && "counter".equals(cEl.getTagName())) {
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
                    if (mLines.missed() > 0 || mBranches.missed() > 0) {
                        String displayMethod = clsSimple.contains("$") ? clsSimple + "." + mName : mName;
                        fc.methods.add(new MethodCoverage(displayMethod, mLine, mLines, mBranches));
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
            String filter, int fileCount, int caseCount,
            boolean hasCompileO0Outcomes, int interpPassed, int compilePassed, int compileO0Passed,
            int parityMatched, List<FailureEntry> failures,
            Counter interpL, Counter interpB,
            Counter compL, Counter compB,
            boolean hasCompileO0Cov, Counter compO0L, Counter compO0B,
            Counter frontL, Counter frontB,
            Counter shInterpL, Counter shInterpB,
            Counter shCompL, Counter shCompB,
            Counter shCompO0L, Counter shCompO0B,
            Counter shUnionL, Counter shUnionB,
            List<FileCoverage> hotspots, Path reportDir, String inspect,
            List<FileCoverage> canonicalClasses, Path sourceRoot) throws IOException {
        if (!filter.isEmpty()) {
            System.out.println("[FILTERED: " + filter + "]");
        }
        System.out.println("=== BATEST RESULTS =============================================================");
        System.out.printf(Locale.ROOT, "Files : %-5d Cases : %d%n", fileCount, caseCount);
        System.out.printf(Locale.ROOT, "Interpret : %d / %d passed (%5.1f%%)%n",
                interpPassed, caseCount, pct(interpPassed, caseCount));
        System.out.printf(Locale.ROOT, "Compile   : %d / %d passed (%5.1f%%)%n",
                compilePassed, caseCount, pct(compilePassed, caseCount));
        if (hasCompileO0Outcomes) {
            System.out.printf(Locale.ROOT, "Compile -O0 : %d / %d passed (%5.1f%%)%n",
                    compileO0Passed, caseCount, pct(compileO0Passed, caseCount));
        }
        int divergences = caseCount - parityMatched;
        System.out.printf(Locale.ROOT, "Parity    : %d / %d matched (%d %s)%n",
                parityMatched, caseCount, divergences, divergences == 1 ? "divergence" : "divergences");

        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("FAILURES (" + failures.size() + ") :");
            int shown = 0;
            for (FailureEntry f : failures) {
                if (shown >= 15) {
                    System.out.println("  ... and " + (failures.size() - shown) + " more failures");
                    break;
                }
                shown++;
                System.out.printf("  [%s] %s:%d (case #%d)%n", f.label(), f.key().file(), f.key().line(), f.key().index());
                System.out.println("    expected  : " + singleLine(f.expected()));
                if (f.interpret() != null) {
                    System.out.println("    interpret : " + singleLine(f.interpret().text()) + " [" + f.interpret().kind() + "]");
                }
                if (f.compile() != null) {
                    System.out.println("    compile   : " + singleLine(f.compile().text()) + " [" + f.compile().kind() + "]");
                }
                if (f.compileO0() != null) {
                    System.out.println("    compile-O0: " + singleLine(f.compileO0().text()) + " [" + f.compileO0().kind() + "]");
                }
            }
        }

        System.out.println();
        System.out.println("=== COVERAGE SUMMARY (.batest suite) ===========================================");
        System.out.println("Scope                        Lines                Branches");
        System.out.println("---------------------------  -------------------  -------------------");
        printScopeRow("Interpreter (impl.*)", interpL, interpB);
        printScopeRow("Compiler (codegen+indy)", compL, compB);
        if (hasCompileO0Cov) {
            printScopeRow("Compiler (-O0)", compO0L, compO0B);
        }
        printScopeRow("Shared Front-End (analysis)", frontL, frontB);
        printScopeRow("Shared Runtime (interpret)", shInterpL, shInterpB);
        printScopeRow("Shared Runtime (compile)", shCompL, shCompB);
        if (hasCompileO0Cov) {
            printScopeRow("Shared Runtime (compile-O0)", shCompO0L, shCompO0B);
        }
        printScopeRow("Shared Runtime (combined)", shUnionL, shUnionB);

        System.out.println();
        System.out.println("=== LOWEST BRANCH COVERAGE HOTSPOTS (top 10) ===================================");
        int top = Math.min(10, hotspots.size());
        for (int i = 0; i < top; i++) {
            FileCoverage fc = hotspots.get(i);
            String ranges = formatLineRanges(fc, 3);
            System.out.printf(Locale.ROOT, "  %2d. %-28s %5.1f%% branches (%3d missed) -> %s%n",
                    i + 1, fc.shortName(), fc.branches.pct(), fc.branches.missed(), ranges);
        }
        System.out.println("Full uncovered line/branch map : " + reportDir.resolve("uncovered.txt"));
        System.out.println("Inspect a single class         : mvn -q verify -Pbatest -Dbatest.inspect=<Class>");

        if (!inspect.isEmpty()) {
            printInspect(inspect, canonicalClasses, sourceRoot);
        }
    }

    private static void printScopeRow(String name, Counter lines, Counter branches) {
        System.out.printf(Locale.ROOT, "%-27s  %s  %s%n",
                name, formatCounter(lines), formatCounter(branches));
    }

    private static String formatCounter(Counter c) {
        return String.format(Locale.ROOT, "%5.1f%% (%4d/%-4d)", c.pct(), c.covered(), c.total());
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

    private static void writeUncoveredTxt(Path outPath, List<FileCoverage> classes, Path sourceRoot) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (FileCoverage fc : classes) {
            if (fc.lines.missed() == 0 && fc.branches.missed() == 0) {
                continue;
            }
            sb.append("=== ").append(fc.qualifiedClass)
                    .append(" [").append(fc.scope.name()).append("] ")
                    .append("lines=").append(String.format(Locale.ROOT, "%.1f%% (%d/%d)", fc.lines.pct(), fc.lines.covered(), fc.lines.total()))
                    .append(", branches=").append(String.format(Locale.ROOT, "%.1f%% (%d/%d)", fc.branches.pct(), fc.branches.covered(), fc.branches.total()))
                    .append(" ===\n");
            if (!fc.methods.isEmpty()) {
                sb.append("  Uncovered methods:\n");
                for (MethodCoverage m : fc.methods) {
                    sb.append(String.format(Locale.ROOT, "    %-35s L%-5d missedLines=%-3d missedBranches=%d%n",
                            m.name(), m.line(), m.lines().missed(), m.branches().missed()));
                }
            }
            List<String> srcLines = loadSourceLines(sourceRoot, fc);
            sb.append("  Missed/partial lines:\n");
            for (LineInfo li : fc.lineMap.values()) {
                if (li.isMissedLine() || li.isPartialBranch()) {
                    String tag = li.isMissedLine() ? "MISS  " : String.format("BR %d/%d", li.cb(), li.cb() + li.mb());
                    String text = (srcLines != null && li.line() >= 1 && li.line() <= srcLines.size())
                            ? srcLines.get(li.line() - 1) : "";
                    sb.append(String.format("    L%-5d [%s] %s%n", li.line(), tag, text));
                }
            }
            sb.append('\n');
        }
        Files.writeString(outPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void printInspect(String query, List<FileCoverage> classes, Path sourceRoot) throws IOException {
        System.out.println();
        System.out.println("=== INSPECT: " + query + " ================================================");
        List<FileCoverage> matches = classes.stream()
                .filter(fc -> fc.qualifiedClass.equals(query)
                        || fc.shortName().equals(query)
                        || fc.qualifiedClass.endsWith("." + query))
                .toList();
        if (matches.isEmpty()) {
            System.out.println("No tracked class matched '" + query + "'.");
            return;
        }
        for (FileCoverage fc : matches) {
            System.out.printf(Locale.ROOT, "%s (%s) : lines %.1f%% (%d/%d), branches %.1f%% (%d/%d)%n",
                    fc.qualifiedClass, fc.scope.name(),
                    fc.lines.pct(), fc.lines.covered(), fc.lines.total(),
                    fc.branches.pct(), fc.branches.covered(), fc.branches.total());
            List<String> srcLines = loadSourceLines(sourceRoot, fc);
            int printed = 0;
            for (LineInfo li : fc.lineMap.values()) {
                if (li.isMissedLine() || li.isPartialBranch()) {
                    String tag = li.isMissedLine() ? "MISS  " : String.format("BR %d/%d", li.cb(), li.cb() + li.mb());
                    String text = (srcLines != null && li.line() >= 1 && li.line() <= srcLines.size())
                            ? srcLines.get(li.line() - 1) : "";
                    System.out.printf("  L%-5d [%s] %s%n", li.line(), tag, text);
                    printed++;
                }
            }
            if (printed == 0) {
                System.out.println("  100% covered (no missed lines or branches).");
            }
        }
    }

    private static List<String> loadSourceLines(Path sourceRoot, FileCoverage fc) throws IOException {
        Path path = fc.packagePath.isEmpty()
                ? sourceRoot.resolve(fc.sourceFile)
                : sourceRoot.resolve(fc.packagePath).resolve(fc.sourceFile);
        return Files.isRegularFile(path) ? Files.readAllLines(path, StandardCharsets.UTF_8) : null;
    }

    private static void writeSummaryJson(
            Path outPath, String filter, int files, int cases,
            boolean hasCompileO0Outcomes, int interpPassed, int compilePassed, int compileO0Passed,
            int parityMatched, List<FailureEntry> failures,
            Counter interpL, Counter interpB,
            Counter compL, Counter compB,
            boolean hasCompileO0Cov, Counter compO0L, Counter compO0B,
            Counter frontL, Counter frontB,
            Counter shInterpL, Counter shInterpB,
            Counter shCompL, Counter shCompB,
            Counter shCompO0L, Counter shCompO0B,
            Counter shUnionL, Counter shUnionB,
            List<FileCoverage> canonicalClasses) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"filter\": ").append(jsonString(filter)).append(",\n");
        sb.append("  \"files\": ").append(files).append(",\n");
        sb.append("  \"cases\": ").append(cases).append(",\n");
        sb.append("  \"interpretPassed\": ").append(interpPassed).append(",\n");
        sb.append("  \"compilePassed\": ").append(compilePassed).append(",\n");
        if (hasCompileO0Outcomes) {
            sb.append("  \"compileO0Passed\": ").append(compileO0Passed).append(",\n");
        }
        sb.append("  \"parityMatched\": ").append(parityMatched).append(",\n");
        sb.append("  \"scopes\": {\n");
        sb.append("    \"interpreter\": ").append(scopeJson(interpL, interpB)).append(",\n");
        sb.append("    \"compiler\": ").append(scopeJson(compL, compB)).append(",\n");
        if (hasCompileO0Cov) {
            sb.append("    \"compilerO0\": ").append(scopeJson(compO0L, compO0B)).append(",\n");
        }
        sb.append("    \"frontEnd\": ").append(scopeJson(frontL, frontB)).append(",\n");
        sb.append("    \"sharedRuntimeInterpret\": ").append(scopeJson(shInterpL, shInterpB)).append(",\n");
        sb.append("    \"sharedRuntimeCompile\": ").append(scopeJson(shCompL, shCompB)).append(",\n");
        if (hasCompileO0Cov) {
            sb.append("    \"sharedRuntimeCompileO0\": ").append(scopeJson(shCompO0L, shCompO0B)).append(",\n");
        }
        sb.append("    \"sharedRuntimeCombined\": ").append(scopeJson(shUnionL, shUnionB)).append("\n");
        sb.append("  },\n");
        sb.append("  \"failures\": [");
        for (int i = 0; i < failures.size(); i++) {
            FailureEntry f = failures.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\n    {\"file\": ").append(jsonString(f.key().file()))
                    .append(", \"index\": ").append(f.key().index())
                    .append(", \"line\": ").append(f.key().line())
                    .append(", \"type\": ").append(jsonString(f.label()))
                    .append(", \"expected\": ").append(jsonString(f.expected()))
                    .append(", \"interpret\": ").append(f.interpret() == null ? "null" : jsonString(f.interpret().text()))
                    .append(", \"compile\": ").append(f.compile() == null ? "null" : jsonString(f.compile().text()));
            if (f.compileO0() != null || hasCompileO0Outcomes) {
                sb.append(", \"compileO0\": ").append(f.compileO0() == null ? "null" : jsonString(f.compileO0().text()));
            }
            sb.append('}');
        }
        if (!failures.isEmpty()) {
            sb.append("\n  ");
        }
        sb.append("],\n");
        sb.append("  \"classes\": [");
        for (int i = 0; i < canonicalClasses.size(); i++) {
            FileCoverage fc = canonicalClasses.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\n    {\"class\": ").append(jsonString(fc.qualifiedClass))
                    .append(", \"scope\": ").append(jsonString(fc.scope.name()))
                    .append(", \"linesCovered\": ").append(fc.lines.covered())
                    .append(", \"linesTotal\": ").append(fc.lines.total())
                    .append(", \"branchesCovered\": ").append(fc.branches.covered())
                    .append(", \"branchesTotal\": ").append(fc.branches.total())
                    .append('}');
        }
        if (!canonicalClasses.isEmpty()) {
            sb.append("\n  ");
        }
        sb.append("]\n}\n");
        Files.writeString(outPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String scopeJson(Counter lines, Counter branches) {
        return String.format(Locale.ROOT,
                "{\"linesCovered\": %d, \"linesTotal\": %d, \"linePct\": %.2f, \"branchesCovered\": %d, \"branchesTotal\": %d, \"branchPct\": %.2f}",
                lines.covered(), lines.total(), lines.pct(),
                branches.covered(), branches.total(), branches.pct());
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
            CaseKey key = new CaseKey(
                    fields.get("file"),
                    Integer.parseInt(fields.get("index")),
                    Integer.parseInt(fields.get("line")));
            map.put(key, new RecordedOutcome(
                    key,
                    fields.getOrDefault("expected", ""),
                    fields.getOrDefault("kind", ""),
                    fields.getOrDefault("text", ""),
                    Boolean.parseBoolean(fields.getOrDefault("passed", "false")),
                    fields.get("crash")));
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
                while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}' && !Character.isWhitespace(json.charAt(i))) {
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
