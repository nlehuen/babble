package org.babblelang.tests;

import org.babblelang.format.Formatter;
import org.babblelang.tests.batest.BatestFile;
import org.babblelang.tests.batest.BatestParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The formatter : what it settles, what it keeps, and that it changes no program's meaning.
 */
public class FormatterTestCase {
    private static void assertFormats(String expected, String source) throws ScriptException {
        Assertions.assertEquals(expected, Formatter.format(source));
        // What is formatted stays as it is.
        Assertions.assertEquals(expected, Formatter.format(expected));
    }

    @Test
    public void testSpaces() throws Exception {
        assertFormats("def x = 1 + 2 * (3 - 4) / 5\n", "def  x=1+2*(3-4)/5");
        assertFormats("f(a, b: 2)(c).d.e = not (g() == h)\n", "f ( a ,b:2 ) (c) . d . e=not(g()==h)");
        assertFormats("def add: fn (a: int, b: int): int = fn (a: int, b: int = 1): int -> (a + b)\n",
                "def add:fn (a:int,b:int):int=fn (a:int,b:int=1):int->( a+b )");
        assertFormats("def f = fn () -> ()\n", "def f=fn ()->( )");
        assertFormats("x += 1\no.n -= int(2.5)\ny *= 2; z /= 3\n", "x+=1\no . n-=int( 2.5 )\ny*=2;z/=3");
        assertFormats("x is java.util.List and x <> null or y != 1.5\n", "x  is java . util . List and x<>null or y!=1.5");
        assertFormats("def x = a + b as int == 1\n", "def x=a+b   as  int==1");
        assertFormats("def f = x as fn (a: int, b: string): bool\n", "def f = x as fn (a:int,b:string):bool");
        assertFormats("x is int and y is string\n", "x  is  int  and  y  is  string");
        assertFormats("def x: int | string?\ndef y: (int | string)? = null\ndef z: (fn (n: int): int)? = fn (n: int): int -> (n)\n",
                "def x:int|string ?\ndef y:(int|string)?=null\ndef z:(fn (n:int):int)?=fn (n:int):int->(n)");
        assertFormats("def n: null = null\n", "def  n : null = null");
        assertFormats("def x: A & B | C\ndef y: (A & B)? = null\n", "def x:A&B|C\ndef y:(A  &  B)?=null");
        assertFormats("def r = f as (java.lang.Runnable & java.io.Serializable)\n", "def r=f as ( java.lang.Runnable&java.io.Serializable )");
        assertFormats("def T = type A & (B | C)\ndef P of (K, V) = type entry of (K, V)\n", "def T=type  A&(B|C)\ndef P  of (K,V)=type entry of(K,V)");
        assertFormats("return (a)\n", "return(a)");
    }

    @Test
    public void testGenerics() throws Exception {
        assertFormats("""
                def id of T: fn (x: T): T = fn (x: T): T -> (x)
                def Stack of T = class (def value: T, def next: (Stack of T)?) ()
                def l: java.util.List of string = java.util.ArrayList()
                def m: java.util.Map of (string, (Stack of int)?) = java.util.HashMap()
                def f: (fn (x: int): Box of (int | string))? = null
                def a: array of int = (array of int)(10)
                def g: array of (array of double) = (array of (array of double))(2)
                def b: (array of int)? = null
                def p = (array of int).of(1, 2, 3)
                """, """
                def id  of  T : fn ( x : T ) : T = fn ( x : T ) : T -> ( x )
                def Stack  of  T = class ( def value : T , def next : ( Stack  of  T ) ? ) ( )
                def l : java.util.List  of  string = java.util.ArrayList ( )
                def m : java.util.Map  of  ( string , ( Stack  of  int ) ? ) = java.util.HashMap ( )
                def f : ( fn ( x : int ) : Box  of  ( int | string ) ) ? = null
                def a : array  of  int = ( array  of  int ) ( 10 )
                def g : array  of  ( array  of  double ) = ( array  of  ( array  of  double ) ) ( 2 )
                def b : ( array  of  int ) ? = null
                def p = ( array  of  int ) . of ( 1 , 2 , 3 )
                """);
    }

    @Test
    public void testUnaryMinusElseIfAndLeadingDots() throws Exception {
        // A unary minus sticks to its operand ; a binary one is spaced.
        assertFormats("def x = -3 - -y * f(-1, - -z)\n", "def x = - 3--y*f(- 1,- - z)");
        // An "else if" chain is brought back on the lines of its parentheses, as "else" is.
        assertFormats("""
                def sign = fn (n) -> (
                    if n < 0 then (
                        "negative"
                    ) else if n == 0 then ("zero") else if n < 10 then (
                        "small"
                    ) else ("large")
                )
                """, """
                def sign = fn (n) -> (
                if n < 0 then (
                "negative"
                ) else
                if n == 0 then ( "zero" )
                else if n < 10 then (
                "small"
                ) else ( "large" )
                )
                """);
        // A line starting with a dot continues the expression above it.
        assertFormats("""
                def s = java.lang.StringBuilder()
                    .append("a")
                    .append(-1)
                    .toString()
                """, """
                def s = java.lang.StringBuilder()
                .append("a")
                        .append(-1)
                  .toString()
                """);
    }

    @Test
    public void testClassesAndInterfaces() throws Exception {
        assertFormats("""
                def Point = record (def x: double, private def y = 0.0) implements Shape, java.lang.Runnable (
                    def norm = fn () -> (super(Shape).norm() + super.size())
                ) static (
                    def origin = this(0.0)
                )
                def Shape = interface extends A, B (
                    def area
                )
                def one = object implements Shape (
                    def area = 1
                )
                """, """
                def Point = record (def x:double,private def y=0.0)implements Shape,java.lang.Runnable(
                def norm = fn ()->(super (Shape).norm()+super.size())
                )
                static
                (
                def origin = this(0.0)
                )
                def Shape = interface extends A,B
                (
                def area
                )
                def one = object implements Shape(
                def area = 1
                )
                """);
    }

    @Test
    public void testIndentationFollowsParentheses() throws Exception {
        assertFormats("""
                object (
                    def f = fn (n) -> (
                        if n < 2 then (
                            1
                        ) else (
                            while false then (
                                g(
                                    n,
                                    (
                                        n
                                    )
                                )
                            )
                        )
                    )
                )
                """, """
                object (
                def f = fn (n) -> (
                      if n < 2 then (
                  1
                         ) else (
                while false then (
                g(
                n,
                (
                n
                )
                )
                )
                )
                  )
                    )
                """);
    }

    @Test
    public void testBlockParenthesesJoinTheirLine() throws Exception {
        assertFormats("""
                object (
                    if a then (
                        1
                    ) else (
                        2
                    )
                    def f = fn () -> (
                        3
                    )
                )
                """, """
                object
                (
                    if a then
                    (
                        1
                    )
                    else
                    (
                        2
                    )
                    def f = fn () ->

                    (
                        3
                    )
                )
                """);
        // Not through a comment, which would end up elsewhere, or swallow the rest of the line.
        assertFormats("""
                if a then  ;; why
                (
                    1
                )
                ;; otherwise
                else (
                    2
                )
                """, """
                if a then ;; why
                (
                1
                )
                ;; otherwise
                else (
                2
                )
                """);
    }

    @Test
    public void testContinuationLines() throws Exception {
        assertFormats("""
                def f = fn () -> (
                    def total = first +
                        second +
                        third(
                            1
                        )
                    def g =
                        fn (a) -> (
                            a
                        )
                    total =
                        g(total)
                    if total > 0 and
                        total < 10 then (
                        total
                    )
                )
                def C = class () implements A,
                    B ()
                """, """
                def f = fn () -> (
                def total = first +
                second +
                third(
                1
                )
                def g =
                fn (a) -> (
                a
                )
                total =
                g(total)
                if total > 0 and
                total < 10 then (
                total
                )
                )
                def C = class () implements A,
                B ()
                """);
    }

    @Test
    public void testParameterLists() throws Exception {
        assertFormats("""
                def f = fn (
                    a,
                    b = 1
                ) -> (
                    a
                )
                f(
                    1,
                    b: 2
                )
                f(1,
                    2)
                """, """
                def f = fn (
                a,
                b=1
                ) -> (
                a
                )
                f(
                1,
                b:2
                )
                f(1,
                2)
                """);
    }

    @Test
    public void testSemicolons() throws Exception {
        assertFormats("a; b; c\nd\n(e; f)\n(g)\n", ";a;b ; c;\n;d;\n(e ;f;)\n( ; g ; )\n");
    }

    @Test
    public void testBlankLines() throws Exception {
        assertFormats("""
                a

                def f = fn () -> (
                    b

                    c
                )

                d
                """, """


                a



                def f = fn () -> (

                    b
                \t
                    c

                )

                d


                """);
        assertFormats("a\n", "a");
        assertFormats("a\nb\n", "a   \r\nb\t\r\n");
    }

    @Test
    public void testComments() throws Exception {
        assertFormats("""
                ;; first

                ;;(
                    several
                      lines

                ;;)
                def f = fn () -> (  ;; a function
                    ;; alone
                    a      ;; aligned
                    b + c  ;; with the line above
                    d ;;( inside
                    ;;) + e

                    ;; last
                )
                def longer = 1  ;; not aligned with those above
                ;; end
                """, """
                ;; first  \s

                ;;(
                    several
                      lines
                  \s
                ;;)
                def f = fn () -> (;; a function
                ;; alone
                a ;; aligned
                b+c ;; with the line above
                d   ;;( inside
                    ;;)   + e

                ;; last
                )
                def longer = 1 ;; not aligned with those above
                ;; end""");
        // A comment of several lines moves as a whole : its lines stay where they are, relative to its start.
        assertFormats("""
                (
                    ;;( one
                        two
                  three
                    ;;)
                    a
                )
                """, """
                (
                  ;;( one
                      two
                three
                  ;;)
                a
                )
                """);
    }

    @Test
    public void testOnlyComments() throws Exception {
        assertFormats(";; nothing\n\n;;( yet ;;)\n", "\n  ;; nothing\n\n\n;;( yet ;;)\n;\n\n");
        assertFormats("", "");
        assertFormats("", " \n\n");
    }

    @Test
    public void testStringsAreKept() throws Exception {
        assertFormats("def s = \"a  ;; b ( \\\" \" + \"\\n  c\"\n", "def s=\"a  ;; b ( \\\" \"+\"\\n  c\"");
        assertFormats("def p = r\"C:\\dir\\\"\n", "def p=r\"C:\\dir\\\"");
    }

    @Test
    public void testSafeNavigationFormatting() throws Exception {
        assertFormats("""
                def x = a?.f(1)?.g
                def y = a
                    ?.f(1)
                    ?.g
                """, """
                def x = a ?. f ( 1 ) ?. g
                def y = a
                ?. f(1)
                ?. g
                """);
    }

    @Test
    public void testSyntaxErrors() {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> Formatter.format("def f = (\n  1 +\n)", "f.ba"));
        Assertions.assertTrue(e.getMessage().contains("Syntax error : unexpected ')'"), e.getMessage());
        Assertions.assertEquals(3, e.getLineNumber());
        Assertions.assertEquals("f.ba", e.getFileName());
        Assertions.assertThrows(ScriptException.class, () -> Formatter.format(";; a comment\n\"open"));
    }

    /**
     * Every script of the repository formats, to a text formatting leaves alone. That it
     * parses to the same tree is the formatter's own check.
     */
    @Test
    public void testEveryScript() throws Exception {
        List<Path> scripts;
        try (Stream<Path> examples = Files.walk(Path.of("examples"));
             Stream<Path> axioms = Files.walk(Path.of("src/main/java/org/babblelang/axioms"))) {
            scripts = Stream.concat(examples, axioms)
                    .filter(path -> path.toString().endsWith(".ba")).sorted().toList();
        }
        Assertions.assertFalse(scripts.isEmpty());
        for (Path script : scripts) {
            String original = Files.readString(script);
            String formatted = Formatter.format(original, script.toString());
            Assertions.assertEquals(formatted, Formatter.format(formatted, script.toString()), script.toString());
            Assertions.assertTrue(formatted.lines().noneMatch(line -> line.endsWith(" ") || line.contains("\t")), script.toString());
            if (script.startsWith(Path.of("src/main/java/org/babblelang/axioms"))) {
                Assertions.assertEquals(original, formatted, "Axiom file is not formatter-clean : " + script);
            }
        }
    }

    @Test
    public void testAllBatestSnippetsFormatIdempotently() throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        Path batestRoot = Path.of("src/test/batest");
        try (Stream<Path> stream = Files.walk(batestRoot)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".batest.md")).sorted().toList()) {
                String relative = batestRoot.relativize(file).toString();
                BatestFile parsed = BatestParser.parse(file, relative);
                if (parsed.preamble() != null && !parsed.preamble().isBlank()) {
                    checked++;
                    checkFormatsIdempotently(parsed.preamble(), relative + " preamble", failures);
                }
                for (var mod : parsed.modules().entrySet()) {
                    checked++;
                    checkFormatsIdempotently(mod.getValue(), relative + " module " + mod.getKey(), failures);
                }
                for (BatestFile.BatestCase testCase : parsed.cases()) {
                    if (testCase.expectedError() != null
                            && (testCase.expectedError().text().startsWith("Syntax error")
                            || testCase.expectedError().text().startsWith("Lexical error"))) {
                        continue;
                    }
                    for (var mod : testCase.caseModules().entrySet()) {
                        checked++;
                        checkFormatsIdempotently(mod.getValue(),
                                relative + ":" + testCase.line() + " module " + mod.getKey(), failures);
                    }
                    for (int i = 0; i < testCase.inputs().size(); i++) {
                        checked++;
                        checkFormatsIdempotently(testCase.inputs().get(i),
                                relative + ":" + testCase.line() + " input[" + i + "]", failures);
                    }
                }
            }
        }

        Assertions.assertTrue(checked > 400, "Expected to check >400 .batest.md snippets, checked " + checked);
        Assertions.assertEquals(List.of(), failures, "Formatter failures:\n" + String.join("\n", failures));
    }

    private static void checkFormatsIdempotently(String source, String label, List<String> failures) {
        try {
            String formatted = Formatter.format(source, label);
            String second = Formatter.format(formatted, label);
            if (!formatted.equals(second)) {
                failures.add(label + " did not reach a fixed point on second format");
            }
            if (formatted.lines().anyMatch(line -> line.endsWith(" "))) {
                failures.add(label + " formatted output has trailing spaces");
            }
        } catch (Exception e) {
            failures.add(label + " failed to format: " + e.getMessage());
        }
    }

    @Test
    public void testRm002EffectsAndAxioms() throws Exception {
        assertFormats("""
                axiom java.lang.Math.abs: fn pure nothrow
                axiom java.util.List.add: fn mut
                axiom java.awt.Point: class () static (def new: fn pure)
                axiom java.nio.file.Files.readString: fn volatile
                export axiom java.time.Instant.now: fn volatile
                axiom java.lang.System.nanoTime: fn io
                def add: fn nothrow (a: int, b: int): int = fn nothrow (a: int, b: int): int -> (
                    a + b
                )
                def id of T = fn (shared x: T): T -> (x)
                def step = fn (mut p: Point, q: Point): int -> (
                    p.x = q.x
                    p.x
                )
                def unnamed: fn (int, int): int = fn (a: int, b: int): int -> (a + b)
                unnamed(1, 2)
                """, """
                axiom   java . lang . Math . abs :  fn   pure   nothrow
                axiom java.util.List.add : fn mut
                axiom java.awt.Point : class ( ) static ( def new : fn pure )
                axiom   java.nio.file.Files.readString  :  fn  volatile
                export   axiom  java.time.Instant.now : fn  volatile
                axiom   java.lang.System.nanoTime : fn   io
                def add : fn  nothrow ( a : int , b : int ) : int = fn  nothrow ( a : int , b : int ) : int -> (
                a+b
                )
                def id  of  T = fn ( shared  x : T ) : T -> ( x )
                def step = fn ( mut  p : Point , q : Point ) : int -> (
                p.x = q.x
                p.x
                )
                def unnamed : fn ( int , int ) : int = fn ( a : int , b : int ) : int -> ( a + b )
                unnamed ( 1 , 2 )
                """);
    }

    @Test
    public void testBip011CoreTypesSyntax() throws Exception {
        assertFormats("""
                def s: string? = "hi"
                def u = s!.toUpperCase()
                def a = 42L
                def b = 1.5f
                def c = 'a'
                def xs = list(int)(1, 2)
                def ys = xs.set(0, 3)
                """, """
                def s : string? = "hi"
                def u = s ! . toUpperCase ( )
                def a = 42L
                def b = 1.5f
                def c = 'a'
                def xs = list ( int ) ( 1 , 2 )
                def ys = xs . set ( 0 , 3 )
                """);
    }

    @Test
    public void testBip007OwnershipSyntax() throws Exception {
        assertFormats("""
                axiom java.util.ArrayList: class () static (def new: fn pure unique)
                def f = fn (unique b: Box, shared c: Box): unique Box -> (
                    def x: frozen list of int = freeze(list(1, 2))
                    discard(x)
                    move(b)
                )
                """, """
                axiom java . util . ArrayList : class ( ) static ( def new : fn pure unique )
                def f = fn ( unique b : Box , shared c : Box ) : unique Box -> (
                  def x : frozen list of int = freeze ( list ( 1 , 2 ) )
                  discard ( x )
                  move ( b )
                )
                """);
    }

    @Test
    public void testBip020ArraySyntax() throws Exception {
        assertFormats("""
                def a: array of int = (array of int)(100)
                def b: (array of (Shape & Named))? = null
                def c = (array of int).of(1, 2, 3)
                def d = array.of(1, 2)
                def e = (array of double)(from: d)
                """, """
                def a : array  of  int = ( array  of  int ) ( 100 )
                def b : ( array  of ( Shape & Named ) ) ? = null
                def c = ( array  of  int ) . of ( 1 , 2 , 3 )
                def d = array . of ( 1 , 2 )
                def e = ( array  of  double ) ( from : d )
                """);
    }

    @Test
    public void testBip021OptionalAndKeywordOnlySyntax() throws Exception {
        assertFormats("""
                def f: fn (a: int, b: int = ..., ..., c: string = ...): int = fn (a: int, b: int = 1, ..., c: string = ""): int -> (
                    a + b
                )
                def C = class (def x: int = 0, ..., def y: int) ()
                """, """
                def f : fn ( a : int , b : int = ... , ... , c : string = ... ) : int = fn ( a : int , b : int = 1 , ... , c : string = "" ) : int -> (
                    a + b
                )
                def C = class ( def x : int = 0 , ... , def y : int ) ( )
                """);
    }

    @Test
    public void testBip021P3GenericLiteralsAndBounds() throws Exception {
        assertFormats("""
                def id = fn of T (t: T): T -> (
                    t
                )
                def biggest of (T: java.lang.Comparable of T) = fn (a: T, b: T): T -> (
                    if a.compareTo(b) > 0 then (a) else (b)
                )
                def f: fn of (T: Shape & Named) (x: T): string = fn of (T: Shape & Named) (x: T): string -> (
                    x.name()
                )
                """, """
                def id = fn  of  T ( t : T ) : T -> (
                    t
                )
                def biggest  of ( T : java . lang . Comparable  of  T ) = fn ( a : T , b : T ) : T -> (
                    if a . compareTo ( b ) > 0 then ( a ) else ( b )
                )
                def f : fn  of ( T : Shape & Named ) ( x : T ) : string = fn  of ( T : Shape & Named ) ( x : T ) : string -> (
                    x . name ( )
                )
                """);
    }

    @Test
    public void testBip021P4VariadicAndSpreadSyntax() throws Exception {
        assertFormats("""
                def join: fn (parts: string..., sep: string = ...): string = fn (parts: string..., sep: string = ", "): string -> (
                    sep
                )
                def countNulls = fn (items: string?...): int -> (
                    0
                )
                def xs = list("a", "b")
                join(...xs, sep: "-")
                join(parts: ...xs)
                """, """
                def join : fn ( parts : string ... , sep : string = ... ) : string = fn ( parts : string ... , sep : string = ", " ) : string -> (
                    sep
                )
                def countNulls = fn ( items : string ?... ) : int -> (
                    0
                )
                def xs = list ( "a" , "b" )
                join ( ... xs , sep : "-" )
                join ( parts : ... xs )
                """);
    }

    @Test
    public void testBip021P5OverloadSyntax() throws Exception {
        assertFormats("""
                def f: overload (fn (a: int): int, fn (s: string): int) = overload (
                    fn (a: int): int -> (a + 1),
                    fn (s: string): int -> (s.length())
                )
                """, """
                def f : overload ( fn ( a : int ) : int , fn ( s : string ) : int ) = overload (
                fn ( a : int ) : int -> ( a + 1 ) ,
                fn ( s : string ) : int -> ( s . length ( ) )
                )
                """);
    }

    @Test
    public void testMultilineAndRawStrings() throws Exception {
        // Top-level (line.indent = 0 -> 4 spaces), intentional extra indent preserved, trailing space stripped
        assertFormats("""
                def s = \"\"\"
                      alpha
                    beta

                    gamma\\s
                    \"\"\"
                """, """
                def s=\"\"\"  \s
                  alpha  \s
                beta
                  \s
                gamma\\s  \s
                \"\"\"
                """);

        // Block-level (line.indent = 1 -> 8 spaces) and continuation (line.indent = 2 -> 12 spaces)
        assertFormats("""
                def f = fn (): string -> (
                    def a = \"\"\"
                        hello
                        \"\"\"
                    def b =
                        r\"\"\"
                            C:\\Users\\ada
                            \"\"\"
                    a + b
                )
                """, """
                def f = fn () : string -> (
                def a = \"\"\"
                  hello
                  \"\"\"
                def b =
                r\"\"\"
                C:\\Users\\ada
                \"\"\"
                a + b
                )
                """);

        // Closing delimiter on last content line, line continuation, and trailing comment
        assertFormats("""
                def c = \"\"\"
                    first \\
                    second\"\"\"  ;; trailing
                """, """
                def c = \"\"\"
                  first \\
                  second\"\"\" ;; trailing
                """);

        // Uniform tab indentation canonicalizes to spaces when no extra leading tabs exist
        assertFormats("""
                def t = \"\"\"
                    one
                    two
                    \"\"\"
                """, "def t = \"\"\"\n\tone\n\ttwo\n\t\"\"\"\n");

        // Extra leading tab in raw/escaped block preserves oldMinIndent so spaces+tabs are never mixed
        String rawTab = "def rt = r\"\"\"\n\tindented\n\"\"\"\n";
        assertFormats(rawTab, rawTab);
        String tabExtraBody = "def tb = \"\"\"\n\ta\n\t\tb\n\t\"\"\"\n";
        assertFormats(tabExtraBody, tabExtraBody);
        String rawTabExtraBody = "def rtb = r\"\"\"\n\ta\n\t\tb\n\t\"\"\"\n";
        assertFormats(rawTabExtraBody, rawTabExtraBody);

        // Whitespace-only closing line with extra tab does not emit tabs past minIndent, so canonicalizes to spaces idempotently
        assertFormats("def tc = \"\"\"\n    a\n    \"\"\"\n", "def tc = \"\"\"\n\ta\n\t\t\"\"\"\n");
        assertFormats("def rtc = r\"\"\"\n    a\n    \"\"\"\n", "def rtc = r\"\"\"\n\ta\n\t\t\"\"\"\n");
        assertFormats("\"\"\"\n    a\n    \"\"\"\n", "\"\"\"\n\ta\n\t\t\"\"\"\n");
        assertFormats("r\"\"\"\n    a\n    \"\"\"\n", "r\"\"\"\n\ta\n\t\t\"\"\"\n");

        // Invalid multiline and unclosed single-line strings are rejected cleanly
        Assertions.assertThrows(ScriptException.class, () -> Formatter.format("def s = \"\"\"abc\"\"\""));
        Assertions.assertThrows(ScriptException.class, () -> Formatter.format("def s = \"\"\"\n \tbad\n \"\"\""));
        Assertions.assertThrows(ScriptException.class, () -> Formatter.format("def s = \"\"\"\n    \\q\n    \"\"\""));
        Assertions.assertThrows(ScriptException.class, () -> Formatter.format("def s = r\"open"));
    }

    @Test
    public void testTryCatchFinallyAndThrowFormatting() throws Exception {
        assertFormats("""
                def v = try (
                    throw exception("boom")
                ) catch e: java.io.IOException | exception then (
                    e.getMessage()
                ) catch : java.lang.RuntimeException then (
                    "rt"
                ) catch e then (
                    throw e
                ) catch then (
                    "fallback"
                ) finally (
                    cleanup()
                )
                """, """
                def v=try
                (
                throw   exception("boom")
                )
                catch   e :java.io.IOException|exception   then
                (
                e.getMessage()
                )
                catch:java.lang.RuntimeException then(
                "rt"
                )
                catch e then(
                throw e
                )
                catch then(
                "fallback"
                )
                finally
                (
                cleanup()
                )
                """);
        // Comments between clauses are preserved without breaking line joins.
        assertFormats("""
                try (
                    work()
                )
                ;; handle failure
                catch e: exception then (
                    recover(e)
                )
                ;; always clean up
                finally (
                    close()
                )
                """, """
                try (
                work()
                )
                ;; handle failure
                catch e:exception then (
                recover(e)
                )
                ;; always clean up
                finally (
                close()
                )
                """);
    }

    @Test
    public void testBip030NullabilityAndInferredEffectsSyntax() throws Exception {
        assertFormats("""
                def s: string! = "hi"
                def a: (array of int)! = (array of int)(2)
                def b: (string?)! = "ok"
                def u: string! | null = "ok"
                def v: string! | int! = 1
                def arr: array of string! = (array of string)(1)
                def m: java.util.Map of (string!, int!)! = java.util.HashMap()
                def f: fn (a: int!): string! = fn (a: int!): string! -> ("ok")
                def varargs: fn (xs: string!...): void = fn (xs: string!...): void -> (null)
                def fr: frozen (Box?)! = x
                def c1 = x is string!
                def c2 = x as string!
                def c3 = (x as string?)!
                axiom java.io.StringWriter: class (
                    def getBuffer: fn ... : java.lang.StringBuffer!
                )
                axiom java.util.Optional of T: class notnull (
                    def get: fn ... : T!
                    def orElse: fn ... (other: T): T
                ) static (
                    def of: fn of T ... (value: T!): (java.util.Optional of T)!
                )
                axiom java.lang.Foo: class (
                    def a: fn ... (): T!
                    def b: fn (): T!
                    def c: fn (a: int): T!
                    def d: fn: T!
                    def e: fn nothrow: T!
                    def g: fn of T ... : T!
                )
                """, """
                def s : string ! = "hi"
                def a : ( array of int ) ! = ( array of int ) ( 2 )
                def b : ( string ? ) ! = "ok"
                def u : string ! | null = "ok"
                def v : string ! | int ! = 1
                def arr : array of string ! = ( array of string ) ( 1 )
                def m : java . util . Map of ( string ! , int ! ) ! = java . util . HashMap ( )
                def f : fn ( a : int ! ) : string ! = fn ( a : int ! ) : string ! -> ( "ok" )
                def varargs : fn ( xs : string ! ... ) : void = fn ( xs : string ! ... ) : void -> ( null )
                def fr : frozen ( Box ? ) ! = x
                def c1 = x is string !
                def c2 = x as string !
                def c3 = ( x as string ? ) !
                axiom java.io.StringWriter:class (
                def getBuffer:fn ...:java.lang.StringBuffer!
                )
                axiom java . util . Optional  of  T : class  notnull (
                def get : fn  ...  :  T !
                def orElse : fn  ... ( other : T ) : T
                ) static (
                def of : fn  of  T  ... ( value : T ! ) : ( java . util . Optional  of  T ) !
                )
                axiom java.lang.Foo : class (
                def a : fn ... ( ) : T !
                def b : fn ( ) : T !
                def c : fn ( a : int ) : T !
                def d : fn : T !
                def e : fn nothrow : T !
                def g : fn of T ...:T!
                )
                """);
    }

    @Test
    public void testVarFormatting() throws Exception {
        assertFormats("""
                var x: int = 0
                def f = fn (var n: int, var mut xs: list of int): int -> (
                    var acc = n
                    acc += xs.size()
                    acc
                )
                def C = class (def id: int, var count: int, private var hidden: int = 0) (
                    var cb: fn (int): int = fn (k: int): int -> (k)
                ) static (
                    def tag = "C"
                    private var total = 0
                )
                """, """
                var  x : int = 0
                def f = fn ( var  n : int , var  mut  xs : list  of  int ) : int -> (
                var acc=n
                acc+=xs.size()
                acc
                )
                def C = class ( def id : int , var count : int , private  var  hidden : int = 0 ) (
                var cb : fn ( int ) : int = fn ( k : int ) : int -> ( k )
                ) static (
                def tag="C"
                private  var  total = 0
                )
                """);
    }

    @Test
    public void testRangesModAndFloorDivFormatting() throws Exception {
        assertFormats("""
                def r1: range of int = 0..<n
                def r2: range of char = 'a'..'z'
                def r3 = (a + 1)..(b - 1)
                def r4 = (3..<10).step(5).reversed()
                def r5 = -5..5
                def r6 = 0..-1
                def m = (i - 1) mod n
                def q = 7 // 2
                var k = 17
                k //= 5
                """, """
                def r1 : range  of  int = 0 ..< n
                def r2 : range  of  char = 'a' .. 'z'
                def r3 = ( a + 1 ) .. ( b - 1 )
                def r4 = ( 3 ..< 10 ) . step ( 5 ) . reversed ( )
                def r5 = - 5 .. 5
                def r6 = 0 .. - 1
                def m = ( i - 1 )  mod  n
                def q = 7//2
                var k = 17
                k//=5
                """);
    }

    @Test
    public void testMatchFormatting() throws Exception {
        assertFormats("""
                def label = match n
                    case 0 then ("zero")
                    case 1 | 2 | 3 then ("small")
                    case < 0 then ("neg")
                    case >= 10 then ("big")
                    case 4..<10 then ("mid")
                    case in specials then ("special")
                    case is Point(def x, def y) then ("pt")
                    case is Point(x: 0, y: def py) then ("axis")
                    case is list(def h, ...def t) then ("cons")
                    case is (array of int)(1, ...) then ("arr")
                    case def k: int if k > 10 then (
                        def d = k * 2
                        "big : " + d
                    )
                    else def other then (
                        "other : " + other
                    )
                """, """
                def label = match  n
                case  0  then  ( "zero" )
                case 1|2|3 then ("small")
                case <0 then ("neg")
                case >=10 then ("big")
                case 4 ..< 10 then ("mid")
                case  in  specials  then ("special")
                case  is  Point ( def x , def y ) then ("pt")
                case is Point ( x : 0 , y : def py ) then ("axis")
                case is list ( def h , ... def t ) then ("cons")
                case is ( array of int ) ( 1 , ... ) then ("arr")
                case def k : int if k > 10 then
                (
                def d = k * 2
                "big : " + d
                )
                else def other then
                (
                "other : " + other
                )
                """);
        assertFormats("""
                def area = fn (s: Shape): double -> (
                    match s
                        case is Circle(def r) then (3.14 * r * r)
                        case is Rect(def w, def h) then (w * h)
                        else (0.0)
                )
                """, """
                def area = fn (s:Shape):double -> (
                match s
                case is Circle ( def r ) then ( 3.14 * r * r )
                case is Rect ( def w , def h ) then ( w * h )
                else ( 0.0 )
                )
                """);
    }
}
