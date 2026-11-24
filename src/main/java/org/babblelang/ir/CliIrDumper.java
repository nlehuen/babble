package org.babblelang.ir;

import org.babblelang.runtime.BabbleException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * CLI {@code -Dbabble.dumpIr} observer backed by the unified {@link IrObserver} pipeline
 * ({@code BIP029-P1}, {@code BIP029-D6}).
 */
public final class CliIrDumper {
    private CliIrDumper() {
    }

    public static IrObserver forSpec(String spec, String internalName, int optLevel) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String lower = spec.toLowerCase(Locale.ROOT);
        boolean canonical = lower.endsWith(":canonical") || "canonical".equals(lower);
        boolean hirOnly = lower.startsWith("hir");
        boolean mirOnly = lower.startsWith("mir");
        String afterPrefix = lower.contains(":after:") ? spec.substring(lower.indexOf(":after:") + 1) : null;
        return new IrObserver() {
            @Override
            public boolean wants(IrTier tier, int activeOpt, String stage) {
                if (hirOnly && tier != IrTier.HIR) {
                    return false;
                }
                if (mirOnly && tier != IrTier.MIR) {
                    return false;
                }
                if (afterPrefix != null) {
                    return afterPrefix.equals(stage);
                }
                if (IrSnapshot.STAGE_BEFORE.equals(stage)) {
                    return true;
                }
                return IrSnapshot.STAGE_FINAL.equals(stage) && optLevel > 0;
            }

            @Override
            public void onSnapshot(IrSnapshot snapshot) {
                String tierPrefix = snapshot.tier().label();
                String phase = IrSnapshot.STAGE_BEFORE.equals(snapshot.stage())
                        ? tierPrefix + "-before"
                        : (IrSnapshot.STAGE_FINAL.equals(snapshot.stage())
                        ? tierPrefix + "-after"
                        : tierPrefix + "-" + snapshot.stage().replace(':', '-'));
                String text = canonical
                        ? snapshot.ir().printCanonical(new IrTarget.Module())
                        : snapshot.ir().printRaw();
                writeDump(spec, internalName, phase, text);
            }
        };
    }

    private static void writeDump(String target, String internalName, String phase, String text) {
        String lower = target.toLowerCase(Locale.ROOT);
        if ("stderr".equals(lower) || "true".equals(lower) || "all".equals(lower)
                || "canonical".equals(lower) || lower.startsWith("hir") || lower.startsWith("mir")) {
            System.err.println("=== " + phase + " : " + internalName + " ===");
            System.err.println(text);
            return;
        }
        Path path = Path.of(target).resolve(internalName + "." + phase + ".txt");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
        } catch (IOException e) {
            throw new BabbleException("Cannot dump IR to " + path, e);
        }
    }
}
