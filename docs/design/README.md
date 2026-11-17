# Babble Improvement Proposals (BIPs)

Design documents and language proposals for Babble, numbered chronologically by creation date in the git history.

## Naming convention

Items within each proposal use stable identifiers prefixed by the BIP number:

- **`BIP###-Q#`** — open questions (e.g., `BIP007-Q1`). Resolving a question gives a corresponding decision (`BIP###-D#`).
- **`BIP###-D#`** — decision points (e.g., `BIP001-D1`, `BIP002-D3`). Where a decision settles an earlier question, both are linked (e.g., `BIP005-Q1` → `BIP005-D1`, or `BIP001-Q1` → `BIP003-D1`).
- **`BIP###-P#`** — implementation phases (e.g., `BIP001-P0`–`BIP001-P4`, `BIP008-P1`–`BIP008-P7`; sub-phases may carry a letter suffix such as `BIP001-P0a` or `BIP008-P6a`).

## Index

| BIP | Title | Date | Status | Document |
|---|---|---|---|---|
| **BIP001** | Compiling Babble to JVM bytecode | 2026-10-11 | Draft (§15 steps 1–11 implemented: `BIP001-P0`–`BIP001-P3`; `BIP001-P4` done as `BIP006-P5`; typed codegen remains) | [bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) |
| **BIP002** | The Babble object model | 2026-10-11 | Implemented (`BIP002-P1`–`BIP002-P6`) | [bip002_object-model.md](bip002_object-model.md) |
| **BIP003** | The type system | 2026-10-24 | Gradual rules replaced by BIP008; runtime checks (`BIP003-D9`) remain at casts and Java boundaries | [bip003_type-system.md](bip003_type-system.md) |
| **BIP004** | The interactive mode | 2026-11-10 | Implemented | [bip004_repl.md](bip004_repl.md) |
| **BIP005** | Modules | 2026-11-11 | Accepted & implemented (`BIP005-P1`–`BIP005-P5`) | [bip005_modules.md](bip005_modules.md) |
| **BIP006** | Module sources and ahead-of-time compilation | 2026-11-14 | Accepted & implemented (`BIP006-P1`–`BIP006-P6`) | [bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md) |
| **BIP007** | Function Effects and Algebraic Properties in Babble | 2026-11-14 | Draft (not implemented; `BIP007-Q1`–`BIP007-Q4` open, `BIP007-P1`–`BIP007-P3` planned) | [bip007_effects.md](bip007_effects.md) |
| **BIP008** | Removing dynamic typing | 2026-11-16 | `BIP008-P1`–`BIP008-P7` implemented | [bip008_static-typing.md](bip008_static-typing.md) |
