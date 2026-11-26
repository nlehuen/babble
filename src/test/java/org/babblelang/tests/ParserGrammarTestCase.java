package org.babblelang.tests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.tests.batest.BatestFile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies RM006 / BIP012 parser invariants :
 *
 * <ul>
 *   <li>Zero {@code @parser::members}, zero {@code @lexer::members}, and zero semantic predicates
 *       in {@code Babble.g4}.
 *   <li>Pure {@link PredictionMode#SLL} parsing (zero LL fallback) across all {@code .ba} and valid
 *       {@code .batest} sources.
 *   <li>{@code BIP012-D8} operator precedence for {@code as}, {@code is}, and {@code not}.
 * </ul>
 */
public class ParserGrammarTestCase extends BabbleTestBase {

    @Test
    public void testGrammarHasZeroParserMembersAndZeroPredicates() throws IOException {
        Path grammarPath = Path.of("src/main/antlr4/org/babblelang/parser/Babble.g4");
        String content =
                Files.readString(grammarPath, StandardCharsets.UTF_8).replaceAll("//.*", "");
        Assertions.assertFalse(
                content.contains("@parser::members"),
                "Babble.g4 must have zero @parser::members blocks");
        Assertions.assertFalse(
                content.contains("@lexer::members"),
                "Babble.g4 must have zero @lexer::members blocks");
        Assertions.assertFalse(
                content.contains("}?"), "Babble.g4 must have zero semantic predicates");
    }

    @Test
    public void testAllBabbleFilesAndBatestSnippetsParseInPureSll() throws IOException {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Path root : List.of(Path.of("examples"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file :
                        stream.filter(p -> p.toString().endsWith(".ba")).sorted().toList()) {
                    checked++;
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    try {
                        parsePureSll(source);
                    } catch (ParseCancellationException e) {
                        failures.add(file + " failed pure SLL parse: " + e.getMessage());
                    }
                }
            }
        }

        Path batestRoot = Path.of("src/test/batest");
        try (Stream<Path> stream = Files.walk(batestRoot)) {
            for (Path file :
                    stream.filter(p -> p.toString().endsWith(".batest.md")).sorted().toList()) {
                BatestFile parsed =
                        org.babblelang.tests.batest.BatestParser.parse(
                                file, batestRoot.relativize(file).toString());
                String preamble =
                        parsed.preamble() != null && !parsed.preamble().isBlank()
                                ? parsed.preamble() + "\n"
                                : "";
                for (var mod : parsed.modules().entrySet()) {
                    checked++;
                    try {
                        parsePureSll(mod.getValue());
                    } catch (ParseCancellationException e) {
                        failures.add(
                                file
                                        + " module "
                                        + mod.getKey()
                                        + " failed pure SLL parse: "
                                        + e.getMessage());
                    }
                }
                for (BatestFile.BatestCase testCase : parsed.cases()) {
                    if (testCase.expectedError() != null
                            && (testCase.expectedError().text().startsWith("Syntax error")
                                    || testCase.expectedError()
                                            .text()
                                            .startsWith("Lexical error"))) {
                        continue;
                    }
                    for (var mod : testCase.caseModules().entrySet()) {
                        checked++;
                        try {
                            parsePureSll(mod.getValue());
                        } catch (ParseCancellationException e) {
                            failures.add(
                                    file
                                            + ":"
                                            + testCase.line()
                                            + " module "
                                            + mod.getKey()
                                            + " failed pure SLL parse: "
                                            + e.getMessage());
                        }
                    }
                    for (int i = 0; i < testCase.inputs().size(); i++) {
                        String input = (i == 0 ? preamble : "") + testCase.inputs().get(i);
                        checked++;
                        try {
                            parsePureSll(input);
                        } catch (ParseCancellationException e) {
                            failures.add(
                                    file
                                            + ":"
                                            + testCase.line()
                                            + " failed pure SLL parse: "
                                            + e.getMessage());
                        }
                    }
                }
            }
        }

        Assertions.assertTrue(
                checked > 400, "Expected to check >400 sources in SLL mode, checked " + checked);
        Assertions.assertEquals(
                List.of(), failures, "Pure SLL failures:\n" + String.join("\n", failures));
    }

    @Test
    public void testPrecedenceAlignmentD8() throws Exception {
        // `as` binds tighter than `*`/`/` and `+`/`-`, looser than unary `-`.
        Assertions.assertEquals(6.0, interpret("1 + 2 as double + 3"));
        Assertions.assertEquals(7.0, interpret("1 + 2 as double * 3"));
        Assertions.assertEquals(-2.0, interpret("-2 as double"));

        // `is` binds tighter than comparisons (`==`, `!=`, `<`, ...).
        Assertions.assertEquals(true, interpret("def x : any = 42 ; x is int == true"));
        Assertions.assertEquals(false, interpret("def x : any = 42 ; x is string == true"));
        Assertions.assertEquals(true, interpret("1 + 2 is int == true"));

        // `not` binds looser than comparisons and `is`, tighter than `and` / `or`.
        Assertions.assertEquals(true, interpret("not 1 == 2"));
        Assertions.assertEquals(false, interpret("not 1 == 1"));
        Assertions.assertEquals(true, interpret("def x : any = \"hi\" ; not x is int"));
        Assertions.assertEquals(false, interpret("def x : any = 42 ; not x is int"));
        Assertions.assertEquals(false, interpret("not false and false"));
        Assertions.assertEquals(true, interpret("not true or true"));
    }

    @Test
    public void testUnifiedIsTypeSemantics() throws Exception {
        Assertions.assertEquals(true, interpret("def x : any = 1 ; x is int | string"));
        Assertions.assertEquals(true, interpret("def x : any = \"a\" ; x is int | string"));
        Assertions.assertEquals(false, interpret("def x : any = 1.5 ; x is int | string"));
        Assertions.assertEquals(true, interpret("def x : any? = null ; x is null"));
        Assertions.assertEquals(true, interpret("def x : any? = null ; x is int?"));
        Assertions.assertEquals(false, interpret("def x : any = 1 ; x is double"));
        assertTypeCheckFails("def x : any = 1 ; x is void", "Type error : cannot test 'is void'");
        assertTypeCheckFails(
                "def x : any = list(1) ; x is list of int",
                "Cannot test erased type arguments with 'is' : use 'is list'");
        assertTypeCheckFails(
                "def f of T = fn (x : any) -> ( x is T )",
                "Cannot test type parameter with 'is' : T");
        assertTypeCheckFails(
                "def x : any = 1 ; x is fn () : int", "Cannot test a function type with 'is'");
        assertTypeCheckFails(
                "def Box = class (def x: int) () ; def f = fn (x: any) -> ( x is frozen Box )",
                "frozen");
    }

    @Test
    public void testBip007OwnershipSllGrammar() {
        parsePureSll(
                """
                axiom java.util.ArrayList: class () static (def new: fn pure unique)
                axiom java.nio.file.Files.readString: fn volatile
                axiom java.net.URL.equals: fn io
                def f = fn (unique a: Box, shared b: Box, mut c: Box): unique Box -> (
                    def x: frozen list of int = freeze(list(1, 2))
                    def y: frozen (Box | int)? = null
                    def z = a.unique + a.frozen + a.io + a.volatile + a.shared + java.io.File
                    discard(x)
                    discard(y)
                    discard(z)
                    move(a)
                )
                def g = fn volatile mut (mut shared b: Box): Box -> ( b )
                def h = fn io (cb: fn io (x: int): int): int -> ( cb(1) )
                def ft: fn (unique Box): unique Box = f
                """);
    }

    @Test
    public void testDeprecatedAxiomOnSyntaxIsGone() {
        // BIP027-D6 : `axiom ... on ...`, `io on ...` and `def x = axiom ... on ...` no longer
        // parse,
        // and `on` is an ordinary name.
        for (String retired :
                List.of(
                        "axiom pure on java.lang.Math.abs",
                        "io on java.lang.System.nanoTime",
                        "export axiom pure on java.lang.Math",
                        "axiom pure unique on java.util.ArrayList()",
                        "def m = axiom pure on java.lang.Math.abs")) {
            Assertions.assertThrows(
                    ParseCancellationException.class, () -> parsePureSll(retired), retired);
        }
        parsePureSll("def on = 1\non + 1");
    }

    @Test
    public void testBip028ExceptionHandlingSllGrammar() {
        parsePureSll(
                """
                def r1 = try (1) catch e: exception then (2) finally (3)
                def r2 = try (1) catch : java.io.IOException | exception then (2)
                def r3 = try (1) catch e then (throw e)
                def r4 = try (1) catch then (0)
                def r5 = try (1) finally (2)
                def r6 = a.try + a.catch + a.finally + a.throw + a.exception
                def r7 = f(try: 1, catch: 2, finally: 3, throw: 4, exception: 5)
                def E: any = exception
                """);
    }

    @Test
    public void testBip030NullabilityAndInferredEffectsSllGrammar() throws Exception {
        parsePureSll(
                """
                def s: string! = "hi"
                def a: (array of int)! = (array of int)(2)
                def b: (string?)! = "ok"
                def u: string! | null = "ok"
                def v: string! | int! = 1
                def arr: array of string! = (array of string)(1)
                def m: java.util.Map of (string!, int!)! = java.util.HashMap()
                def f: fn (a: int!): string! = fn (a: int!): string! -> ("ok")
                def varargs: fn (xs: string!...): void = fn (xs: string!...): void -> (null)
                def fr: frozen (Box?)! = box
                def J = interface extends I! ()
                def Impl = class () implements I! (
                    def m = fn (): int -> (super(I!).m())
                )
                def c1 = x as string!
                def c2 = (x as string?)!
                def c3 = x as (string?)!
                def c4 = x as string?!
                def c5 = x as string!!
                def c6 = x is string!
                def c7 = g of string!(1)
                def c8 = a != b
                axiom java.io.StringWriter: class notnull (
                    def getBuffer: fn ... : java.lang.StringBuffer!
                    def append: fn ... (a: string!): java.io.StringWriter!
                    def empty: fn ... (): string!
                    def sigOnly: fn (): string!
                    def retOnly: fn : string!
                    def nothrowRet: fn nothrow : string!
                    def genericRet: fn of T ... : T!
                    def uniqueRet: fn : unique string!
                ) static (
                    def new: fn pure unique (): java.io.StringWriter!
                )
                """);

        // AST shape: `x as string!` binds `!` to `SimpleType`, not outer `NonNullAssertion`.
        BabbleParser.FileContext asBang = parsePureSll("x as string!");
        BabbleParser.AsExpressionContext asBangExpr =
                Assertions.assertInstanceOf(
                        BabbleParser.AsExpressionContext.class, asBang.expression(0));
        BabbleParser.NonParenTypeContext asBangRight =
                Assertions.assertInstanceOf(
                        BabbleParser.NonParenTypeContext.class, asBangExpr.right.nullableType(0));
        BabbleParser.SimpleTypeContext asBangSimple =
                Assertions.assertInstanceOf(
                        BabbleParser.SimpleTypeContext.class, asBangRight.nonParenNullableType());
        Assertions.assertNotNull(asBangSimple.nullMod);
        Assertions.assertEquals(BabbleLexer.BANG, asBangSimple.nullMod.getType());

        // AST shape: `(x as string?)!` wraps `BlockExpression(AsExpression(..., ?))` in
        // `NonNullAssertion`.
        BabbleParser.FileContext parenAsBang = parsePureSll("(x as string?)!");
        BabbleParser.NonNullAssertionContext parenAssert =
                Assertions.assertInstanceOf(
                        BabbleParser.NonNullAssertionContext.class, parenAsBang.expression(0));
        BabbleParser.BlockExpressionContext innerBlock =
                Assertions.assertInstanceOf(
                        BabbleParser.BlockExpressionContext.class, parenAssert.expression());
        BabbleParser.AsExpressionContext innerAs =
                Assertions.assertInstanceOf(
                        BabbleParser.AsExpressionContext.class, innerBlock.block().expression(0));
        BabbleParser.SimpleTypeContext innerSimple =
                Assertions.assertInstanceOf(
                        BabbleParser.SimpleTypeContext.class,
                        ((BabbleParser.NonParenTypeContext) innerAs.right.nullableType(0))
                                .nonParenNullableType());
        Assertions.assertEquals(BabbleLexer.QUESTION, innerSimple.nullMod.getType());

        // AST shape: `x as string?!` lexes `?` + `!`; `?` attaches to type, `!` is outer
        // `NonNullAssertion`.
        BabbleParser.FileContext asQBang = parsePureSll("x as string?!");
        BabbleParser.NonNullAssertionContext qBangAssert =
                Assertions.assertInstanceOf(
                        BabbleParser.NonNullAssertionContext.class, asQBang.expression(0));
        BabbleParser.AsExpressionContext qBangAs =
                Assertions.assertInstanceOf(
                        BabbleParser.AsExpressionContext.class, qBangAssert.expression());
        BabbleParser.SimpleTypeContext qBangSimple =
                Assertions.assertInstanceOf(
                        BabbleParser.SimpleTypeContext.class,
                        ((BabbleParser.NonParenTypeContext) qBangAs.right.nullableType(0))
                                .nonParenNullableType());
        Assertions.assertEquals(BabbleLexer.QUESTION, qBangSimple.nullMod.getType());

        // AST shape: `x is string!` binds `!` to `SimpleType`.
        BabbleParser.FileContext isBang = parsePureSll("x is string!");
        BabbleParser.IsExpressionContext isBangExpr =
                Assertions.assertInstanceOf(
                        BabbleParser.IsExpressionContext.class, isBang.expression(0));
        BabbleParser.SimpleTypeContext isBangSimple =
                Assertions.assertInstanceOf(
                        BabbleParser.SimpleTypeContext.class,
                        ((BabbleParser.NonParenTypeContext) isBangExpr.right.nullableType(0))
                                .nonParenNullableType());
        Assertions.assertEquals(BabbleLexer.BANG, isBangSimple.nullMod.getType());

        // AST shape: `fn ... : T!` has `ELLIPSIS` effect word, null `fnTypeParameters`, non-null
        // return `type`.
        BabbleParser.FileContext inferredAxiom =
                parsePureSll(
                        "axiom java.io.StringWriter.getBuffer: fn ... : java.lang.StringBuffer!");
        BabbleParser.AxiomExpressionContext axiomExpr =
                Assertions.assertInstanceOf(
                        BabbleParser.AxiomExpressionContext.class, inferredAxiom.expression(0));
        BabbleParser.AxiomTypeContext axiomType = axiomExpr.axiomType();
        Assertions.assertNull(axiomType.fnTypeParameters());
        Assertions.assertNotNull(axiomType.type());
        Assertions.assertNotNull(axiomType.axiomEffectPrefix().axiomEffectWord(0).ELLIPSIS());

        // AST shape: bare return-only `fn : string!` has null prefix, null params, non-null return
        // type.
        BabbleParser.FileContext retOnlyAxiom =
                parsePureSll("axiom java.lang.Foo.bar: fn : string!");
        BabbleParser.AxiomExpressionContext retOnlyExpr =
                Assertions.assertInstanceOf(
                        BabbleParser.AxiomExpressionContext.class, retOnlyAxiom.expression(0));
        Assertions.assertNull(retOnlyExpr.axiomType().axiomEffectPrefix());
        Assertions.assertNull(retOnlyExpr.axiomType().fnTypeParameters());
        Assertions.assertNotNull(retOnlyExpr.axiomType().type());

        // Bare `null!` and stacked nullability modifiers in type position are syntax errors.
        Assertions.assertThrows(
                ParseCancellationException.class,
                () -> parsePureSll("def f = fn (x: null!): int -> (1)"));
        Assertions.assertThrows(
                ParseCancellationException.class,
                () -> parsePureSll("def f = fn (x: string?!): int -> (1)"));
        Assertions.assertThrows(
                ParseCancellationException.class,
                () -> parsePureSll("def f = fn (x: string!!): int -> (1)"));
        Assertions.assertThrows(
                ParseCancellationException.class,
                () -> parsePureSll("def f = fn (): null! -> (null)"));

        // Runtime `is` and `as` with `T!`.
        Assertions.assertEquals(true, interpret("def x : any = \"hi\" ; x is string! == true"));
        Assertions.assertEquals(false, interpret("def x : any? = null ; x is string! == true"));
        Assertions.assertEquals("hi", interpret("def x : any = \"hi\" ; x as string!"));
        Assertions.assertEquals("hi", interpret("def x : any? = \"hi\" ; (x as string?)!"));
    }

    @Test
    public void testBip031RangesModAndFloorDivSllGrammar() {
        parsePureSll(
                """
                def r1: range of int = 0..<10
                def r2: range of char = 'a'..'z'
                def r3: range of double = 0.0..<1.0
                def r4: (range of int)? = null
                def s = (3..<10).step(5)
                def rev = (0..<5).reversed()
                def m1 = 10 mod 3
                def d1 = 7 // 2
                var n = 17
                n //= 5
                def neg = -5..5
                def neg2 = 0..-1
                def mem = a.range + a.mod + f(range: 1, mod: 2)
                def check = 1..10 is range of int
                def chained = 7 // 2 * 2 + 1 + 5 mod 3
                """);

        // Precedence: unary `-` binds tighter than `..` (`-5..5` is `(-5)..5`).
        BabbleParser.FileContext negRange = parsePureSll("-5..5");
        BabbleParser.RangeExpressionContext negRangeExpr =
                Assertions.assertInstanceOf(
                        BabbleParser.RangeExpressionContext.class, negRange.expression(0));
        Assertions.assertInstanceOf(BabbleParser.NegateContext.class, negRangeExpr.left);
        Assertions.assertInstanceOf(BabbleParser.IntegerContext.class, negRangeExpr.right);

        // Precedence: `..` binds tighter than `+` (`a + 1..b - 1` is `(a + (1..b)) - 1`).
        BabbleParser.FileContext arithRange = parsePureSll("a + 1..b - 1");
        BabbleParser.BinaryOpContext outerSub =
                Assertions.assertInstanceOf(
                        BabbleParser.BinaryOpContext.class, arithRange.expression(0));
        Assertions.assertEquals("-", outerSub.op.getText());
        BabbleParser.BinaryOpContext innerAdd =
                Assertions.assertInstanceOf(BabbleParser.BinaryOpContext.class, outerSub.left);
        Assertions.assertEquals("+", innerAdd.op.getText());
        Assertions.assertInstanceOf(BabbleParser.RangeExpressionContext.class, innerAdd.right);

        // Precedence: `mod` and `//` sit at multiplicative precedence (`1 + 5 mod 3` is `1 + (5 mod
        // 3)`).
        BabbleParser.FileContext modPrec = parsePureSll("1 + 5 mod 3");
        BabbleParser.BinaryOpContext modAdd =
                Assertions.assertInstanceOf(
                        BabbleParser.BinaryOpContext.class, modPrec.expression(0));
        Assertions.assertEquals("+", modAdd.op.getText());
        BabbleParser.BinaryOpContext modRight =
                Assertions.assertInstanceOf(BabbleParser.BinaryOpContext.class, modAdd.right);
        Assertions.assertEquals("mod", modRight.op.getText());

        // Precedence: `//` and `*` are left-associative (`7 // 2 * 2` is `(7 // 2) * 2`).
        BabbleParser.FileContext divMul = parsePureSll("7 // 2 * 2");
        BabbleParser.BinaryOpContext mulTop =
                Assertions.assertInstanceOf(
                        BabbleParser.BinaryOpContext.class, divMul.expression(0));
        Assertions.assertEquals("*", mulTop.op.getText());
        BabbleParser.BinaryOpContext divLeft =
                Assertions.assertInstanceOf(BabbleParser.BinaryOpContext.class, mulTop.left);
        Assertions.assertEquals("//", divLeft.op.getText());

        // Precedence: member access binds tighter than `..<` (`3..<10.step(5)` is
        // `3..<(10.step(5))`).
        BabbleParser.FileContext unparen = parsePureSll("3..<10.step(5)");
        BabbleParser.RangeExpressionContext unparenRange =
                Assertions.assertInstanceOf(
                        BabbleParser.RangeExpressionContext.class, unparen.expression(0));
        Assertions.assertInstanceOf(BabbleParser.IntegerContext.class, unparenRange.left);
        Assertions.assertInstanceOf(BabbleParser.CallContext.class, unparenRange.right);

        // Precedence: `1..10 is range of int` parses as `(1..10) is range of int`.
        BabbleParser.FileContext isRange = parsePureSll("1..10 is range of int");
        BabbleParser.IsExpressionContext isRangeExpr =
                Assertions.assertInstanceOf(
                        BabbleParser.IsExpressionContext.class, isRange.expression(0));
        Assertions.assertInstanceOf(BabbleParser.RangeExpressionContext.class, isRangeExpr.left);
    }

    @Test
    public void testBip038PatternMatchingSllGrammar() {
        parsePureSll(
                """
                def area = fn (s: Shape): double -> (
                    match s
                        case is Circle(def r) then (3.14 * r * r)
                        case is Rect(def w, def h) then (w * h)
                )
                def classify = fn (n: int, target: int, specials: set of int): string -> (
                    match n
                        case 0 then ("zero")
                        case 1 | 2 | 3 then ("tiny")
                        case target + 1 then ("above")
                        case < 0 then ("neg")
                        case <= -10 then ("very neg")
                        case > 100 then ("big")
                        case >= 50 then ("medium")
                        case == 42 then ("answer")
                        case != 99 then ("not99")
                        case <> 88 then ("not88")
                        case 4..<10 then ("single")
                        case 10..99 if n mod 10 == 0 then ("round")
                        case (3..<10).step(5) then ("stepped")
                        case in specials then ("special")
                        case is A & B then ("inter")
                        case is (A | B) then ("union")
                        case is A | null then ("or-null")
                        case def whole: Line(is Point(def x1, _), is Point(x: 0, y: def y2)) if x1 == y2 then ("vert")
                        case is Point(0, ...) then ("prefix")
                        case is list() then ("empty")
                        case is list(def h, ...def t) then ("cons")
                        case is list(...) then ("anylist")
                        case is (array of int)(1, 2, ...def rest) then ("arr")
                        case def u: int | string then ("u")
                        else def other then ("other : " + other)
                )
                def mem = a.match + a.case + f(match: 1, case: 2)
                def nested = 1 + match x case 1 then (2) else (3)
                """);

        // Pin SLL alternative order: `case def x if x > 0` parses as `bindingPattern`, not
        // `valuePattern(defExpression)`.
        BabbleParser.FileContext bindGuard =
                parsePureSll("match n case def x if x > 0 then (x) else (0)");
        BabbleParser.MatchExpressionContext matchExpr =
                Assertions.assertInstanceOf(
                        BabbleParser.MatchExpressionContext.class, bindGuard.expression(0));
        BabbleParser.CaseClauseContext firstCase = matchExpr.caseClause(0);
        BabbleParser.BindingPatternContext bindPat =
                Assertions.assertInstanceOf(
                        BabbleParser.BindingPatternContext.class,
                        firstCase.pattern().atomicPattern(0));
        Assertions.assertEquals("x", bindPat.name.getText());
        Assertions.assertNotNull(firstCase.guard);

        // `case is A | is B` parses as OR of two `typeOrDestructurePattern`s (not a union inside
        // `patternType`).
        BabbleParser.FileContext orTypes =
                parsePureSll("match x case is A | is B then (1) else (2)");
        BabbleParser.MatchExpressionContext orMatch =
                Assertions.assertInstanceOf(
                        BabbleParser.MatchExpressionContext.class, orTypes.expression(0));
        Assertions.assertEquals(2, orMatch.caseClause(0).pattern().atomicPattern().size());
        Assertions.assertInstanceOf(
                BabbleParser.TypeOrDestructurePatternContext.class,
                orMatch.caseClause(0).pattern().atomicPattern(0));
        Assertions.assertInstanceOf(
                BabbleParser.TypeOrDestructurePatternContext.class,
                orMatch.caseClause(0).pattern().atomicPattern(1));

        // `case def x: A | B` parses as a single `bindingPattern` with a union
        // `patternBindingType`.
        BabbleParser.FileContext bindUnion =
                parsePureSll("match x case def v: A | B then (v) else (null)");
        BabbleParser.MatchExpressionContext bindUnionMatch =
                Assertions.assertInstanceOf(
                        BabbleParser.MatchExpressionContext.class, bindUnion.expression(0));
        Assertions.assertEquals(1, bindUnionMatch.caseClause(0).pattern().atomicPattern().size());
        BabbleParser.BindingPatternContext unionBind =
                Assertions.assertInstanceOf(
                        BabbleParser.BindingPatternContext.class,
                        bindUnionMatch.caseClause(0).pattern().atomicPattern(0));
        Assertions.assertEquals(2, unionBind.patternBindingType().type().nullableType().size());
    }

    private void assertTypeCheckFails(String source, String expectedMessage) {
        javax.script.ScriptException ex =
                Assertions.assertThrows(
                        javax.script.ScriptException.class, () -> interpret(source));
        Assertions.assertTrue(
                ex.getMessage().contains(expectedMessage),
                "Expected message containing <"
                        + expectedMessage
                        + "> but got <"
                        + ex.getMessage()
                        + ">");
    }

    private static BabbleParser.FileContext parsePureSll(String source) {
        BabbleLexer lexer = new BabbleLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokens);
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        return parser.file();
    }
}
