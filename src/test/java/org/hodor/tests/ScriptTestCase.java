package org.hodor.tests;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.hodor.parser.HodorLexer;
import org.hodor.parser.HodorParser;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ScriptTestCase extends HodorTestBase {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        File file = new File("src/test/babble");
        Assert.assertTrue(file.isDirectory());
        return findBaFiles(file, new ArrayList<Object[]>());
    }

    private static Collection<Object[]> findBaFiles(File base, ArrayList<Object[]> result) {
        if (base.isFile()) {
            if (base.getName().endsWith(".ba")) {
                result.add(new Object[]{base.getPath().replace(File.separatorChar, '/')});
            }
        } else {
            for (File file : base.listFiles()) {
                findBaFiles(file, result);
            }
        }
        return result;
    }

    private String file;

    public ScriptTestCase(String file) {
        this.file = file;
    }

    @Test
    public void parse() throws Exception {
        CharStream input = new ANTLRInputStream(new FileReader(file));
        HodorLexer lexer = new HodorLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        HodorParser parser = new HodorParser(tokenStream);
        parser.setErrorHandler(new BailErrorStrategy());
        HodorParser.FileContext file = parser.file();
    }

    @Test
    public void run() throws Exception {
        interpretFile(file);
    }
}
