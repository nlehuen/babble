package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

/**
 * A character the grammar doesn't define must abort compilation. ANTLR's default lexer
 * behaviour is to print to stderr, skip the character and carry on, so the engine used to
 * evaluate a mangled token stream and return normally.
 */
public class BabbleLexerErrorTestCase extends BabbleTestBase {
    private ScriptException assertRejected(String script) {
        return Assertions.assertThrows(ScriptException.class, () -> interpret(script));
    }

    @Test
    public void testSingleQuotesAreRejected() {
        // Used to yield the Integer 1 : both quotes were dropped.
        ScriptException e = assertRejected("def a = '1' ; a");
        Assertions.assertTrue(e.getMessage().contains("Lexical error at line 1:8"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("token recognition error"), e.getMessage());

        assertRejected("def a = '12' ; a");
        assertRejected("def a = 'ab' ; a");
    }

    @Test
    public void testUnknownCharacterIsRejected() {
        // Dropping the '@' left a script that parsed and ran, returning 1.
        ScriptException e = assertRejected("def a@ = 1 ; a");
        Assertions.assertTrue(e.getMessage().contains("Lexical error at line 1:5"), e.getMessage());

        // Already a syntax error once the '#' was dropped, but now reported as the lexical
        // error it actually is.
        Assertions.assertTrue(assertRejected("def a = 1 # 2").getMessage().contains("Lexical error"));
    }

    @Test
    public void testLexicalErrorReportsItsPosition() {
        ScriptException e = assertRejected("def a = 1\ndef b = '2'");
        Assertions.assertTrue(e.getMessage().contains("Lexical error at line 2:8"), e.getMessage());
    }

    @Test
    public void testValidScriptsStillCompile() throws Exception {
        Assertions.assertEquals("ab", interpret("def a = \"ab\" ; a"));
        Assertions.assertEquals(3, interpret("1 + 2"));
    }
}
