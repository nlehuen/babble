#!/usr/bin/env python3
"""Render and score the triceratops scene against examples/raytracer/assets/triceratops.jpg.

Usage:
    python3 examples/raytracer/assets/score_triceratops.py
    python3 examples/raytracer/assets/score_triceratops.py --diff /tmp/diff.png
    python3 examples/raytracer/assets/score_triceratops.py --skip-render --json
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import time
from pathlib import Path
from PIL import Image, ImageDraw

TARGET_SIZE = (160, 160)
FG_WHITE_THRESHOLD = 30  # Manhattan distance from (255, 255, 255)


def is_foreground(r: int, g: int, b: int) -> bool:
    return (255 - r) + (255 - g) + (255 - b) > FG_WHITE_THRESHOLD


def perceptual_sq_dist(r1: int, g1: int, b1: int, r2: int, g2: int, b2: int) -> float:
    """Redmean weighted squared RGB distance normalized to [0, 255^2]."""
    rmean = 0.5 * (r1 + r2)
    dr = r1 - r2
    dg = g1 - g2
    db = b1 - b2
    return (
        (2.0 + rmean / 256.0) * dr * dr
        + 4.0 * dg * dg
        + (2.0 + (255.0 - rmean) / 256.0) * db * db
    ) / 9.0


def compute_ssim(ref_luma: list[float], ren_luma: list[float], w: int, h: int) -> float:
    """Windowed SSIM over 8x8 blocks with stride 4 on [0, 255] luminance."""
    c1 = (0.01 * 255.0) ** 2
    c2 = (0.03 * 255.0) ** 2
    win = 8
    stride = 4
    total = 0.0
    count = 0
    for y0 in range(0, h - win + 1, stride):
        for x0 in range(0, w - win + 1, stride):
            sx = sy = sxx = syy = sxy = 0.0
            for dy in range(win):
                row = (y0 + dy) * w + x0
                for dx in range(win):
                    vx = ref_luma[row + dx]
                    vy = ren_luma[row + dx]
                    sx += vx
                    sy += vy
                    sxx += vx * vx
                    syy += vy * vy
                    sxy += vx * vy
            n = float(win * win)
            mx = sx / n
            my = sy / n
            vx = max(0.0, sxx / n - mx * mx)
            vy = max(0.0, syy / n - my * my)
            cov = sxy / n - mx * my
            num = (2.0 * mx * my + c1) * (2.0 * cov + c2)
            den = (mx * mx + my * my + c1) * (vx + vy + c2)
            total += num / den
            count += 1
    return max(0.0, min(1.0, total / count))


def heat_color(err_0_to_255: float) -> tuple[int, int, int]:
    """Map amplified error in [0, 255] to a readable black->blue->cyan->yellow->red ramp."""
    t = max(0.0, min(1.0, (err_0_to_255 * 2.5) / 255.0))
    if t < 0.25:
        u = t / 0.25
        return (0, 0, int(255 * u))
    if t < 0.50:
        u = (t - 0.25) / 0.25
        return (0, int(255 * u), 255)
    if t < 0.75:
        u = (t - 0.50) / 0.25
        return (int(255 * u), 255, int(255 * (1.0 - u)))
    u = (t - 0.75) / 0.25
    return (255, int(255 * (1.0 - u)), 0)


def score_images(
    ref_path: Path, ren_path: Path, diff_path: Path | None = None
) -> dict[str, float | int | list[int]]:
    ref_raw = Image.open(ref_path).convert("RGB")
    ref_img = ref_raw.resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    ren_raw = Image.open(ren_path).convert("RGB")
    ren_img = (
        ren_raw
        if ren_raw.size == TARGET_SIZE
        else ren_raw.resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    )

    w, h = TARGET_SIZE
    get_data = getattr(ref_img, "get_flattened_data", ref_img.getdata)
    ref_px = list(get_data())
    get_data_ren = getattr(ren_img, "get_flattened_data", ren_img.getdata)
    ren_px = list(get_data_ren())
    n = w * h

    ref_luma = [0.0] * n
    ren_luma = [0.0] * n

    full_perc_sq = 0.0
    full_rgb_sq = 0.0
    fg_perc_sq = 0.0
    fg_union = 0
    fg_inter = 0
    fg_ref_only = 0
    fg_ren_only = 0

    diff_pixels: list[tuple[int, int, int]] | None = [] if diff_path else None
    sil_pixels: list[tuple[int, int, int]] | None = [] if diff_path else None

    for idx in range(n):
        r1, g1, b1 = ref_px[idx]
        r2, g2, b2 = ren_px[idx]

        ref_luma[idx] = 0.299 * r1 + 0.587 * g1 + 0.114 * b1
        ren_luma[idx] = 0.299 * r2 + 0.587 * g2 + 0.114 * b2

        psq = perceptual_sq_dist(r1, g1, b1, r2, g2, b2)
        full_perc_sq += psq
        full_rgb_sq += ((r1 - r2) ** 2 + (g1 - g2) ** 2 + (b1 - b2) ** 2) / 3.0

        f1 = is_foreground(r1, g1, b1)
        f2 = is_foreground(r2, g2, b2)
        if f1 or f2:
            fg_union += 1
            fg_perc_sq += psq
            if f1 and f2:
                fg_inter += 1
                if sil_pixels is not None:
                    sil_pixels.append((46, 204, 113))  # green = TP
            elif f1:
                fg_ref_only += 1
                if sil_pixels is not None:
                    sil_pixels.append((231, 76, 60))  # red = FN (missing)
            else:
                fg_ren_only += 1
                if sil_pixels is not None:
                    sil_pixels.append((52, 152, 219))  # blue = FP (extra)
        elif sil_pixels is not None:
            sil_pixels.append((240, 240, 240))  # light gray = TN

        if diff_pixels is not None:
            diff_pixels.append(heat_color(math.sqrt(psq)))

    full_rmse = math.sqrt(full_perc_sq / n)
    rgb_rmse = math.sqrt(full_rgb_sq / n)
    psnr = 99.99 if rgb_rmse == 0.0 else 20.0 * math.log10(255.0 / rgb_rmse)
    fg_rmse = math.sqrt(fg_perc_sq / fg_union) if fg_union > 0 else 0.0

    silhouette_iou = (100.0 * fg_inter / fg_union) if fg_union > 0 else 100.0
    fg_color_score = 100.0 * max(0.0, 1.0 - fg_rmse / 128.0)
    full_color_score = 100.0 * max(0.0, 1.0 - full_rmse / 128.0)
    ssim_score = 100.0 * compute_ssim(ref_luma, ren_luma, w, h)

    composite = (
        0.30 * silhouette_iou
        + 0.35 * fg_color_score
        + 0.20 * ssim_score
        + 0.15 * full_color_score
    )

    if diff_path is not None and diff_pixels is not None and sil_pixels is not None:
        diff_img = Image.new("RGB", (w, h))
        diff_img.putdata(diff_pixels)
        sil_img = Image.new("RGB", (w, h))
        sil_img.putdata(sil_pixels)

        scale = 2
        pw, ph = w * scale, h * scale
        header = 22
        strip = Image.new("RGB", (pw * 4, ph + header), (30, 30, 34))
        panels = [
            ("Reference (160x160)", ref_img),
            ("Rendered (160x160)", ren_img),
            ("Perceptual Error (2.5x)", diff_img),
            ("Silhouette (G=TP R=FN B=FP)", sil_img),
        ]
        draw = ImageDraw.Draw(strip)
        for i, (title, panel) in enumerate(panels):
            resized = panel.resize((pw, ph), Image.Resampling.NEAREST)
            strip.paste(resized, (i * pw, header))
            draw.text((i * pw + 6, 5), title, fill=(235, 235, 240))
        diff_path.parent.mkdir(parents=True, exist_ok=True)
        strip.save(diff_path)

    return {
        "width": w,
        "height": h,
        "composite_score": round(composite, 2),
        "silhouette_iou": round(silhouette_iou, 2),
        "fg_color_score": round(fg_color_score, 2),
        "ssim_score": round(ssim_score, 2),
        "full_color_score": round(full_color_score, 2),
        "fg_rmse": round(fg_rmse, 2),
        "full_rmse": round(full_rmse, 2),
        "psnr_db": round(psnr, 2),
        "fg_tp": fg_inter,
        "fg_fn": fg_ref_only,
        "fg_fp": fg_ren_only,
    }


def run_render(jar: Path, script: Path, output: Path, budget: float) -> tuple[float, str]:
    cmd = ["java", "-jar", str(jar), str(script), "triceratops", str(output)]
    start = time.monotonic()
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=budget + 10.0,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        elapsed = time.monotonic() - start
        raise TimeoutError(
            f"Render timed out after {elapsed:.2f}s (budget {budget:.1f}s)"
        ) from exc
    elapsed = time.monotonic() - start
    if proc.returncode != 0:
        raise RuntimeError(
            f"Render failed with exit code {proc.returncode}:\n{proc.stdout}\n{proc.stderr}"
        )
    return elapsed, proc.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Render the triceratops scene and score it against triceratops.jpg."
    )
    parser.add_argument(
        "--jar",
        type=Path,
        default=Path("target/babble-1.0-SNAPSHOT.jar"),
        help="Path to the compiled Babble jar",
    )
    parser.add_argument(
        "--script",
        type=Path,
        default=Path("examples/raytracer/raytracer_jvm.ba"),
        help="Path to raytracer_jvm.ba",
    )
    parser.add_argument(
        "--reference",
        type=Path,
        default=Path("examples/raytracer/assets/triceratops.jpg"),
        help="Path to the reference image",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("examples/raytracer/assets/triceratops.png"),
        help="Path where raytracer_jvm.ba writes the 160x160 PNG",
    )
    parser.add_argument(
        "--diff",
        type=Path,
        default=None,
        help="Optional path to save a 4-panel visual diagnostic strip",
    )
    parser.add_argument(
        "--budget",
        type=float,
        default=20.0,
        help="Maximum wall-clock render time budget in seconds",
    )
    parser.add_argument(
        "--skip-render",
        action="store_true",
        help="Score an existing output image without invoking Babble",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print results as JSON",
    )
    args = parser.parse_args()

    elapsed: float | None = None
    render_stdout = ""
    within_budget = True

    if not args.skip_render:
        if not args.jar.is_file():
            print(f"Error : Babble jar not found at {args.jar} (run 'mvn package -DskipTests')", file=sys.stderr)
            return 2
        try:
            elapsed, render_stdout = run_render(args.jar, args.script, args.output, args.budget)
        except Exception as exc:
            print(f"Error : {exc}", file=sys.stderr)
            return 1
        within_budget = elapsed <= args.budget

    if not args.output.is_file():
        print(f"Error : rendered image not found at {args.output}", file=sys.stderr)
        return 1

    try:
        metrics = score_images(args.reference, args.output, args.diff)
    except Exception as exc:
        print(f"Error : {exc}", file=sys.stderr)
        return 1

    result: dict[str, object] = dict(metrics)
    if elapsed is not None:
        result["elapsed_seconds"] = round(elapsed, 3)
        result["budget_seconds"] = args.budget
        result["within_budget"] = within_budget

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        if render_stdout:
            print(render_stdout)
        print("=== Triceratops Render Score ===")
        print(f"  Format          : {metrics['width']}x{metrics['height']}")
        if elapsed is not None:
            status = "PASS" if within_budget else "OVER BUDGET"
            print(f"  Elapsed time    : {elapsed:.2f}s / {args.budget:.1f}s ({status})")
        print(f"  Composite Score : {metrics['composite_score']:6.2f} / 100")
        print(f"    Silhouette IoU: {metrics['silhouette_iou']:6.2f} / 100  (TP={metrics['fg_tp']} FN={metrics['fg_fn']} FP={metrics['fg_fp']})")
        print(f"    FG Color      : {metrics['fg_color_score']:6.2f} / 100  (FG RMSE={metrics['fg_rmse']:.2f})")
        print(f"    Windowed SSIM : {metrics['ssim_score']:6.2f} / 100")
        print(f"    Full Color    : {metrics['full_color_score']:6.2f} / 100  (Full RMSE={metrics['full_rmse']:.2f}, PSNR={metrics['psnr_db']:.2f} dB)")
        if args.diff:
            print(f"  Diff strip      : {args.diff}")

    return 0 if within_budget else 1


if __name__ == "__main__":
    raise SystemExit(main())
