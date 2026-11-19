package org.babblelang.runtime;

import org.babblelang.engine.impl.natives.AssertFunction;
import org.babblelang.engine.impl.natives.ClassofFunction;
import org.babblelang.engine.impl.natives.ConvertFunction;
import org.babblelang.engine.impl.natives.DiscardFunction;
import org.babblelang.engine.impl.natives.IdentityFunction;
import org.babblelang.engine.impl.natives.PrintFunction;
import org.babblelang.engine.impl.natives.SameFunction;
import org.babblelang.engine.impl.natives.java.ImportFunction;

import java.io.PrintWriter;
import java.io.Writer;

/**
 * The names every script sees without defining them : {@code print}, {@code assert},
 * {@code import}, {@code int} and {@code double}, the {@code java} and {@code javax} packages, … (several with French
 * aliases). The engine and a compiled program's launcher build them the same way.
 */
public final class Implicits {
    private Implicits() {
    }

    /** The built-ins, shared by every eval of an engine. */
    public static Namespace create() {
        Namespace implicits = new Namespace();
        ImportFunction importFunction = new ImportFunction();
        implicits.define("java", true).set(importFunction.getPackage("java"));
        implicits.define("javax", true).set(importFunction.getPackage("javax"));
        implicits.define("import", true).set(importFunction);
        implicits.define("importe", true).set(importFunction);
        implicits.define("print", true).set(new PrintFunction(false));
        implicits.define("println", true).set(new PrintFunction(true));
        implicits.define("affiche", true).set(new PrintFunction(false));
        implicits.define("afficherc", true).set(new PrintFunction(true));
        implicits.define("assert", true).set(new AssertFunction());
        implicits.define("suppose", true).set(new AssertFunction());
        implicits.define("same", true).set(new SameFunction());
        implicits.define("classof", true).set(new ClassofFunction());
        implicits.define("move", true).set(new IdentityFunction("move"));
        implicits.define("freeze", true).set(new IdentityFunction("freeze"));
        implicits.define("discard", true).set(new DiscardFunction());
        implicits.define("byte", true).set(ConvertFunction.BYTE);
        implicits.define("short", true).set(ConvertFunction.SHORT);
        implicits.define("int", true).set(ConvertFunction.INT);
        implicits.define("long", true).set(ConvertFunction.LONG);
        implicits.define("float", true).set(ConvertFunction.FLOAT);
        implicits.define("double", true).set(ConvertFunction.DOUBLE);
        implicits.define("bool", true).set(ConvertFunction.BOOL);
        implicits.define("char", true).set(ConvertFunction.CHAR);
        implicits.define("string", true).set(ConvertFunction.STRING);
        return implicits;
    }

    /**
     * The namespace of one eval's output, under the built-ins : {@code STDOUT}, bound to the
     * writer. A script's root namespace goes under it.
     */
    public static Namespace withOutput(Namespace implicits, Writer writer) {
        Namespace output = implicits.enter();
        output.define("STDOUT", true).set(writer == null || writer instanceof PrintWriter ? writer : new PrintWriter(writer));
        return output;
    }
}
