package org.babblelang.repl;

import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.impl.natives.java.JavaClass;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.runtime.BabbleInstance;
import org.babblelang.runtime.BabbleType;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaValue;
import org.babblelang.runtime.Members;
import org.babblelang.runtime.Module;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What Tab offers in the interactive mode (docs/design/bip004_repl.md §9, {@code BIP004-D5}) :
 * commands, file names after {@code :load}, the names an input can use and the keywords,
 * and the members after a chain of plain names ({@code p.}, {@code java.lang.Math.}). Completion
 * never runs code : a chain is followed by looking names and members up, never by calling,
 * so nothing is offered after a call ({@code f().}).
 */
public final class Completions {
    /** The commands of the loop, as {@code :help} lists them. */
    public static final List<String> COMMANDS = List.of(":cancel", ":help", ":load", ":quit", ":type");

    private static final SortedSet<String> KEYWORDS = keywords();

    /**
     * @param start where the text the candidates replace begins : it ends at the cursor
     * @param candidates what can replace it, sorted
     * @param shared how many characters every candidate starts with that a list of them needn't
     *               show : the receiver of members ({@code java.lang.Math.}), or the directory of files
     */
    public record Completion(int start, List<String> candidates, int shared) {
        public Completion(int start, List<String> candidates) {
            this(start, candidates, 0);
        }
    }

    private Completions() {
    }

    /** What can complete the text before {@code cursor} in {@code buffer}. */
    public static Completion at(BabbleSession session, String buffer, int cursor) {
        String before = buffer.substring(0, Math.min(cursor, buffer.length()));
        String stripped = before.stripLeading();
        if (stripped.startsWith(":")) {
            int commandStart = before.length() - stripped.length();
            int space = indexOfWhitespace(stripped);
            if (space < 0) {
                return new Completion(commandStart, startingWith(COMMANDS, stripped));
            }
            String command = stripped.substring(0, space);
            if (command.equals(":load") || command.equals(":l")) {
                int argument = commandStart + space;
                while (argument < before.length() && Character.isWhitespace(before.charAt(argument))) {
                    argument++;
                }
                String typed = before.substring(argument);
                int quote = typed.startsWith("\"") ? 1 : 0;
                String path = typed.substring(quote);
                return new Completion(argument + quote, files(path), path.lastIndexOf('/') + 1);
            }
            if (!command.equals(":type") && !command.equals(":t")) {
                return new Completion(cursor, List.of());
            }
        }
        return expression(session, before);
    }

    /** The start of the chain of names that ends at the end of {@code before}. */
    public static int wordStart(String before) {
        int start = before.length();
        while (start > 0 && (Character.isLetterOrDigit(before.charAt(start - 1)) || before.charAt(start - 1) == '_'
                || before.charAt(start - 1) == '.')) {
            start--;
        }
        return start;
    }

    private static Completion expression(BabbleSession session, String before) {
        int start = wordStart(before);
        String chain = before.substring(start);
        if (chain.startsWith(".") || !chain.isEmpty() && Character.isDigit(chain.charAt(0)) || chain.contains("..")) {
            // After a call, a parenthesis or a number : completing would mean running something.
            return new Completion(before.length(), List.of());
        }
        int dot = chain.lastIndexOf('.');
        if (dot < 0) {
            SortedSet<String> words = new TreeSet<>(session.names());
            words.addAll(KEYWORDS);
            return new Completion(start, startingWith(words, chain));
        }
        String receiver = chain.substring(0, dot);
        String prefix = chain.substring(dot + 1);
        Object target = resolve(session, receiver);
        List<String> candidates = new ArrayList<>();
        if (target != null) {
            for (String member : members(target)) {
                if (member.startsWith(prefix)) {
                    candidates.add(receiver + "." + member);
                }
            }
        }
        return new Completion(start, candidates, dot + 1);
    }

    /** The value of {@code a.b.c}, looking names and members up only, or null. */
    private static Object resolve(BabbleSession session, String chain) {
        String[] names = chain.split("\\.");
        Object value = session.peek(names[0]);
        for (int i = 1; i < names.length && value != null; i++) {
            try {
                value = Members.select(value, names[i], names[0]);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return value;
    }

    /** The members a value can be selected on, without its private ones. */
    static SortedSet<String> members(Object value) {
        SortedSet<String> members = new TreeSet<>();
        switch (value) {
            case BabbleInstance instance -> {
                ClassShape shape = instance.babbleClass().shape();
                for (int i = 0; i < shape.memberCount(); i++) {
                    if (!shape.isPrivate(i)) {
                        members.add(shape.member(i));
                    }
                }
            }
            case BabbleType type -> {
                ClassShape shape = type.shape();
                for (int i = 0; i < shape.staticCount(); i++) {
                    if (!shape.isStaticPrivate(i)) {
                        members.add(shape.staticMember(i));
                    }
                }
            }
            case Module module -> members.addAll(module.exports());
            case JavaClass javaClass -> javaMembers(javaClass.javaClass(), true, members);
            case JavaValue wrapper when wrapper.javaValue() != null -> javaMembers(wrapper.javaValue().getClass(), false, members);
            case Number number -> {
                // Babble's scalars have no members.
            }
            case Boolean bool -> {
            }
            case Character character -> {
            }
            case Callable callable -> {
            }
            default -> {
                // A Java object, as Java calls return them : not one of Babble's own values.
                if (!value.getClass().getName().startsWith("org.babblelang.")) {
                    javaMembers(value.getClass(), false, members);
                }
            }
        }
        return members;
    }

    /** Public methods and fields, static ones for a class ; and its public member classes. */
    private static void javaMembers(Class<?> type, boolean statics, SortedSet<String> members) {
        try {
            for (Method method : type.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) == statics) {
                    members.add(method.getName());
                }
            }
            for (Field field : type.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) == statics) {
                    members.add(field.getName());
                }
            }
            if (statics) {
                for (Class<?> member : type.getClasses()) {
                    members.add(member.getSimpleName());
                }
            }
        } catch (SecurityException | LinkageError e) {
            // Nothing to offer.
        }
    }

    /** The files and directories whose path starts with {@code typed}, directories ending with a slash. */
    private static List<String> files(String path) {
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash + 1);
        String prefix = path.substring(slash + 1);
        Path dir = Path.of(directory.isEmpty() ? "." : directory);
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(prefix) && (prefix.startsWith(".") || !name.startsWith("."))) {
                    result.add(directory + name + (Files.isDirectory(entry) ? "/" : ""));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Nothing to offer.
        }
        result.sort(null);
        return result;
    }

    private static List<String> startingWith(Iterable<String> words, String prefix) {
        List<String> result = new ArrayList<>();
        for (String word : words) {
            if (word.startsWith(prefix)) {
                result.add(word);
            }
        }
        return result;
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The lexer's words : {@code def}, {@code fn}, {@code if}... */
    private static SortedSet<String> keywords() {
        SortedSet<String> keywords = new TreeSet<>();
        for (int type = 0; type <= BabbleLexer.VOCABULARY.getMaxTokenType(); type++) {
            String literal = BabbleLexer.VOCABULARY.getLiteralName(type);
            if (literal != null && literal.length() > 2) {
                String word = literal.substring(1, literal.length() - 1);
                if (word.chars().allMatch(Character::isLetter)) {
                    keywords.add(word);
                }
            }
        }
        return keywords;
    }
}
