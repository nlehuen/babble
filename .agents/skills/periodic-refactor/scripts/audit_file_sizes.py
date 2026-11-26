#!/usr/bin/env python3
"""Audit physical LOC of production source files under src/main.

Counts physical lines (including comments and blank lines) with the same
line-counting semantics as Java's Files.lines(path).count(), classifies files
against Babble's soft (1,250) and hard (2,000) thresholds, optionally annotates
recent git churn, and can enforce the hard limit using GRANDFATHERED_CEILINGS
from ArchitectureBoundaryTestCase.java as the single source of truth.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path

SOFT_LIMIT = 1_250
NEAR_LIMIT = 1_100
HARD_LIMIT = 2_000
MAX_GRANDFATHERED_SLACK = 50
SOURCE_EXTENSIONS = {".java", ".g4", ".ba"}
BOUNDARY_TEST_REL = Path(
    "src/test/java/org/babblelang/tests/ArchitectureBoundaryTestCase.java"
)

CEILING_ENTRY_RE = re.compile(r'"(src/main/[^"]+)"\s*,\s*([0-9_]+)')


@dataclass
class FileMetric:
    path: str
    lines: int
    tier: str
    grandfathered_ceiling: int | None = None
    recent_commits: int = 0
    recent_added: int = 0
    recent_deleted: int = 0

    @property
    def recent_net(self) -> int:
        return self.recent_added - self.recent_deleted


def find_repo_root(start: Path) -> Path:
    cur = start.resolve()
    for candidate in (cur, *cur.parents):
        if (candidate / "pom.xml").is_file() and (candidate / "src/main").is_dir():
            return candidate
    raise SystemExit("Error : could not locate Babble repository root (missing pom.xml / src/main)")


def count_physical_lines(path: Path) -> int:
    # Matches Java Files.lines(path).count()
    with path.open("r", encoding="utf-8", errors="replace") as f:
        return sum(1 for _ in f)


def load_grandfathered_ceilings(repo_root: Path) -> dict[str, int]:
    test_path = repo_root / BOUNDARY_TEST_REL
    if not test_path.is_file():
        return {}
    text = test_path.read_text(encoding="utf-8")
    anchor = text.find("GRANDFATHERED_CEILINGS")
    if anchor < 0:
        return {}
    block_end = text.find(");", anchor)
    if block_end < 0:
        return {}
    block = text[anchor:block_end]
    ceilings: dict[str, int] = {}
    for match in CEILING_ENTRY_RE.finditer(block):
        rel_path = match.group(1)
        limit = int(match.group(2).replace("_", ""))
        ceilings[rel_path] = limit
    return ceilings


def collect_git_churn(repo_root: Path, since: str) -> dict[str, tuple[int, int, int]]:
    # Check if `since` is a valid git revision; otherwise treat as --since=<date>
    rev_check = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{since}^{{commit}}"],
        cwd=repo_root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if rev_check.returncode == 0:
        cmd = ["git", "log", f"{since}..HEAD", "--numstat", "--format=tformat:__COMMIT__", "--", "src/main"]
    else:
        cmd = ["git", "log", f"--since={since}", "--numstat", "--format=tformat:__COMMIT__", "--", "src/main"]

    proc = subprocess.run(
        cmd,
        cwd=repo_root,
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        print(f"Warning : git log failed for --since={since!r}: {proc.stderr.strip()}", file=sys.stderr)
        return {}

    commits_per_file: dict[str, int] = defaultdict(int)
    added_per_file: dict[str, int] = defaultdict(int)
    deleted_per_file: dict[str, int] = defaultdict(int)
    seen_in_commit: set[str] = set()

    for line in proc.stdout.splitlines():
        if line == "__COMMIT__":
            seen_in_commit.clear()
            continue
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        add_s, del_s, rel_path = parts
        if add_s == "-" or del_s == "-":
            continue
        if rel_path not in seen_in_commit:
            commits_per_file[rel_path] += 1
            seen_in_commit.add(rel_path)
        added_per_file[rel_path] += int(add_s)
        deleted_per_file[rel_path] += int(del_s)

    return {
        path: (commits_per_file[path], added_per_file[path], deleted_per_file[path])
        for path in commits_per_file
    }


def classify(lines: int) -> str:
    if lines > HARD_LIMIT:
        return "hard"
    if lines > SOFT_LIMIT:
        return "soft"
    if lines >= NEAR_LIMIT:
        return "near"
    return "ok"


def scan_sources(repo_root: Path, since: str | None) -> list[FileMetric]:
    ceilings = load_grandfathered_ceilings(repo_root)
    churn = collect_git_churn(repo_root, since) if since else {}
    src_main = repo_root / "src/main"
    metrics: list[FileMetric] = []
    for path in sorted(src_main.rglob("*")):
        if not path.is_file() or path.suffix not in SOURCE_EXTENSIONS:
            continue
        rel = path.relative_to(repo_root).as_posix()
        lines = count_physical_lines(path)
        c, a, d = churn.get(rel, (0, 0, 0))
        metrics.append(
            FileMetric(
                path=rel,
                lines=lines,
                tier=classify(lines),
                grandfathered_ceiling=ceilings.get(rel),
                recent_commits=c,
                recent_added=a,
                recent_deleted=d,
            )
        )
    metrics.sort(key=lambda m: (-m.lines, m.path))
    return metrics


def package_key(rel_path: str) -> str:
    p = Path(rel_path)
    parts = p.parts
    if len(parts) >= 4 and parts[:3] == ("src", "main", "java"):
        return ".".join(parts[3:-1]) or "(default)"
    return "/".join(parts[:-1])


def check_hard_limit(repo_root: Path, metrics: list[FileMetric]) -> list[str]:
    ceilings = load_grandfathered_ceilings(repo_root)
    by_path = {m.path: m for m in metrics}
    violations: list[str] = []

    for m in metrics:
        cap = ceilings.get(m.path, HARD_LIMIT)
        if m.lines > cap:
            kind = "grandfathered ceiling" if m.path in ceilings else "hard limit"
            violations.append(f"{m.path} has {m.lines} LOC ({kind} is {cap})")

    for rel_path, cap in sorted(ceilings.items()):
        m = by_path.get(rel_path)
        if m is None:
            violations.append(f"Stale GRANDFATHERED_CEILINGS entry (missing file) : {rel_path}")
        elif m.lines <= HARD_LIMIT:
            violations.append(
                f"Stale GRANDFATHERED_CEILINGS entry ({m.lines} <= {HARD_LIMIT} LOC, remove exemption) : {rel_path}"
            )
        elif cap <= HARD_LIMIT:
            violations.append(
                f"Invalid GRANDFATHERED_CEILINGS entry (ceiling {cap} <= {HARD_LIMIT}) : {rel_path}"
            )
        elif cap - m.lines > MAX_GRANDFATHERED_SLACK:
            violations.append(
                f"Loose GRANDFATHERED_CEILINGS entry ({m.lines} LOC vs ceiling {cap}, "
                f"slack > {MAX_GRANDFATHERED_SLACK}; tighten ceiling to {m.lines}) : {rel_path}"
            )

    return violations


def format_markdown(metrics: list[FileMetric], since: str | None) -> str:
    total_files = len(metrics)
    total_loc = sum(m.lines for m in metrics)
    hard = [m for m in metrics if m.tier == "hard"]
    soft = [m for m in metrics if m.tier == "soft"]
    near = [m for m in metrics if m.tier == "near"]

    out: list[str] = []
    out.append("# `src/main` File-Size Audit")
    out.append("")
    out.append(f"- **Total production source files**: {total_files}")
    out.append(f"- **Total physical LOC**: {total_loc:,}")
    out.append(f"- **Hard limit (> {HARD_LIMIT:,} LOC)**: {len(hard)}")
    out.append(f"- **Soft limit ({SOFT_LIMIT + 1:,}–{HARD_LIMIT:,} LOC)**: {len(soft)}")
    out.append(f"- **Near threshold ({NEAR_LIMIT:,}–{SOFT_LIMIT:,} LOC)**: {len(near)}")
    if since:
        out.append(f"- **Churn window**: `{since}`")
    out.append("")

    def render_table(title: str, rows: list[FileMetric]) -> None:
        out.append(f"## {title} ({len(rows)})")
        out.append("")
        if not rows:
            out.append("_None._")
            out.append("")
            return
        if since:
            out.append("| Rank | File | LOC | Cap | Commits | Net Δ |")
            out.append("| ---: | :--- | ---: | ---: | ---: | ---: |")
        else:
            out.append("| Rank | File | LOC | Cap |")
            out.append("| ---: | :--- | ---: | ---: |")
        for idx, m in enumerate(rows, 1):
            cap_str = str(m.grandfathered_ceiling) if m.grandfathered_ceiling is not None else str(HARD_LIMIT)
            if since:
                net_str = f"{m.recent_net:+d}" if m.recent_commits else "0"
                out.append(f"| {idx} | `{m.path}` | {m.lines:,} | {cap_str} | {m.recent_commits} | {net_str} |")
            else:
                out.append(f"| {idx} | `{m.path}` | {m.lines:,} | {cap_str} |")
        out.append("")

    render_table(f"Hard Limit Violations / Grandfathered (> {HARD_LIMIT:,} LOC)", hard)
    render_table(f"Soft Limit Exceeded ({SOFT_LIMIT + 1:,}–{HARD_LIMIT:,} LOC)", soft)
    render_table(f"Near Threshold ({NEAR_LIMIT:,}–{SOFT_LIMIT:,} LOC)", near)

    pkg_totals: dict[str, tuple[int, int]] = defaultdict(lambda: (0, 0))
    for m in metrics:
        pkg = package_key(m.path)
        files_n, loc_n = pkg_totals[pkg]
        pkg_totals[pkg] = (files_n + 1, loc_n + m.lines)

    out.append("## Package LOC Rollup (Top 10)")
    out.append("")
    out.append("| Package | Files | Total LOC |")
    out.append("| :--- | ---: | ---: |")
    for pkg, (files_n, loc_n) in sorted(pkg_totals.items(), key=lambda kv: (-kv[1][1], kv[0]))[:10]:
        out.append(f"| `{pkg}` | {files_n} | {loc_n:,} |")
    out.append("")
    return "\n".join(out)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Audit physical LOC of src/main production source files.")
    parser.add_argument("--since", help="Git ref or date window for churn annotations (e.g. '14 days ago' or HEAD~20)")
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON instead of Markdown")
    parser.add_argument(
        "--check-hard-limit",
        action="store_true",
        help="Exit non-zero if any file violates HARD_LIMIT or GRANDFATHERED_CEILINGS in ArchitectureBoundaryTestCase",
    )
    args = parser.parse_args(argv)

    repo_root = find_repo_root(Path.cwd())
    metrics = scan_sources(repo_root, args.since)

    if args.check_hard_limit:
        violations = check_hard_limit(repo_root, metrics)
        if violations:
            print("Hard file-size limit check FAILED :", file=sys.stderr)
            for v in violations:
                print(f"  - {v}", file=sys.stderr)
            return 1
        total_loc = sum(m.lines for m in metrics)
        hard_count = sum(1 for m in metrics if m.tier == "hard")
        soft_count = sum(1 for m in metrics if m.tier == "soft")
        print(
            f"Hard file-size limit check PASSED ({len(metrics)} files, {total_loc:,} LOC; "
            f"{hard_count} grandfathered > {HARD_LIMIT}, {soft_count} over soft limit {SOFT_LIMIT})."
        )
        return 0

    if args.json:
        payload = {
            "total_files": len(metrics),
            "total_loc": sum(m.lines for m in metrics),
            "thresholds": {"near": NEAR_LIMIT, "soft": SOFT_LIMIT, "hard": HARD_LIMIT},
            "since": args.since,
            "files": [asdict(m) for m in metrics if m.tier != "ok"],
        }
        print(json.dumps(payload, indent=2))
    else:
        print(format_markdown(metrics, args.since))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
