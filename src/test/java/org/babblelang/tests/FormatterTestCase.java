package org.babblelang.tests;

import org.babblelang.format.Formatter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertFormats("def add: (a: int, b: int): int = (a: int, b: int = 1): int -> (a + b)\n",
                "def add:(a:int,b:int):int=(a:int,b:int=1):int->( a+b )");
        assertFormats("def f = () -> ()\n", "def f=()->( )");
        assertFormats("x += 1\no.n -= int(2.5)\ny *= 2; z /= 3\n", "x+=1\no . n-=int( 2.5 )\ny*=2;z/=3");
        assertFormats("x is java.util.List and x <> null or y != 1.5\n", "x  is java . util . List and x<>null or y!=1.5");
        assertFormats("def x = a + b as int == 1\n", "def x=a+b   as  int==1");
        assertFormats("def f = x as (a: int, b: string): bool\n", "def f = x as(a:int,b:string):bool");
        assertFormats("x is int and y is string\n", "x  is  int  and  y  is  string");
        assertFormats("def x: int | string?\ndef y: (int | string)? = null\ndef z: ((n: int): int)? = (n: int): int -> (n)\n",
                "def x:int|string ?\ndef y:(int|string)?=null\ndef z:((n:int):int)?=(n:int):int->(n)");
        assertFormats("def n: null = null\n", "def  n : null = null");
        assertFormats("return (a)\n", "return(a)");
    }

    @Test
    public void testGenerics() throws Exception {
        assertFormats("""
                def id(T): (x: T): T = (x: T): T -> (x)
                def Stack(T) = class (def value: T, def next: Stack(T)?) ()
                def l: java.util.List(string) = java.util.ArrayList()
                def m: java.util.Map(string, Stack(int)?) = java.util.HashMap()
                def f: ((x: int): Box(int | string))? = null
                """, """
                def id ( T ) : ( x : T ) : T = ( x : T ) : T -> ( x )
                def Stack ( T ) = class ( def value : T , def next : Stack ( T ) ? ) ( )
                def l : java.util.List ( string ) = java.util.ArrayList ( )
                def m : java.util.Map ( string , Stack ( int ) ? ) = java.util.HashMap ( )
                def f : ( ( x : int ) : Box ( int | string ) ) ? = null
                """);
    }

    @Test
    public void testUnaryMinusElseIfAndLeadingDots() throws Exception {
        // A unary minus sticks to its operand ; a binary one is spaced.
        assertFormats("def x = -3 - -y * f(-1, - -z)\n", "def x = - 3--y*f(- 1,- - z)");
        // An "else if" chain is brought back on the lines of its parentheses, as "else" is.
        assertFormats("""
                def sign = (n) -> (
                    if n < 0 then (
                        "negative"
                    ) else if n == 0 then ("zero") else if n < 10 then (
                        "small"
                    ) else ("large")
                )
                """, """
                def sign = (n) -> (
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
                def Point = value class (def x: double, private def y = 0.0) implements Shape, java.lang.Runnable (
                    def norm = () -> (super(Shape).norm() + super.size())
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
                def Point = value class(def x:double,private def y=0.0)implements Shape,java.lang.Runnable(
                def norm = ()->(super (Shape).norm()+super.size())
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
                package p (
                    def f = (n) -> (
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
                package p (
                def f = (n) -> (
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
                package p (
                    if a then (
                        1
                    ) else (
                        2
                    )
                    def f = () -> (
                        3
                    )
                )
                """, """
                package p
                (
                    if a then
                    (
                        1
                    )
                    else
                    (
                        2
                    )
                    def f = () ->

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
                def f = () -> (
                    def total = first +
                        second +
                        third(
                            1
                        )
                    def g =
                        (a) -> (
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
                def f = () -> (
                def total = first +
                second +
                third(
                1
                )
                def g =
                (a) -> (
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
                def f = (
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
                def f = (
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

                def f = () -> (
                    b

                    c
                )

                d
                """, """


                a



                def f = () -> (

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
    public void testTypeApplication() throws Exception {
        assertFormats("def b = Box(int?)(null)\ndef u = Box(int | string)(\"s\")\n", "def b = Box (int?) (null)\ndef u = Box( int|string )( \"s\" )");
    }

    @Test
    public void testScalarLiterals() throws Exception {
        assertFormats("def a = -42L + 1.5f * 2e-3d - 'x'\n", "def a=-42L+1.5f*2e-3d-'x'");
    }

    @Test
    public void testCoreTypesAndNonNullAssertion() throws Exception {
        assertFormats("def xs: list = m.get(k)!.set(0, int(x)!)\nv is int\n",
                "def xs:list=m.get(k) !.set(0,int (x) !)\nv is int");
    }

    @Test
    public void testComments() throws Exception {
        assertFormats("""
                ;; first

                ;;(
                    several
                      lines

                ;;)
                def f = () -> (  ;; a function
                    ;; alone
                    a      ;; aligned
                    b + c  ;; with the line above
                    d ;;( inside
                    ;;) + e

                    ;; last
                )
                def lengthy = 1  ;; not aligned with those above
                ;; end
                """, """
                ;; first  \s

                ;;(
                    several
                      lines
                  \s
                ;;)
                def f = () -> (;; a function
                ;; alone
                a ;; aligned
                b+c ;; with the line above
                d   ;;( inside
                    ;;)   + e

                ;; last
                )
                def lengthy = 1 ;; not aligned with those above
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
        assertFormats("def s = \"a  ;; b ( \\\" \" + \"\n  c\"\n", "def s=\"a  ;; b ( \\\" \"+\"\n  c\"");
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
        try (Stream<Path> tests = Files.walk(Path.of("src/test/babble")); Stream<Path> examples = Files.walk(Path.of("examples"))) {
            scripts = Stream.concat(tests, examples).filter(path -> path.toString().endsWith(".ba")).sorted().toList();
        }
        Assertions.assertFalse(scripts.isEmpty());
        for (Path script : scripts) {
            String formatted = Formatter.format(Files.readString(script), script.toString());
            Assertions.assertEquals(formatted, Formatter.format(formatted, script.toString()), script.toString());
            Assertions.assertTrue(formatted.lines().noneMatch(line -> line.endsWith(" ") || line.contains("\t")), script.toString());
        }
    }

    @Test
    public void testRm002EffectsAndAxioms() throws Exception {
        assertFormats("""
                axiom pure deterministic java.lang.Math
                axiom mut java.util.List.add
                axiom pure java.awt.Point()
                export axiom pure import("java.time")
                impure java.lang.System.nanoTime
                def m = axiom pure java.lang.Math
                def r = impure java.lang.Math.random
                def add: pure deterministic nothrow (a: int, b: int): int = pure deterministic nothrow (a: int, b: int): int -> (
                    a + b
                )
                def id(T) = pure (x: T): T -> (x)
                def step = hermetic (mut p: Point, borrow q: Point): int -> (
                    p.x = q.x
                    p.x
                )
                def pure = (axiom: int, mut: int): int -> (axiom + mut)
                pure(1, 2)
                """, """
                axiom   pure   deterministic   java . lang . Math
                axiom mut java.util.List.add
                axiom pure java.awt.Point ( )
                export   axiom  pure  import ( "java.time" )
                impure   java.lang.System.nanoTime
                def m = axiom   pure  java.lang.Math
                def r = impure   java.lang.Math.random
                def add : pure  deterministic  nothrow ( a : int , b : int ) : int = pure  deterministic  nothrow ( a : int , b : int ) : int -> (
                a+b
                )
                def id ( T ) = pure ( x : T ) : T -> ( x )
                def step = hermetic ( mut  p : Point , borrow  q : Point ) : int -> (
                p.x = q.x
                p.x
                )
                def pure = ( axiom : int , mut : int ) : int -> ( axiom + mut )
                pure ( 1 , 2 )
                """);
    }
}
