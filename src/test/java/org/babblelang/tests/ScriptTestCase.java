package org.babblelang.tests;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ScriptTestCase extends BabbleTestBase {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        File file = new File("src/test/babble");
        Assert.assertTrue(file.isDirectory());
        return findBaFiles(file, new ArrayList<>());
    }

    private static Collection<Object[]> findBaFiles(File base, ArrayList<Object[]> result) {
        if (base.isFile()) {
            if (base.getName().endsWith(".ba")) {
                result.add(new Object[]{base.getPath().replace(File.separatorChar, '/')});
            }
        } else {
            File[] files = base.listFiles();
            if (files != null) {
                for (File file : files) {
                    findBaFiles(file, result);
                }
            }
        }
        return result;
    }

    private final String file;

    public ScriptTestCase(String file) {
        this.file = file;
    }

    @Test
    public void parse() throws Exception {
        CharStream input = CharStreams.fromFileName(file);
        BabbleLexer lexer = new BabbleLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokenStream);
        parser.setErrorHandler(new BailErrorStrategy());
        parser.file();
    }

    @Test
    public void run() throws Exception {
        interpretFile(file);
    }
}
