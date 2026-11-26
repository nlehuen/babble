package org.babblelang.tests;

import javax.script.ScriptException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A character the grammar doesn't define must abort compilation. ANTLR's default lexer behaviour is
 * to print to stderr, skip the character and carry on, so the engine used to evaluate a mangled
 * token stream and return normally.
 */
public class BabbleLexerErrorTestCase extends BabbleTestBase {
    private ScriptException assertRejected(String script) {
        return Assertions.assertThrows(ScriptException.class, () -> interpret(script));
    }

    @Test
    public void testMalformedSingleQuotesAreRejected() {
        // Multi-char, empty, and unterminated single-quoted literals must still fail lexing.
        ScriptException e = assertRejected("def a = '12' ; a");
        Assertions.assertTrue(e.getMessage().contains("Lexical error at line 1:8"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("token recognition error"), e.getMessage());

        assertRejected("def a = 'ab' ; a");
        assertRejected("def a = '' ; a");
        assertRejected("def a = 'a");
    }

    @Test
    public void testUnknownCharacterIsRejected() {
        // Dropping the '@' left a script that parsed and ran, returning 1.
        ScriptException e = assertRejected("def a@ = 1 ; a");
        Assertions.assertTrue(e.getMessage().contains("Lexical error at line 1:5"), e.getMessage());

        // Already a syntax error once the '#' was dropped, but now reported as the lexical
        // error it actually is.
        Assertions.assertTrue(
                assertRejected("def a = 1 # 2").getMessage().contains("Lexical error"));
    }

    @Test
    public void testLexicalErrorReportsItsPosition() {
        ScriptException e = assertRejected("def a = 1\ndef b = '23'");
        Assertions.assertTrue(e.getMessage().contains("Lexical error at line 2:8"), e.getMessage());
    }

    @Test
    public void testSyntaxErrorsSayWhatAndWhere() {
        ScriptException e = assertRejected("def a = 1\ndef = 2");
        Assertions.assertEquals(
                "org.babblelang.runtime.BabbleException: Syntax error : unexpected '=' in <input> at line number 2 at column number 5",
                e.getMessage());
        Assertions.assertEquals(2, e.getLineNumber());
        Assertions.assertEquals(5, e.getColumnNumber());
        Assertions.assertTrue(
                assertRejected("def a = (1 +")
                        .getMessage()
                        .contains("Syntax error : unexpected end of input"));
        Assertions.assertTrue(
                assertRejected("def\na")
                        .getMessage()
                        .contains("Syntax error : unexpected end of line"));
    }

    @Test
    public void testValidScriptsStillCompile() throws Exception {
        Assertions.assertEquals("ab", interpret("def a = \"ab\" ; a"));
        Assertions.assertEquals("C:\\dir\\", interpret("def a = r\"C:\\dir\\\" ; a"));
        Assertions.assertEquals("a\nb\n", interpret("def a = \"\"\"\r\n  a\r  b\r\n  \"\"\" ; a"));
        Assertions.assertEquals("ab", interpret("def a = \"\"\"\r\n  a\\\r\n  b\"\"\" ; a"));
        Assertions.assertEquals(3, interpret("1 + 2"));
    }

    @Test
    public void testSingleLineAndMultilineLexicalErrors() {
        Assertions.assertTrue(
                assertRejected("def s = \"a\nb\"").getMessage().contains("Lexical error"));
        Assertions.assertTrue(
                assertRejected("def s = \"a\rb\"").getMessage().contains("Lexical error"));
        Assertions.assertTrue(
                assertRejected("def s = r\"a\nb\"").getMessage().contains("Lexical error"));
        Assertions.assertTrue(
                assertRejected("def s = r\"unclosed").getMessage().contains("Lexical error"));

        ScriptException badOpen = assertRejected("def s = \"\"\"abc\"\"\"");
        Assertions.assertTrue(
                badOpen.getMessage()
                        .contains(
                                "Lexical error at line 1:8 : multiline string opening \"\"\" must be followed by a newline"),
                badOpen.getMessage());
        Assertions.assertTrue(
                assertRejected("def s = r\"\"\"abc\"\"\"")
                        .getMessage()
                        .contains("multiline string opening \"\"\" must be followed by a newline"));

        ScriptException mixedWithin = assertRejected("def s = \"\"\"\n \thello\n \"\"\"");
        Assertions.assertTrue(
                mixedWithin
                        .getMessage()
                        .contains("mixed tabs and spaces in multiline string indentation"),
                mixedWithin.getMessage());
        ScriptException mixedAcross =
                assertRejected("def s = r\"\"\"\n    hello\n\tworld\n    \"\"\"");
        Assertions.assertTrue(
                mixedAcross
                        .getMessage()
                        .contains("mixed tabs and spaces in multiline string indentation"),
                mixedAcross.getMessage());

        ScriptException badEsc = assertRejected("def s = \"\"\"\n    \\q\n    \"\"\"");
        Assertions.assertTrue(
                badEsc.getMessage().contains("unknown escape sequence : \\q"), badEsc.getMessage());
        ScriptException badUni = assertRejected("def s = \"\"\"\n    \\u12g\n    \"\"\"");
        Assertions.assertTrue(
                badUni.getMessage().contains("unknown escape sequence : \\u12g"),
                badUni.getMessage());
        ScriptException shortUni = assertRejected("def s = \"\"\"\n    \\u12\n    \"\"\"");
        Assertions.assertTrue(
                shortUni.getMessage().contains("unknown escape sequence : \\u12"),
                shortUni.getMessage());
        ScriptException trailingAfterBackslash = assertRejected("def s = \"\"\"\n    a\\   \"\"\"");
        Assertions.assertTrue(
                trailingAfterBackslash.getMessage().contains("unknown escape sequence : \\"),
                trailingAfterBackslash.getMessage());
    }
}
