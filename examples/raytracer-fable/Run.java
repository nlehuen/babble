import org.babblelang.engine.BabbleScriptEngineFactory;

import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs a Babble script, compiled to bytecode : the only Java code of the ray tracer. */
public class Run {
    public static void main(String[] args) throws Exception {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put("babble.mode", System.getProperty("babble.mode", "compile"));
        engine.put(ScriptEngine.FILENAME, args[0]);     // what the script's imports are relative to
        engine.eval(Files.readString(Path.of(args[0])));
        engine.getContext().getWriter().flush();
    }
}
