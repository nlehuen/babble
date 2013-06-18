package org.babblelang.tests;

import junit.framework.TestCase;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleListener;
import org.babblelang.parser.BabbleParser;

import java.io.IOException;

public abstract class BabbleTestBase extends TestCase {
    protected BabbleParser.FileContext parse(String path) throws IOException {
        CharStream input = new ANTLRFileStream(path);
        BabbleLexer lexer = new BabbleLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokenStream);
        parser.setErrorHandler(new BailErrorStrategy());
        BabbleParser.FileContext result = parser.file();
        return result;
    }

    protected void parse(String path, BabbleListener listener) throws IOException {
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parse(path));
    }
}
