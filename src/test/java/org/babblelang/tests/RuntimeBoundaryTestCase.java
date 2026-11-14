package org.babblelang.tests;

import org.babblelang.runtime.Namespace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The runtime boundary (docs/design/module-sources-and-aot.md §3) : what a compiled Babble
 * program needs to run references nothing of the front end (parser, analysis, compiler,
 * interpreter, engine), no third-party library, and no JDK module but {@code java.base}.
 * The runtime is the {@code runtime} package and the built-ins of {@code engine.impl.natives}
 * and {@code engine.impl.natives.java}.
 */
public class RuntimeBoundaryTestCase {
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([A-Za-z_$][\\w/$]*);");
    private static final Set<String> JAVA_BASE = Object.class.getModule().getDescriptor().packages();

    static boolean isRuntime(String internalName) {
        return internalName.startsWith("org/babblelang/runtime/")
                || internalName.startsWith("org/babblelang/engine/impl/natives/");
    }

    private static Path classesDirectory() throws URISyntaxException {
        Path directory = Path.of(Namespace.class.getResource("Namespace.class").toURI()).getParent();
        for (int i = 0; i < Namespace.class.getPackageName().split("\\.").length; i++) {
            directory = directory.getParent();
        }
        return directory;
    }

    /** The runtime's class files, by internal name. */
    static List<String> runtimeClasses() throws Exception {
        Path root = classesDirectory();
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("org/babblelang"))) {
            files.filter(f -> f.toString().endsWith(".class")).forEach(f -> {
                String name = root.relativize(f).toString().replace('\\', '/');
                name = name.substring(0, name.length() - ".class".length());
                if (isRuntime(name)) {
                    result.add(name);
                }
            });
        }
        return result;
    }

    /** Every class a class file names : in class constants, and in descriptors and signatures. */
    static Set<String> referencedClasses(InputStream classFile) throws IOException {
        DataInputStream in = new DataInputStream(classFile);
        Assertions.assertEquals(0xCAFEBABE, in.readInt());
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        List<Integer> classNameIndexes = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classNameIndexes.add(in.readUnsignedShort());
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        Set<String> result = new TreeSet<>();
        for (int index : classNameIndexes) {
            String name = utf8[index];
            if (name.startsWith("[")) {
                Matcher m = DESCRIPTOR_CLASS.matcher(name);
                while (m.find()) {
                    result.add(m.group(1));
                }
            } else {
                result.add(name);
            }
        }
        for (String constant : utf8) {
            if (constant != null && (constant.startsWith("(") || constant.startsWith("L") || constant.startsWith("["))) {
                Matcher m = DESCRIPTOR_CLASS.matcher(constant);
                while (m.find()) {
                    result.add(m.group(1));
                }
            }
        }
        return result;
    }

    /** Why a class may not be referenced from the runtime, or null when it may. */
    static String violation(String referenced) {
        if (referenced.startsWith("org/babblelang/")) {
            return isRuntime(referenced) ? null : "the front end";
        }
        if (referenced.startsWith("java/") || referenced.startsWith("javax/") || referenced.startsWith("jdk/")) {
            int slash = referenced.lastIndexOf('/');
            String pkg = referenced.substring(0, slash).replace('/', '.');
            return JAVA_BASE.contains(pkg) ? null : "a JDK module other than java.base";
        }
        return "a third-party library";
    }

    @Test
    public void theRuntimeNeedsNothingButItselfAndJavaBase() throws Exception {
        Path root = classesDirectory();
        List<String> classes = runtimeClasses();
        Assertions.assertTrue(classes.contains("org/babblelang/runtime/Bootstrap"), classes.toString());
        Assertions.assertTrue(classes.contains("org/babblelang/engine/impl/natives/java/ImportFunction"), classes.toString());
        List<String> violations = new ArrayList<>();
        for (String name : classes) {
            try (InputStream in = Files.newInputStream(root.resolve(name + ".class"))) {
                for (String referenced : referencedClasses(in)) {
                    String why = violation(referenced);
                    if (why != null) {
                        violations.add(name + " -> " + referenced + " (" + why + ")");
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), violations);
    }

    @Test
    public void theCheckSeesWhatItLooksFor() throws Exception {
        // The checks themselves : a front-end class, a library and another JDK module are refused.
        Assertions.assertEquals("the front end", violation("org/babblelang/engine/BabbleScriptEngine"));
        Assertions.assertEquals("the front end", violation("org/babblelang/engine/impl/Interpreter"));
        Assertions.assertEquals("a third-party library", violation("org/antlr/v4/runtime/Parser"));
        Assertions.assertEquals("a JDK module other than java.base", violation("javax/script/ScriptContext"));
        Assertions.assertNull(violation("java/lang/invoke/MethodHandle"));
        Assertions.assertNull(violation("org/babblelang/runtime/Operators"));
        // And the class file reader finds references in descriptors, not only in class constants.
        try (InputStream in = org.babblelang.engine.BabbleScriptEngine.class.getResourceAsStream("BabbleScriptEngine.class")) {
            Set<String> referenced = referencedClasses(in);
            Assertions.assertTrue(referenced.contains("javax/script/ScriptContext"), referenced.toString());
            Assertions.assertTrue(referenced.contains("org/babblelang/parser/BabbleParser$FileContext"), referenced.toString());
        }
    }
}
