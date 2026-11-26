package org.babblelang.tests.batest;

import java.util.List;
import org.babblelang.engine.BabbleScriptEngine.Mode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BatestParser}, {@link BatestRunner}, {@link BatestFilter}, and {@code
 * BIP009-D5}/{@code BIP009-D7} normalization and Java exception unwrapping on the {@code
 * .batest.md} v2 format ({@code BIP029}).
 */
public class BatestHarnessTestCase {

    @Test
    public void testParserBasicAndWhitespace() {
        String content =
                "\r\n# Sample\r\n\r\n```babble\r\n;; babble preamble\r\n  def x = 1\r\n```\r\n\r\n## First\r\n\r\n```babble\r\n  print(x)\r\n```\r\n\r\n```stdout\r\n  1  \r\n```\r\n\r\n## Second\r\n\r\n````babble\r\nprintln(\"a\")\r\nprintln(\"b\")\r\n````\r\n\r\n```stdout\r\na\r\nb\r\n```\r\n";
        BatestFile file = BatestParser.parseString(content, "sample.batest.md");
        Assertions.assertEquals("def x = 1", file.preamble());
        Assertions.assertTrue(file.modules().isEmpty());
        Assertions.assertEquals(2, file.cases().size());
        Assertions.assertEquals(9, file.cases().get(0).line());
        Assertions.assertEquals("print(x)", file.cases().get(0).code());
        Assertions.assertEquals("1", file.cases().get(0).expectedStdout().text());
        Assertions.assertEquals("a\nb", file.cases().get(1).expectedStdout().text());
    }

    @Test
    public void testParserPreambleLessAndEmptyExpectation() {
        String content = "## Silent\n```babble\nassert(1 + 1 == 2)\n```\n";
        BatestFile file = BatestParser.parseString(content, "nopreamble.batest.md");
        Assertions.assertEquals("", file.preamble());
        Assertions.assertEquals(1, file.cases().size());
        Assertions.assertEquals(1, file.cases().get(0).line());
        Assertions.assertEquals("", file.cases().get(0).expectedStdout().text());
        Assertions.assertEquals("", file.cases().get(0).expectedStderr().text());
        Assertions.assertNull(file.cases().get(0).expectedError());
    }

    @Test
    public void testParserModulesAndSessionsAndGitConflictMarkersDoNotClash() {
        String content =
                """
                # Modules and sessions

                ```babble
                ;; babble module=lib/math.ba
                export def sq = fn (n : int) : int -> ( n * n )
                ```

                ```babble
                ;; babble preamble
                def math = import("lib/math.ba")
                ```

                ## Session with conflict markers

                ```babble
                ;; babble session
                ;; Strings containing git conflict markers work inside .batest.md code blocks
                def s = "<<<<<<< ======= >>>>>>>"
                println(math.sq(5))
                ```

                ```babble
                ;; babble session
                println(s)
                ```

                ```stdout
                25
                <<<<<<< ======= >>>>>>>
                ```
                """;
        BatestFile file = BatestParser.parseString(content, "mod_session.batest.md");
        Assertions.assertEquals(
                "export def sq = fn (n : int) : int -> ( n * n )",
                file.modules().get("lib/math.ba"));
        Assertions.assertEquals("def math = import(\"lib/math.ba\")", file.preamble());
        Assertions.assertEquals(1, file.cases().size());
        BatestFile.BatestCase c = file.cases().get(0);
        Assertions.assertTrue(c.session());
        Assertions.assertEquals(2, c.inputs().size());
        Assertions.assertEquals("25\n<<<<<<< ======= >>>>>>>", c.expectedStdout().text());
    }

    @Test
    public void testParserStructuralErrors() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        BatestParser.parseString(
                                "```babble\n;; babble preamble\ndef x = 1\n```\n",
                                "empty.batest.md"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        BatestParser.parseString(
                                "## A\n```babble\nprint(1)\n", "unclosed.batest.md"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BatestParser.parseString("```babble\n1\n```\n", "before_case.batest.md"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        BatestParser.parseString(
                                "## A\n```babble\n1\n```\n```babble\n2\n```\n",
                                "two_plain_babble.batest.md"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        BatestParser.parseString(
                                "```babble\n;; babble module=a.ba\nexport def x = 1\n```\n```babble\n;; babble module=a.ba\nexport def y = 2\n```\n## A\n```babble\n1\n```\n",
                                "dup_mod.batest.md"));
    }

    @Test
    public void testRegexExpectationBlocks() {
        String content =
                """
                # Regex expectations

                ## Multi-line stdout regex

                ```babble
                println("JDK 27")
                println("done")
                ```

                ```stdout
                # stdout regex
                JDK \\d+\\ndone
                ```

                ## JDK error message regex

                ```babble
                def a = (array of int)(1)
                a.get(5)
                ```

                ```error
                # error regex
                java\\.lang\\.ArrayIndexOutOfBoundsException: .*\\b5\\b.*
                ```
                """;
        BatestFile file = BatestParser.parseString(content, "regex.batest.md");
        BatestFile.BatestCase output = file.cases().get(0);
        Assertions.assertTrue(output.expectedStdout().matches("JDK 27\ndone"));
        Assertions.assertTrue(output.expectedStdout().matches("JDK 21\ndone"));
        Assertions.assertFalse(output.expectedStdout().matches("JDK x\ndone"));
        Assertions.assertFalse(output.expectedStdout().matches("JDK 27\ndone!"));
        for (BatestFile.BatestCase c : file.cases()) {
            for (Mode mode : Mode.values()) {
                BatestRunner.CaseOutcome outcome = BatestRunner.run(file, c, mode);
                Assertions.assertNull(
                        BatestRunner.evaluateBehavioral(c, outcome),
                        c.name() + " " + mode + " : " + outcome.text());
            }
        }
        IllegalArgumentException invalid =
                Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BatestParser.parseString(
                                        "## Bad\n```babble\nprint(1)\n```\n```stdout\n# stdout regex\n(1\n```\n",
                                        "bad_regex.batest.md"));
        Assertions.assertTrue(
                invalid.getMessage()
                        .startsWith("bad_regex.batest.md:5: invalid regular expression"),
                invalid.getMessage());
    }

    @Test
    public void testErrorMessageNormalizationRules() {
        Assertions.assertEquals(
                "Lexical error : token recognition error at: '#'",
                BatestRunner.normalizeMessage(
                        "org.babblelang.runtime.BabbleException: Lexical error at line 4:2 : token recognition error at: '#'"));
        Assertions.assertEquals(
                "Syntax error : unexpected '='",
                BatestRunner.normalizeMessage(
                        "org.babblelang.runtime.BabbleException: Syntax error : unexpected '=' in <input> at line number 5 at column number 6"));
        Assertions.assertEquals(
                "Assertion failed : 1 == 2",
                BatestRunner.normalizeMessage(
                        "org.babblelang.runtime.BabbleException: Assertion failed at line 5 : 1 == 2 in <input> at line number 5"));
        Assertions.assertEquals(
                "Type error : expected int, got double (x is double because of the assignment)",
                BatestRunner.normalizeMessage(
                        "javax.script.ScriptException: org.babblelang.runtime.BabbleException: Type error : expected int, got double (x is double because of the assignment at line 4) in <input> at line number 6, column 9"));
        Assertions.assertEquals(
                "No such export : cube in m.ba",
                BatestRunner.normalizeMessage(
                        "org.babblelang.runtime.BabbleException: No such export : cube in m.ba in m.ba at line number 2 in <input> at line number 6"));
        Assertions.assertEquals(
                "java.io.FileNotFoundException : missing.txt (<OS error>)",
                BatestRunner.normalizeMessage(
                        "org.babblelang.runtime.BabbleException: java.io.FileNotFoundException : missing.txt (Aucun fichier ou dossier de ce nom) in <input> at line number 2"));
        Assertions.assertEquals(
                "java.io.FileNotFoundException : missing.txt (<OS error>)",
                BatestRunner.normalizeMessage(
                        "java.io.FileNotFoundException : missing.txt (No such file or directory)"));
        Assertions.assertEquals(
                "java.lang.IllegalArgumentException : object is not an instance of declaring class",
                BatestRunner.normalizeMessage(
                        "java.lang.IllegalArgumentException : object of type org.babblelang.engine.impl.Function is not an instance of java.util.function.IntPredicate"));
        Assertions.assertEquals(
                "java.lang.IllegalArgumentException : object is not an instance of declaring class",
                BatestRunner.normalizeMessage(
                        "java.lang.IllegalArgumentException : object is not an instance of declaring class"));
    }

    @Test
    public void testRunnerParityAndJavaExceptionUnwrapping() {
        String batest =
                """
                # Java error unwrapping

                ## NumberFormatException

                ```babble
                print(java.lang.Integer.parseInt("nope"))
                ```

                ```error
                java.lang.NumberFormatException : For input string: "nope"
                ```

                ## ClassNotFoundException

                ```babble
                java.lang.Class.forName("no.such.Class")
                ```

                ```error
                java.lang.ClassNotFoundException : no.such.Class
                ```

                ## IllegalArgumentException

                ```babble
                def l : java.util.List of int = java.util.ArrayList(-1)
                ```

                ```error
                java.lang.IllegalArgumentException : Illegal Capacity: -1
                ```

                ## NullPointerException

                ```babble
                java.util.Objects.requireNonNull(null)
                ```

                ```error
                java.lang.NullPointerException
                ```

                ## UnsupportedOperationException

                ```babble
                def It = class () implements java.util.Iterator of int (
                    def hasNext = fn () : bool -> ( false )
                    def next = fn () : int -> ( 0 )
                )
                It().remove()
                ```

                ```error
                java.lang.UnsupportedOperationException : remove
                ```

                ## Callback BabbleException unwrapped

                ```babble
                def l : java.util.List of int = java.util.ArrayList()
                l.add(2)
                l.add(1)
                l.sort(fn (a : int, b : int) : int -> ( assert(false, "boom") ; 0 ))
                ```

                ```error
                boom
                ```

                ## StackOverflowError

                ```babble
                def loop = fn (n : int) : int -> ( 1 + recurse(n + 1) )
                loop(0)
                ```

                ```error
                Stack overflow
                ```
                """;
        BatestFile file = BatestParser.parseString(batest, "java_errors.batest.md");
        for (BatestFile.BatestCase c : file.cases()) {
            BatestRunner.CaseOutcome interp = BatestRunner.run(file, c, Mode.INTERPRET);
            BatestRunner.CaseOutcome comp = BatestRunner.run(file, c, Mode.COMPILE);
            Assertions.assertEquals(
                    BatestRunner.Kind.USER_ERROR, interp.kind(), c.name() + " interpret");
            Assertions.assertEquals(
                    BatestRunner.Kind.USER_ERROR, comp.kind(), c.name() + " compile");
            Assertions.assertNull(
                    BatestRunner.evaluateBehavioral(c, interp), c.name() + " interpret");
            Assertions.assertNull(BatestRunner.evaluateBehavioral(c, comp), c.name() + " compile");
        }
    }

    @Test
    public void testInternalCrashClassification() {
        BatestRunner.CaseOutcome npeCrash =
                BatestRunner.classifyFailure(
                        new javax.script.ScriptException(
                                "java.lang.NullPointerException: bug", "<input>", 1),
                        List.of(),
                        "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, npeCrash.kind());

        BatestRunner.CaseOutcome wrappedCrash =
                BatestRunner.classifyFailure(
                        new javax.script.ScriptException(new IllegalStateException("codegen bug")),
                        List.of(),
                        "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, wrappedCrash.kind());

        BatestRunner.CaseOutcome verifyCrash =
                BatestRunner.classifyFailure(new VerifyError("Bad stack map"), List.of(), "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, verifyCrash.kind());

        BatestRunner.CaseOutcome bootstrapCrash =
                BatestRunner.classifyFailure(
                        new BootstrapMethodError(new IllegalArgumentException("bad bsm")),
                        List.of(),
                        "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, bootstrapCrash.kind());

        BatestRunner.CaseOutcome bootstrapUserErr =
                BatestRunner.classifyFailure(
                        new BootstrapMethodError(
                                new org.babblelang.runtime.BabbleException("No such member : x")),
                        List.of(),
                        "");
        Assertions.assertEquals(BatestRunner.Kind.USER_ERROR, bootstrapUserErr.kind());
        Assertions.assertEquals("No such member : x", bootstrapUserErr.text());
    }

    @Test
    public void testWarningsPreErrorStdoutAndModulesAndSessions() {
        String batest =
                """
                # Warnings, pre-error stdout, modules, and sessions

                ```babble
                ;; babble module=helper.ba
                export def twice = fn (n : int) : int -> ( n * 2 )
                ```

                ```babble
                ;; babble preamble
                def h = import("helper.ba")
                ```

                ## Warning plus stdout

                ```babble
                def x = 1 as int
                print(h.twice(x))
                ```

                ```stdout
                2
                ```

                ```stderr
                Warning : Redundant cast : int
                ```

                ## Pre-error stdout plus runtime error

                ```babble
                println("before")
                def zero = 0
                print(int(1 / zero))
                ```

                ```stdout
                before
                ```

                ```error
                Out of the int range : 1/zero = Infinity
                ```

                ## Missing module import

                ```babble
                import("missing.ba")
                ```

                ```error
                Cannot import missing.ba : no such module
                ```

                ## Session shadowing and closure capture

                ```babble
                ;; babble session
                var x = 1
                def get = fn volatile () : int -> ( x )
                ```

                ```babble
                ;; babble session
                x = 5
                ```

                ```babble
                ;; babble session
                var x = 100
                println(get(), ":", x)
                ```

                ```babble
                ;; babble session
                x = x + 1
                println(get(), ":", x)
                ```

                ```stdout
                5:100
                5:101
                ```
                """;
        BatestFile file = BatestParser.parseString(batest, "features.batest.md");
        for (BatestFile.BatestCase c : file.cases()) {
            BatestRunner.CaseOutcome interp = BatestRunner.run(file, c, Mode.INTERPRET);
            BatestRunner.CaseOutcome comp = BatestRunner.run(file, c, Mode.COMPILE);
            Assertions.assertEquals(interp, comp, c.name());
            Assertions.assertNull(BatestRunner.evaluateBehavioral(c, interp), c.name());
        }
    }

    @Test
    public void testFilterMatching() {
        BatestFile f1 =
                new BatestFile(
                        "types/narrowing.batest.md",
                        "",
                        java.util.Map.of(),
                        List.of(
                                new BatestFile.BatestCase(1, 5, List.of("1"), false, ""),
                                new BatestFile.BatestCase(4, 42, List.of("2"), false, "")));
        BatestFile f2 =
                new BatestFile(
                        "objects/operators.batest.md",
                        "",
                        java.util.Map.of(),
                        List.of(new BatestFile.BatestCase(1, 10, List.of("3"), false, "")));

        Assertions.assertTrue(BatestFilter.parse("narrowing").matches(f1, f1.cases().get(0)));
        Assertions.assertFalse(BatestFilter.parse("narrowing").matches(f2, f2.cases().get(0)));
        Assertions.assertTrue(
                BatestFilter.parse("objects/*.batest.md").matches(f2, f2.cases().get(0)));
        Assertions.assertFalse(
                BatestFilter.parse("objects/*.batest.md").matches(f1, f1.cases().get(0)));
        Assertions.assertTrue(
                BatestFilter.parse("types/narrowing.batest.md#4").matches(f1, f1.cases().get(1)));
        Assertions.assertFalse(
                BatestFilter.parse("types/narrowing.batest.md#4").matches(f1, f1.cases().get(0)));
        Assertions.assertTrue(
                BatestFilter.parse("types/narrowing.batest.md:42").matches(f1, f1.cases().get(1)));
        Assertions.assertFalse(
                BatestFilter.parse("types/narrowing.batest.md:42").matches(f1, f1.cases().get(0)));
    }

    @Test
    public void testParserSizeBudgetsAndLargeOverride() {
        String code399 = "def x = \"" + "a".repeat(389) + "\"";
        Assertions.assertEquals(399, BatestParser.codeLength(code399));
        BatestParser.parseString("## Ok\n```babble\n" + code399 + "\n```\n", "ok399.batest.md");

        // Comments (line and block) do not count toward the 400-char case limit
        String withLongComments =
                ";; " + "c".repeat(600) + "\n;;(\n" + "d".repeat(600) + "\n;;)\n" + code399;
        Assertions.assertEquals(399, BatestParser.codeLength(withLongComments));
        BatestParser.parseString(
                "## Comments ok\n```babble\n" + withLongComments + "\n```\n",
                "comments_ok.batest.md");

        // String containing ;; and char literal '"' must not confuse comment stripping
        String quoted = "def q = '\"' ; def s = \";; not a comment\" ;; real comment";
        Assertions.assertEquals(
                "def q = '\"' ; def s = \";; not a comment\"", BatestParser.stripComments(quoted));

        // 400 code chars fails without large="<reason>"
        String code400 = "def x = \"" + "a".repeat(390) + "\"";
        Assertions.assertEquals(400, BatestParser.codeLength(code400));
        IllegalArgumentException caseErr =
                Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BatestParser.parseString(
                                        "## Big\n```babble\n" + code400 + "\n```\n",
                                        "big_case.batest.md"));
        Assertions.assertTrue(
                caseErr.getMessage().contains("exceeds 400 code characters"), caseErr.getMessage());

        // large="<reason>" overrides the case limit
        BatestParser.parseString(
                "## Big\n```babble\n;; babble large=\"intentional stress test\"\n"
                        + code400
                        + "\n```\n",
                "large_case.batest.md");

        // bare large or empty large="" fails immediately
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        BatestParser.parseString(
                                "## Big\n```babble\n;; babble large\n" + code400 + "\n```\n",
                                "no_reason1.batest.md"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        BatestParser.parseString(
                                "## Big\n```babble\n;; babble large=\"\"\n" + code400 + "\n```\n",
                                "no_reason2.batest.md"));

        // Preamble: 799 passes, 800 fails, large="<reason>" overrides
        String pre799 = "def p = \"" + "b".repeat(789) + "\"";
        String pre800 = "def p = \"" + "b".repeat(790) + "\"";
        Assertions.assertEquals(799, BatestParser.codeLength(pre799));
        Assertions.assertEquals(800, BatestParser.codeLength(pre800));
        BatestParser.parseString(
                "```babble\n;; babble preamble\n"
                        + pre799
                        + "\n```\n## Ok\n```babble\nassert(true)\n```\n",
                "pre799.batest.md");
        IllegalArgumentException preErr =
                Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BatestParser.parseString(
                                        "```babble\n;; babble preamble\n"
                                                + pre800
                                                + "\n```\n## Ok\n```babble\nassert(true)\n```\n",
                                        "pre800.batest.md"));
        Assertions.assertTrue(
                preErr.getMessage().contains("preamble exceeds 800 code characters"),
                preErr.getMessage());
        BatestParser.parseString(
                "```babble\n;; babble preamble large=\"shared fixture\"\n"
                        + pre800
                        + "\n```\n## Ok\n```babble\nassert(true)\n```\n",
                "pre_large.batest.md");

        // Virtual module: 800 fails, large="<reason>" overrides
        String mod800 =
                "```babble\n;; babble module=m.ba\n"
                        + pre800
                        + "\n```\n## Ok\n```babble\nassert(true)\n```\n";
        IllegalArgumentException modErr =
                Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> BatestParser.parseString(mod800, "mod800.batest.md"));
        Assertions.assertTrue(
                modErr.getMessage().contains("virtual module 'm.ba' exceeds 800 code characters"),
                modErr.getMessage());
        BatestParser.parseString(
                "```babble\n;; babble module=m.ba large=\"fixture module\"\n"
                        + pre800
                        + "\n```\n## Ok\n```babble\nassert(true)\n```\n",
                "mod_large.batest.md");

        // Output and IR blocks (stdout, stderr, error, hir, mir) do NOT count toward the 400-char
        // case input budget
        String bigOut = "x".repeat(600);
        String withLargeOutputs =
                """
                ## Large outputs ok
                ```babble
                println("ok")
                ```
                ```stdout
                %s
                ```
                ```stderr
                %s
                ```
                ```hir
                ;; hir -O1 fn=f
                %s
                ```
                ```mir
                ;; mir -O2 fn=f stage=before
                %s
                ```
                """
                        .formatted(bigOut, bigOut, bigOut, bigOut);
        BatestFile outOk = BatestParser.parseString(withLargeOutputs, "large_outputs.batest.md");
        Assertions.assertEquals(1, outOk.cases().size());
        Assertions.assertEquals(2, outOk.cases().get(0).irExpectations().size());
    }

    @Test
    public void testParserFileLineLimit() {
        String header = "# Limit check\n";
        String caseBlock = "## Case\n```babble\nassert(true)\n```\n";
        // 1 (header) + 4 (caseBlock) + 995 (blank/prose lines) = 1000 lines (passes with trailing
        // newline)
        String exact1000 = header + "\n".repeat(995) + caseBlock;
        BatestFile ok = BatestParser.parseString(exact1000, "ok_1000.batest.md");
        Assertions.assertEquals(1, ok.cases().size());
        // 1000 lines without trailing newline also passes
        BatestFile okNoTrail =
                BatestParser.parseString(
                        exact1000.substring(0, exact1000.length() - 1), "ok_1000_no_nl.batest.md");
        Assertions.assertEquals(1, okNoTrail.cases().size());

        // 1001 lines exceeds MAX_FILE_LINES and fails with clear splitting guidance
        String tooBig1001 = header + "\n".repeat(996) + caseBlock;
        IllegalArgumentException err =
                Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> BatestParser.parseString(tooBig1001, "oversized.batest.md"));
        Assertions.assertTrue(
                err.getMessage().contains("oversized.batest.md is too big with 1001 lines"),
                err.getMessage());
        Assertions.assertTrue(err.getMessage().contains("< 1000 lines"), err.getMessage());
        Assertions.assertTrue(err.getMessage().contains("foo_1.batest.md"), err.getMessage());

        // Lines inside output/IR blocks DO count toward the 1000-line whole-file limit
        String tooBigWithMir =
                header
                        + caseBlock
                        + "```mir\n;; mir -O2 fn=f\n"
                        + "v0 = const.int 1\n".repeat(994)
                        + "```\n";
        IllegalArgumentException mirErr =
                Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> BatestParser.parseString(tooBigWithMir, "oversized_mir.batest.md"));
        Assertions.assertTrue(
                mirErr.getMessage().contains("oversized_mir.batest.md is too big with 1002 lines"),
                mirErr.getMessage());
    }

    @Test
    public void testBatestShardPartitioningAndValidation() {
        BatestShard inactive = BatestShard.parse(null);
        Assertions.assertFalse(inactive.isActive());
        Assertions.assertTrue(inactive.matchesCase("types/arrays.batest.md", 1));
        Assertions.assertFalse(BatestShard.parse("   ").isActive());

        List<String> files =
                List.of(
                        "types/arrays.batest.md",
                        "control/loops.batest.md",
                        "functions/tail_calls.batest.md");
        int total = 6;
        for (String file : files) {
            java.util.Set<Integer> shardsSeen = new java.util.HashSet<>();
            for (int caseIdx = 1; caseIdx <= 24; caseIdx++) {
                int hits = 0;
                for (int i = 0; i < total; i++) {
                    BatestShard shard = BatestShard.parse(i + "/" + total);
                    Assertions.assertTrue(shard.isActive());
                    Assertions.assertEquals(i, shard.index());
                    Assertions.assertEquals(total, shard.total());
                    if (shard.matchesCase(file, caseIdx)) {
                        hits++;
                        shardsSeen.add(i);
                    }
                }
                Assertions.assertEquals(
                        1,
                        hits,
                        "Each case must belong to exactly one shard : " + file + "#" + caseIdx);
            }
            Assertions.assertTrue(
                    shardsSeen.size() >= 4,
                    "Cases within one file should spread across shards : " + file);
        }

        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestShard.parse("0"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestShard.parse("0/0"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestShard.parse("-1/6"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestShard.parse("6/6"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestShard.parse("a/6"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BatestShard.parse("0/1/2"));
    }
}
