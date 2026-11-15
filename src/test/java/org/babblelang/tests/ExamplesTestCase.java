package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.FileModuleSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Compiles every script of {@code examples/} without running it : the ray tracers take too long
 * to run in the suite, but a language change that breaks them should still fail it. In the
 * compiled run, each script is compiled as a program, with every module it imports statically
 * (and {@code babble.verify} checks the generated classes) ; in the interpreted run, it is
 * parsed, analysed and type-checked, its static imports with it.
 */
public class ExamplesTestCase {
    static List<Path> scripts() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("examples"))) {
            return files.filter(path -> path.toString().endsWith(".ba")).sorted().toList();
        }
    }

    @ParameterizedTest
    @MethodSource("scripts")
    public void testCompiles(Path script) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        StringWriter errors = new StringWriter();
        engine.getContext().setErrorWriter(errors);
        Path file = script.toAbsolutePath().normalize();
        if (engine.getMode() == BabbleScriptEngine.Mode.COMPILE) {
            List<BabbleScriptEngine.ProgramModule> modules =
                    engine.compileProgram(FileModuleSource.rooted(file.getParent()), file.toString());
            Assertions.assertFalse(modules.isEmpty(), script.toString());
        } else {
            engine.getContext().setAttribute(ScriptEngine.FILENAME, file.toString(), ScriptContext.ENGINE_SCOPE);
            engine.compile(Files.readString(file));
        }
        Assertions.assertEquals("", errors.toString(), script + " compiles with warnings");
    }
}
