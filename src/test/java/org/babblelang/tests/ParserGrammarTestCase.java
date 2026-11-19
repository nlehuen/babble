package org.babblelang.tests;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Verifies RM006 / BIP012 parser invariants :
 * <ul>
 *   <li>Zero {@code @parser::members} and zero semantic predicates in {@code Babble.g4}.</li>
 *   <li>Pure {@link PredictionMode#SLL} parsing (zero LL fallback) across all {@code .ba} and valid {@code .batest} sources.</li>
 *   <li>{@code BIP012-D8} operator precedence for {@code as}, {@code is}, and {@code not}.</li>
 * </ul>
 */
public class ParserGrammarTestCase extends BabbleTestBase {

    @Test
    public void testGrammarHasZeroParserMembersAndZeroPredicates() throws IOException {
        Path grammarPath = Path.of("src/main/antlr4/org/babblelang/parser/Babble.g4");
        String content = Files.readString(grammarPath, StandardCharsets.UTF_8)
                .replaceAll("//.*", "");
        Assertions.assertFalse(content.contains("@parser::members"),
                "Babble.g4 must have zero @parser::members blocks");
        Assertions.assertFalse(content.contains("}?"),
                "Babble.g4 must have zero semantic predicates");
    }

    @Test
    public void testAllBabbleFilesAndBatestSnippetsParseInPureSll() throws IOException {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Path root : List.of(Path.of("src/test/babble"), Path.of("examples"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".ba")).sorted().toList()) {
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
            for (Path file : stream.filter(p -> p.toString().endsWith(".batest")).sorted().toList()) {
                BatestFile parsed = org.babblelang.tests.batest.BatestParser.parse(file, batestRoot.relativize(file).toString());
                String preamble = parsed.preamble() != null && !parsed.preamble().isBlank()
                        ? parsed.preamble() + "\n" : "";
                for (var mod : parsed.modules().entrySet()) {
                    checked++;
                    try {
                        parsePureSll(mod.getValue());
                    } catch (ParseCancellationException e) {
                        failures.add(file + " module " + mod.getKey() + " failed pure SLL parse: " + e.getMessage());
                    }
                }
                for (BatestFile.BatestCase testCase : parsed.cases()) {
                    if (testCase.expected().startsWith("Syntax error") || testCase.expected().startsWith("Lexical error")) {
                        continue;
                    }
                    for (int i = 0; i < testCase.inputs().size(); i++) {
                        String input = (i == 0 ? preamble : "") + testCase.inputs().get(i);
                        checked++;
                        try {
                            parsePureSll(input);
                        } catch (ParseCancellationException e) {
                            failures.add(file + ":" + testCase.line() + " failed pure SLL parse: " + e.getMessage());
                        }
                    }
                }
            }
        }

        Assertions.assertTrue(checked > 400, "Expected to check >400 sources in SLL mode, checked " + checked);
        Assertions.assertEquals(List.of(), failures, "Pure SLL failures:\n" + String.join("\n", failures));
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
        assertTypeCheckFails("def x : any = list(1) ; x is list of int", "Cannot test erased type arguments with 'is' : use 'is list'");
        assertTypeCheckFails("def f of T = fn (x : any) -> ( x is T )", "Cannot test type parameter with 'is' : T");
        assertTypeCheckFails("def x : any = 1 ; x is fn () : int", "Cannot test a function type with 'is'");
    }

    private void assertTypeCheckFails(String source, String expectedMessage) {
        javax.script.ScriptException ex = Assertions.assertThrows(javax.script.ScriptException.class, () -> interpret(source));
        Assertions.assertTrue(ex.getMessage().contains(expectedMessage),
                "Expected message containing <" + expectedMessage + "> but got <" + ex.getMessage() + ">");
    }

    private static void parsePureSll(String source) {
        BabbleLexer lexer = new BabbleLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokens);
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        parser.file();
    }
}
