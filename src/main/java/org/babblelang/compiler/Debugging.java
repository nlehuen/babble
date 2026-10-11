package org.babblelang.compiler;

import org.babblelang.engine.BabbleException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The debugging switches that need {@code asm-util}, an optional dependency. This class
 * is only loaded when one of them is on.
 */
final class Debugging {
    private Debugging() {
    }

    static void trace(byte[] bytes) {
        PrintWriter err = new PrintWriter(System.err, true);
        new ClassReader(bytes).accept(new TraceClassVisitor(err), 0);
        err.flush();
    }

    static void verify(byte[] bytes, ClassLoader loader) {
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), loader, false, new PrintWriter(problems));
        if (!problems.toString().isEmpty()) {
            throw new BabbleException("Generated class failed verification :\n" + problems);
        }
    }
}
