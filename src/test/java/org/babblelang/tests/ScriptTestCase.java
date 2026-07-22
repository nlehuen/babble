package org.babblelang.tests;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

public class ScriptTestCase extends BabbleTestBase {

    static Collection<String> data() {
        File file = new File("src/test/babble");
        Assertions.assertTrue(file.isDirectory());
        return findBaFiles(file, new ArrayList<>());
    }

    private static Collection<String> findBaFiles(File base, ArrayList<String> result) {
        if (base.isFile()) {
            if (base.getName().endsWith(".ba")) {
                result.add(base.getPath().replace(File.separatorChar, '/'));
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void parse(String file) throws Exception {
        CharStream input = CharStreams.fromFileName(file);
        BabbleLexer lexer = new BabbleLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BabbleParser parser = new BabbleParser(tokenStream);
        parser.setErrorHandler(new BailErrorStrategy());
        parser.file();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void run(String file) throws Exception {
        interpretFile(file);
    }
}
