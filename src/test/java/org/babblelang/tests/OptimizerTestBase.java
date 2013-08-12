package org.babblelang.tests;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public abstract class OptimizerTestBase {

    private BabbleParser.FileContext parse(String code) {
        CharStream input = new ANTLRInputStream(code);
        BabbleLexer lexer = new BabbleLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokenStream);
        parser.setErrorHandler(new BailErrorStrategy());
        return parser.file();
    }

    protected void assertEquivalent(String code1, String code2) {
        assertEquivalent(code1, code2, null);
    }

    protected <T> void assertEquivalent(String code1, String code2, BabbleBaseVisitor<T> modifier) {
        BabbleParser.FileContext tree1 = parse(code1);
        BabbleParser.FileContext tree2 = parse(code2);
        if (modifier != null) {
            modifier.visitFile(tree2);
        }
        Assert.assertEquals(code1 + " is not equivalent to " + code2 + " after applying " + modifier, tree1.getText(), tree2.getText());
    }
}
