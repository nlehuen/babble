package org.babblelang.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The index of a compiled program's jar (docs/design/module-sources-and-aot.md §5.4), the
 * resource {@link #RESOURCE} : its main module, and for each module its key, display name,
 * script class and exports. One line each, tab-separated :
 * <pre>
 * main     &lt;key&gt;
 * module   &lt;key&gt;  &lt;display name&gt;  &lt;class&gt;  &lt;export,export,…&gt;
 * </pre>
 */
public record ProgramIndex(String main, Map<String, Entry> modules) {
    public static final String RESOURCE = "babble/modules.index";

    /** A module of the program : {@code className} is a binary name, {@code babble.lib.vec3$ba}. */
    public record Entry(String key, String displayName, String className, String[] exports) {
    }

    public void write(Writer out) throws IOException {
        out.write("main\t" + main + "\n");
        for (Entry entry : modules.values()) {
            out.write("module\t" + entry.key() + "\t" + entry.displayName() + "\t" + entry.className() + "\t"
                    + String.join(",", entry.exports()) + "\n");
        }
    }

    public static ProgramIndex read(InputStream in) throws IOException {
        String main = null;
        Map<String, Entry> modules = new LinkedHashMap<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String[] fields = line.split("\t", -1);
            if (fields[0].equals("main") && fields.length == 2) {
                main = fields[1];
            } else if (fields[0].equals("module") && fields.length == 5) {
                String[] exports = fields[4].isEmpty() ? new String[0] : fields[4].split(",");
                modules.put(fields[1], new Entry(fields[1], fields[2], fields[3], exports));
            } else if (!line.isEmpty()) {
                throw new IOException("Not a line of " + RESOURCE + " : " + line);
            }
        }
        if (main == null || !modules.containsKey(main)) {
            throw new IOException(RESOURCE + " names no main module");
        }
        return new ProgramIndex(main, modules);
    }

    @Override
    public String toString() {
        List<String> lines = new ArrayList<>();
        modules.values().forEach(e -> lines.add(e.key() + " " + e.className() + " " + Arrays.toString(e.exports())));
        return "main " + main + " " + lines;
    }
}
