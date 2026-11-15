package org.babblelang;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The formatter's command line : the standard input, files and directories, and its options.
 */
public class BabblefmtTestCase {
    private static final String UNFORMATTED = "def f=(a)->(\na+1\n)\n";
    private static final String FORMATTED = "def f = (a) -> (\n    a + 1\n)\n";

    @TempDir
    Path directory;

    private record Run(int status, String out, String err) {
    }

    private static Run run(String input, String... args) throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int status = Babblefmt.run(args, new StringReader(input), new PrintWriter(out), new PrintWriter(err));
        return new Run(status, out.toString().replace(System.lineSeparator(), "\n"), err.toString().replace(System.lineSeparator(), "\n"));
    }

    private Path file(String name, String source) throws Exception {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
        return path;
    }

    @Test
    public void testTheStandardInput() throws Exception {
        Assertions.assertEquals(new Run(0, FORMATTED, ""), run(UNFORMATTED));
        Run failed = run("def = 1\n");
        Assertions.assertEquals(Babblefmt.FAILED, failed.status());
        Assertions.assertEquals("", failed.out());
        Assertions.assertEquals("Error : Syntax error : unexpected '=' in <standard input> at line number 1 at column number 5\n", failed.err());
    }

    @Test
    public void testFilesArePrinted() throws Exception {
        Path a = file("a.ba", UNFORMATTED);
        Assertions.assertEquals(new Run(0, FORMATTED, ""), run("", a.toString()));
        Assertions.assertEquals(UNFORMATTED, Files.readString(a));
    }

    @Test
    public void testWriteListAndCheck() throws Exception {
        Path a = file("a.ba", UNFORMATTED);
        Path b = file("lib/b.ba", FORMATTED);
        Path other = file("lib/notes.txt", "not   Babble (");
        String dir = directory.toString();

        Assertions.assertEquals(new Run(0, a + "\n", ""), run("", "-l", dir));
        Assertions.assertEquals(new Run(Babblefmt.FAILED, a + "\n", ""), run("", "-check", dir));
        Assertions.assertEquals(UNFORMATTED, Files.readString(a));

        Assertions.assertEquals(new Run(0, a + "\n", ""), run("", "-w", "-l", dir));
        Assertions.assertEquals(FORMATTED, Files.readString(a));
        Assertions.assertEquals(FORMATTED, Files.readString(b));
        Assertions.assertEquals("not   Babble (", Files.readString(other));

        Assertions.assertEquals(new Run(0, "", ""), run("", "-check", dir));
        Assertions.assertEquals(new Run(0, "", ""), run("", "-w", dir));
    }

    @Test
    public void testAFileThatCannotBeFormattedIsLeftAlone() throws Exception {
        Path broken = file("broken.ba", "def f = (\n");
        Path fine = file("fine.ba", UNFORMATTED);
        Run run = run("", "-w", broken.toString(), fine.toString(), directory.resolve("missing.ba").toString());
        Assertions.assertEquals(Babblefmt.FAILED, run.status());
        Assertions.assertEquals("Error : Syntax error : unexpected end of input in " + broken + " at line number 2 at column number 1\n"
                + "Cannot read " + directory.resolve("missing.ba") + " : no such file\n", run.err());
        Assertions.assertEquals("def f = (\n", Files.readString(broken));
        Assertions.assertEquals(FORMATTED, Files.readString(fine));
    }

    @Test
    public void testTheCommandLine() throws Exception {
        Run help = run("", "-help");
        Assertions.assertEquals(0, help.status());
        Assertions.assertTrue(help.out().startsWith("Usage : babblefmt"));
        Run unknown = run("", "-x");
        Assertions.assertEquals(Babblefmt.USAGE_ERROR, unknown.status());
        Assertions.assertTrue(unknown.err().startsWith("Unknown option : -x\n"));
        Assertions.assertEquals(Babblefmt.USAGE_ERROR, run(UNFORMATTED, "-w").status());
    }
}
