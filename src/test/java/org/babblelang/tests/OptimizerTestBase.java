package org.babblelang.tests;

import org.antlr.v4.runtime.*;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;

public abstract class OptimizerTestBase {

    private BabbleParser.FileContext parse(String code) {
        CharStream input = CharStreams.fromString(code);
        BabbleLexer lexer = new BabbleLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokenStream);
        parser.setErrorHandler(new BailErrorStrategy());
        return parser.file();
    }

    void assertEquivalent(String code1, String code2) {
        assertEquivalent(code1, code2, null);
    }

    <T> void assertEquivalent(String code1, String code2, BabbleBaseVisitor<T> modifier) {
        BabbleParser.FileContext tree1 = parse(code1);
        BabbleParser.FileContext tree2 = parse(code2);
        if (modifier != null) {
            modifier.visitFile(tree2);
        }
        Assertions.assertEquals(tree1.getText(), tree2.getText(), code1 + " is not equivalent to " + code2 + " after applying " + modifier);
    }
}
