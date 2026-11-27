# Unified Babble Ray Tracer (`examples/raytracer`)

A single, layered ray tracer written maximally in **pure Babble** (math, RNG, BVH, materials, geometries, camera, and scenes) with only a **thin target-specific IO layer** bridging to the JVM or WebAssembly 3.0 (`WasmGC`).

Every scene renders **byte-for-byte identically** across `interpret`, compiled JVM (`-O0`, `-O1`, `-O2`, single-threaded or multi-threaded), ahead-of-time jars (`babblec -target jvm`), WebAssembly 3.0 on Node.js, and WebAssembly 3.0 in the browser (`<canvas>`).

## Rendered Scenes

| Weekend Cover (`cover`, 800×450) | Baby Triceratops (`triceratops`, 160×160) |
|---|---|
| ![Weekend Cover rendered in pure Babble](assets/scene.png) | ![Baby Triceratops rendered in pure Babble](assets/triceratops.png) |

## Architecture

```mermaid
flowchart LR
    Core["core/<br/>100% pure Babble (WASM_BARE)<br/>math, rng, vec3, ray, aabb,<br/>materials, geometry/, camera, scene"]
    Scenes["scenes/<br/>100% pure Babble (WASM_BARE)<br/>weekend_cover, three_spheres,<br/>triceratops, registry"]
    IO_PPM["io/sink.ba + io/ppm.ba<br/>Portable STDOUT PPM (JVM + Wasm)"]
    IO_JVM["io/jvm/png.ba<br/>JVM Thread pool + BufferedImage + PNG"]
    IO_WASM["io/wasm/canvas.ba<br/>axiom wasm.canvas.*"]

    Scenes --> Core
    IO_PPM --> Core
    IO_JVM --> Core
    IO_WASM --> Core
```

### Layers

1. **`core/` — Rendering engine, materials, and geometries (`WASM_BARE`-clean)**
   - **Numerics & RNG**: `math.ba` (Newton-Raphson `sqrt`, Taylor `sin`/`cos`/`tan`, `powInt`, clamping — zero `java.lang.Math`) and `rng.ba` (deterministic 32-bit LCG with per-scanline `Rng.forRow(seed, y)` so 1-thread and 16-thread renders match bit-for-bit).
   - **Math & Interfaces**: `vec3.ba` (`record Vec3` with `Vec3.srgb`), `ray.ba`, `interval.ba`, flat 6-double `aabb.ba`, `interfaces.ba` (`Describable`, `Scatter`, `Material`, `HitRecord`, `Hittable`, `emptyHittable`), `color.ba`, `background.ba` (`SkyBackground`, `SolidBackground`).
   - **Core Material Library (`materials.ba`)**: Immutable `record` materials implementing `Material` — `Lambertian`, `Metal`, `Dielectric`, `Emissive`, and `Toon`.
   - **Core Geometry Library (`geometry/`)**: `Sphere`, oriented `Ellipsoid`, analytic tapered `RoundCone` (+ `addQuadraticBezierTube`), Fourier-contoured 3D `SculptedLobe`, pure-Babble `HittableList` (over `list of Hittable`), and pure-Babble `BVHNode`.
   - **Rendering (`camera.ba`, `scene.ba`, `core.ba`)**: Pure thin-lens `Camera` with `BIP017` effect-polymorphic `renderRow` (`fn calling emit`), plus `Scene` record bundling world, camera, and seed.
2. **`scenes/` — Pure scene definitions (`WASM_BARE`-clean)**
   - `weekend_cover.ba` (*Ray Tracing in One Weekend* Ch. 14 22×22 marble cover scene)
   - `three_spheres.ba` (*Ray Tracing in One Weekend* Ch. 13 three-spheres scene)
   - `triceratops.ba` (Baby Triceratops sculpted from `SculptedLobe`, `Ellipsoid`, `RoundCone`, `Sphere`, `Toon`, and `Emissive` — now 100% WebAssembly-portable!)
   - `registry.ba` (`match`-based lookup by scene name)
3. **`io/` — Thin target-specific IO & execution adapters**
   - `io/sink.ba` & `io/ppm.ba`: Pure-Babble `PixelSink` interface, sequential scanline driver, and portable ASCII `P3` PPM writer (`STDOUT`).
   - `io/jvm/png.ba`: Thin JVM-only adapter (~60 lines) — the **only** module using `java.*`/`javax.*` (`Thread`, `AtomicInteger`, `BufferedImage`, `ImageIO`).
   - `io/wasm/canvas.ba`: Thin Wasm-only adapter (~20 lines) — the **only** module declaring `axiom wasm.canvas.*`.

## Running

Build Babble first from the repository root:

```bash
mvn package
```

### 1. Multi-threaded PNG rendering on the JVM (`raytracer_jvm.ba`)

```bash
# Weekend Cover (800x450, 20 spp, 16 threads)
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer/raytracer_jvm.ba cover examples/raytracer/assets/scene.png 800 20 16

# Baby Triceratops (160x160, 64 spp, 16 threads)
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer/raytracer_jvm.ba triceratops examples/raytracer/assets/triceratops.png

# Score the Triceratops render against assets/triceratops.jpg
python3 examples/raytracer/assets/score_triceratops.py
```

Pass `1` as the thread count to run single-threaded (also works under `-Dbabble.mode=interpret`).

### 2. Portable PPM output on JVM, AOT jar, or WebAssembly Node.js (`raytracer_ppm.ba`)

```bash
# Direct JVM execution (defaults to cover 400x225, 20 spp -> matches assets/scene.ppm byte-for-byte)
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer/raytracer_ppm.ba > scene.ppm

# Ahead-of-time standalone JVM jar
java -jar target/babble-1.0-SNAPSHOT.jar -compile examples/raytracer/raytracer_ppm.ba -o raytracer.jar
java -jar raytracer.jar > scene.ppm

# WebAssembly 3.0 (WasmGC) on Node.js — produces the exact same PPM byte-for-byte
java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js examples/raytracer/raytracer_ppm.ba -o raytracer.wasm
node raytracer.mjs > scene.ppm
node raytracer.mjs triceratops 160 16 > triceratops.ppm
```

### 3. Interactive WebAssembly 3.0 browser `<canvas>` (`raytracer_wasm.ba` + `wasm_canvas.html`)

```bash
java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js \
    examples/raytracer/raytracer_wasm.ba \
    -o examples/raytracer/raytracer_wasm.wasm
java -jar target/babble-1.0-SNAPSHOT.jar -dev-server -d examples/raytracer -p 8080
# Open http://127.0.0.1:8080/wasm_canvas.html and pick Cover, Three Spheres, or Baby Triceratops
```

### 4. Pure-Babble Self-Test Suite (`selftest.ba`)

```bash
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer/selftest.ba
```
