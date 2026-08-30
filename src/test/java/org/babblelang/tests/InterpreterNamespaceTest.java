package org.babblelang.tests;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The interpreter tracks the current namespace in a field, entering and leaving scopes
 * as it walks. These tests drive a single Interpreter across two evaluations to check
 * that a failed evaluation leaves it where it started.
 * <p>
 * They cannot go through BabbleTestBase : the engine builds a fresh Interpreter for every
 * script and aborts on the first error, so a leaked scope is invisible from javax.script.
 */
public class InterpreterNamespaceTest {
    private BabbleParser.FileContext parse(String code) {
        BabbleLexer lexer = new BabbleLexer(CharStreams.fromString(code));
        BabbleParser parser = new BabbleParser(new CommonTokenStream(lexer));
        parser.setErrorHandler(new BailErrorStrategy());
        return parser.file();
    }

    /**
     * Evaluates code that must fail, then defines a name and checks it landed in the
     * root namespace. If the failure left the interpreter inside an inner scope, the
     * definition goes there instead and the root never sees it.
     */
    private void assertNamespaceRestored(String failing) {
        Namespace root = new Namespace();
        Interpreter interpreter = new Interpreter(root);

        Assertions.assertThrows(BabbleException.class,
                () -> interpreter.visit(parse(failing)),
                "expected this snippet to fail : " + failing);

        interpreter.visit(parse("def marker = 1"));
        Assertions.assertTrue(root.isDeclared("marker"),
                "after the failure the interpreter was still inside an inner scope");
    }

    @Test
    public void testPackageBodyFailureRestoresNamespace() {
        assertNamespaceRestored("package p ( nosuchname )");
    }

    @Test
    public void testObjectBodyFailureRestoresNamespace() {
        assertNamespaceRestored("def o = object ( nosuchname )");
    }

    @Test
    public void testCallFailureRestoresNamespace() {
        assertNamespaceRestored("def f = () -> ( nosuchname ) ; f()");
    }

    @Test
    public void testParameterBindingFailureRestoresNamespace() {
        assertNamespaceRestored("def f = (a) -> ( a ) ; f()");
    }
}
