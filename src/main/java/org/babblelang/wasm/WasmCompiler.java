package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.ir.hir.HirModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates WebAssembly 3.0 compilation from target-neutral {@link HirModule}s:
 * portability checking ({@link WasmPortabilityChecker}), closed-world linking
 * ({@link WasmProgramLinker}), structural verification ({@link WasmVerifier}),
 * WAT rendering ({@link WatPrinter}), binary encoding ({@link WasmWriter}),
 * and JS ES module glue emission ({@link WasmJsGlueGen}) ({@code BIP025-I1}).
 */
public final class WasmCompiler {
    public record Options(
            CompilationTarget target,
            boolean verify,
            boolean debugNames,
            boolean dumpWat,
            boolean wasmOpt
    ) {
        public static Options defaults(CompilationTarget target) {
            boolean verify = Boolean.getBoolean("babble.verify");
            boolean debugNames = Boolean.getBoolean("babble.wasmDebugNames");
            String dumpWasm = System.getProperty("babble.dumpWasm", "");
            boolean dumpWat = "wat".equalsIgnoreCase(dumpWasm) || "true".equalsIgnoreCase(dumpWasm);
            return new Options(target, verify, debugNames, dumpWat, false);
        }
    }

    public record CompileResult(
            WasmModule module,
            byte[] wasmBytes,
            String wat,
            String jsGlue,
            List<WasmPortabilityChecker.Diagnostic> portabilityErrors
    ) {
        public boolean ok() {
            return portabilityErrors.isEmpty();
        }
    }

    private WasmCompiler() {
    }

    /** Compiles linked HIR modules into a verified WebAssembly 3.0 binary (plus optional JS glue). */
    public static CompileResult compile(List<WasmProgramLinker.ModuleInput> modules, Options options, String wasmFileName) {
        if (options.target() == CompilationTarget.JVM) {
            throw new IllegalArgumentException("WasmCompiler requires a WebAssembly target, got : jvm");
        }
        List<HirModule> hirs = modules.stream().map(WasmProgramLinker.ModuleInput::hir).toList();
        List<WasmPortabilityChecker.Diagnostic> diags = WasmPortabilityChecker.check(hirs, options.target());
        if (!diags.isEmpty()) {
            return new CompileResult(null, null, null, null, diags);
        }

        WasmModule wasmMod = WasmProgramLinker.link(modules, options.target());
        if (options.verify()) {
            WasmVerifier.verify(wasmMod);
        }

        String wat = options.dumpWat() ? WatPrinter.print(wasmMod) : null;
        if (options.dumpWat() && wat != null) {
            System.err.println(wat);
        }

        byte[] bytes = WasmWriter.encode(wasmMod, options.debugNames());
        String jsGlue = options.target() == CompilationTarget.WASM_JS
                ? WasmJsGlueGen.generate(wasmFileName != null ? wasmFileName : "app.wasm")
                : null;
        return new CompileResult(wasmMod, bytes, wat, jsGlue, List.of());
    }

    /** Writes {@code outWasm} (and sibling {@code .mjs} on {@code wasm-js}), running optional {@code wasm-opt}. */
    public static void writeOutputs(CompileResult result, Path outWasm, Options options) throws IOException {
        if (outWasm.toAbsolutePath().getParent() != null) {
            Files.createDirectories(outWasm.toAbsolutePath().getParent());
        }
        Files.write(outWasm, result.wasmBytes());
        if (options.wasmOpt()) {
            runWasmOpt(outWasm);
        }
        if (result.jsGlue() != null) {
            String base = outWasm.getFileName().toString();
            String mjsName = (base.endsWith(".wasm") ? base.substring(0, base.length() - 5) : base) + ".mjs";
            Path outMjs = outWasm.toAbsolutePath().normalize().getParent().resolve(mjsName);
            Files.writeString(outMjs, result.jsGlue(), StandardCharsets.UTF_8);
        }
    }

    private static void runWasmOpt(Path wasmPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "wasm-opt", "-O3", "--enable-gc", "--enable-reference-types",
                "--enable-exception-handling", "--enable-tail-call", "--enable-multivalue",
                wasmPath.toString(), "-o", wasmPath.toString());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("wasm-opt failed (exit " + code + ") : " + out.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("wasm-opt interrupted", e);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("wasm-opt ")) {
                throw e;
            }
            throw new IOException("Cannot run wasm-opt : install Binaryen (wasm-opt) on PATH", e);
        }
    }
}
