package org.babblelang.tests;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ScriptTestCase extends BabbleTestBase {

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
        BabbleLexer lexer = new BabbleLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokenStream);
        parser.setErrorHandler(new BailErrorStrategy());
        BabbleParser.FileContext file = parser.file();
    }

    @Test
    public void run() throws Exception {
        interpretFile(file);
    }
}
