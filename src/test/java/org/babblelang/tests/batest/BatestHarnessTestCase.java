package org.babblelang.tests.batest;

import org.babblelang.engine.BabbleScriptEngine.Mode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link BatestParser}, {@link BatestRunner}, {@link BatestFilter},
 * and {@code BIP009-D5}/{@code BIP009-D7} normalization and Java exception unwrapping.
 */
public class BatestHarnessTestCase {

    @Test
    public void testParserBasicAndWhitespace() {
        String content = "\r\n  def x = 1\r\n\r\n{{{{{\r\n  print(x)\r\n}}}}}\r\n  1  \r\n\r\n{{{{{{\r\nprintln(\"a\")\r\nprintln(\"b\")\r\n}}}}}}\r\na\nb\n";
        BatestFile file = BatestParser.parseString(content, "sample.batest");
        Assertions.assertEquals("def x = 1", file.preamble());
        Assertions.assertTrue(file.modules().isEmpty());
        Assertions.assertEquals(2, file.cases().size());
        Assertions.assertEquals(4, file.cases().get(0).line());
        Assertions.assertEquals("print(x)", file.cases().get(0).code());
        Assertions.assertEquals("1", file.cases().get(0).expected());
        Assertions.assertEquals("a\nb", file.cases().get(1).expected());
    }

    @Test
    public void testParserPreambleLessAndEmptyExpectation() {
        String content = "{{{{{\nassert(1 + 1 == 2)\n}}}}}\n";
        BatestFile file = BatestParser.parseString(content, "nopreamble.batest");
        Assertions.assertEquals("", file.preamble());
        Assertions.assertEquals(1, file.cases().size());
        Assertions.assertEquals(1, file.cases().get(0).line());
        Assertions.assertEquals("", file.cases().get(0).expected());
    }

    @Test
    public void testParserModulesAndSessionsAndGitConflictMarkersDoNotClash() {
        String content = """
                ========== lib/math.ba ==========
                export def sq = fn (n : int) : int -> ( n * n )

                ==========
                def math = import("lib/math.ba")

                {{{{{
                ;; Strings containing git conflict markers work inside .batest code blocks
                def s = "<<<<<<< ======= >>>>>>>"
                println(math.sq(5))
                ----------
                println(s)
                }}}}}
                25
                <<<<<<< ======= >>>>>>>
                """;
        BatestFile file = BatestParser.parseString(content, "mod_session.batest");
        Assertions.assertEquals("export def sq = fn (n : int) : int -> ( n * n )", file.modules().get("lib/math.ba"));
        Assertions.assertEquals("def math = import(\"lib/math.ba\")", file.preamble());
        Assertions.assertEquals(1, file.cases().size());
        BatestFile.BatestCase c = file.cases().get(0);
        Assertions.assertTrue(c.session());
        Assertions.assertEquals(2, c.inputs().size());
        Assertions.assertEquals("25\n<<<<<<< ======= >>>>>>>", c.expected());
    }

    @Test
    public void testParserStructuralErrors() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("def x = 1", "empty.batest"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("{{{{{\nprint(1)\n{{{{{\n", "nested.batest"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("{{{{{\nprint(1)\n", "unclosed.batest"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("}}}}}\n", "extra_close.batest"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("{{{{{\n1\n}}}}}\nok\n}}}}}\n", "double_close.batest"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> BatestParser.parseString("========== a.ba ==========\nexport def x = 1\n========== a.ba ==========\nexport def y = 2\n{{{{{\n1\n}}}}}\n", "dup_mod.batest"));
    }

    @Test
    public void testErrorMessageNormalizationRules() {
        Assertions.assertEquals(
                "Lexical error : token recognition error at: '#'",
                BatestRunner.normalizeMessage("org.babblelang.runtime.BabbleException: Lexical error at line 4:2 : token recognition error at: '#'"));
        Assertions.assertEquals(
                "Syntax error : unexpected '='",
                BatestRunner.normalizeMessage("org.babblelang.runtime.BabbleException: Syntax error : unexpected '=' in <input> at line number 5 at column number 6"));
        Assertions.assertEquals(
                "Assertion failed : 1 == 2",
                BatestRunner.normalizeMessage("org.babblelang.runtime.BabbleException: Assertion failed at line 5 : 1 == 2 in <input> at line number 5"));
        Assertions.assertEquals(
                "Type error : expected int, got double (x is double because of the assignment)",
                BatestRunner.normalizeMessage("javax.script.ScriptException: org.babblelang.runtime.BabbleException: Type error : expected int, got double (x is double because of the assignment at line 4) in <input> at line number 6, column 9"));
        Assertions.assertEquals(
                "No such export : cube in m.ba",
                BatestRunner.normalizeMessage("org.babblelang.runtime.BabbleException: No such export : cube in m.ba in m.ba at line number 2 in <input> at line number 6"));
        Assertions.assertEquals(
                "java.io.FileNotFoundException : missing.txt (<OS error>)",
                BatestRunner.normalizeMessage("org.babblelang.runtime.BabbleException: java.io.FileNotFoundException : missing.txt (Aucun fichier ou dossier de ce nom) in <input> at line number 2"));
        Assertions.assertEquals(
                "java.io.FileNotFoundException : missing.txt (<OS error>)",
                BatestRunner.normalizeMessage("java.io.FileNotFoundException : missing.txt (No such file or directory)"));
        Assertions.assertEquals(
                "java.lang.IllegalArgumentException : object is not an instance of declaring class",
                BatestRunner.normalizeMessage("java.lang.IllegalArgumentException : object of type org.babblelang.engine.impl.Function is not an instance of java.util.function.IntPredicate"));
        Assertions.assertEquals(
                "java.lang.IllegalArgumentException : object is not an instance of declaring class",
                BatestRunner.normalizeMessage("java.lang.IllegalArgumentException : object is not an instance of declaring class"));
    }

    @Test
    public void testRunnerParityAndJavaExceptionUnwrapping() {
        String batest = """
                {{{{{
                print(java.lang.Integer.parseInt("nope"))
                }}}}}
                java.lang.NumberFormatException : For input string: "nope"

                {{{{{
                java.lang.Class.forName("no.such.Class")
                }}}}}
                java.lang.ClassNotFoundException : no.such.Class

                {{{{{
                def l : java.util.List of int = java.util.ArrayList(-1)
                }}}}}
                java.lang.IllegalArgumentException : Illegal Capacity: -1

                {{{{{
                java.util.Objects.requireNonNull(null)
                }}}}}
                java.lang.NullPointerException

                {{{{{
                def It = class () implements java.util.Iterator of int (
                    def hasNext = fn () : bool -> ( false )
                    def next = fn () : int -> ( 0 )
                )
                It().remove()
                }}}}}
                java.lang.UnsupportedOperationException : remove

                {{{{{
                def l : java.util.List of int = java.util.ArrayList()
                l.add(2)
                l.add(1)
                l.sort(fn (a : int, b : int) : int -> ( assert(false, "boom") ; 0 ))
                }}}}}
                boom

                {{{{{
                def loop = fn (n : int) : int -> ( recurse(n + 1) )
                loop(0)
                }}}}}
                Stack overflow
                """;
        BatestFile file = BatestParser.parseString(batest, "java_errors.batest");
        for (BatestFile.BatestCase c : file.cases()) {
            BatestRunner.CaseOutcome interp = BatestRunner.run(file, c, Mode.INTERPRET);
            BatestRunner.CaseOutcome comp = BatestRunner.run(file, c, Mode.COMPILE);
            Assertions.assertEquals(BatestRunner.Kind.USER_ERROR, interp.kind(), "case #" + c.index() + " interpret");
            Assertions.assertEquals(BatestRunner.Kind.USER_ERROR, comp.kind(), "case #" + c.index() + " compile");
            Assertions.assertEquals(c.expected(), interp.text(), "case #" + c.index() + " interpret");
            Assertions.assertEquals(c.expected(), comp.text(), "case #" + c.index() + " compile");
        }
    }

    @Test
    public void testInternalCrashClassification() {
        BatestRunner.CaseOutcome npeCrash = BatestRunner.classifyFailure(
                new javax.script.ScriptException("java.lang.NullPointerException: bug", "<input>", 1),
                List.of(), "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, npeCrash.kind());

        BatestRunner.CaseOutcome wrappedCrash = BatestRunner.classifyFailure(
                new javax.script.ScriptException(new IllegalStateException("codegen bug")),
                List.of(), "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, wrappedCrash.kind());

        BatestRunner.CaseOutcome verifyCrash = BatestRunner.classifyFailure(
                new VerifyError("Bad stack map"), List.of(), "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, verifyCrash.kind());

        BatestRunner.CaseOutcome bootstrapCrash = BatestRunner.classifyFailure(
                new BootstrapMethodError(new IllegalArgumentException("bad bsm")), List.of(), "");
        Assertions.assertEquals(BatestRunner.Kind.INTERNAL_CRASH, bootstrapCrash.kind());

        BatestRunner.CaseOutcome bootstrapUserErr = BatestRunner.classifyFailure(
                new BootstrapMethodError(new org.babblelang.runtime.BabbleException("No such member : x")),
                List.of(), "");
        Assertions.assertEquals(BatestRunner.Kind.USER_ERROR, bootstrapUserErr.kind());
        Assertions.assertEquals("No such member : x", bootstrapUserErr.text());
    }

    @Test
    public void testWarningsPreErrorStdoutAndModulesAndSessions() {
        String batest = """
                ========== helper.ba ==========
                export def twice = fn (n : int) : int -> ( n * 2 )

                ==========
                def h = import("helper.ba")

                {{{{{
                def x = 1 as int
                print(h.twice(x))
                }}}}}
                Warning : Redundant cast : int
                2

                {{{{{
                println("before")
                def zero = 0
                print(int(1 / zero))
                }}}}}
                before
                Out of the int range : 1/zero = Infinity

                {{{{{
                import("missing.ba")
                }}}}}
                Cannot import missing.ba : no such module

                {{{{{
                def x = 1
                def get = fn () : int -> ( x )
                ----------
                x = 5
                ----------
                def x = 100
                println(get(), ":", x)
                ----------
                x = x + 1
                println(get(), ":", x)
                }}}}}
                5:100
                5:101
                """;
        BatestFile file = BatestParser.parseString(batest, "features.batest");
        for (BatestFile.BatestCase c : file.cases()) {
            BatestRunner.CaseOutcome interp = BatestRunner.run(file, c, Mode.INTERPRET);
            BatestRunner.CaseOutcome comp = BatestRunner.run(file, c, Mode.COMPILE);
            Assertions.assertEquals(interp, comp, "case #" + c.index());
            Assertions.assertEquals(c.expected(), interp.text(), "case #" + c.index());
        }
    }

    @Test
    public void testFilterMatching() {
        BatestFile f1 = new BatestFile("types/narrowing.batest", "", java.util.Map.of(),
                List.of(
                        new BatestFile.BatestCase(1, 5, List.of("1"), false, ""),
                        new BatestFile.BatestCase(4, 42, List.of("2"), false, "")
                ));
        BatestFile f2 = new BatestFile("objects/operators.batest", "", java.util.Map.of(),
                List.of(new BatestFile.BatestCase(1, 10, List.of("3"), false, "")));

        Assertions.assertTrue(BatestFilter.parse("narrowing").matches(f1, f1.cases().get(0)));
        Assertions.assertFalse(BatestFilter.parse("narrowing").matches(f2, f2.cases().get(0)));
        Assertions.assertTrue(BatestFilter.parse("objects/*.batest").matches(f2, f2.cases().get(0)));
        Assertions.assertFalse(BatestFilter.parse("objects/*.batest").matches(f1, f1.cases().get(0)));
        Assertions.assertTrue(BatestFilter.parse("types/narrowing.batest#4").matches(f1, f1.cases().get(1)));
        Assertions.assertFalse(BatestFilter.parse("types/narrowing.batest#4").matches(f1, f1.cases().get(0)));
        Assertions.assertTrue(BatestFilter.parse("types/narrowing.batest:42").matches(f1, f1.cases().get(1)));
        Assertions.assertFalse(BatestFilter.parse("types/narrowing.batest:42").matches(f1, f1.cases().get(0)));
    }
}
