#!/usr/bin/env python3
"""Rewrite planned BIP feature references (BIP###-P#) to implemented (BIP###-I#).

Per docs/design/README.md, Babble Improvement Proposals use:
  - BIP###-P# for planned features / implementation phases
  - BIP###-I# for implemented features / implementation phases

When a planned feature is fully implemented, run this script to rewrite all
references across the repository (documentation, code comments, .ba/.batest
files) from BIP###-P# to BIP###-I#.

Examples:
  python3 src/tools/mark_bip_implemented.py BIP022-P2
  python3 src/tools/mark_bip_implemented.py BIP021-P4..P6
  python3 src/tools/mark_bip_implemented.py --dry-run --diff BIP014-P3..P5
  python3 src/tools/mark_bip_implemented.py --list-planned
"""

from __future__ import annotations

import argparse
import collections
import difflib
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

# Phase token: digits with optional single lowercase letter suffix (e.g. 0, 0a, 6b, 12).
PHASE_TOKEN_RE = re.compile(r"^(\d+)([a-z]?)$")

# CLI spec:
#   BIP021-P4
#   BIP008-P6a
#   BIP021-P4..P6 / BIP021-P4-P6 / BIP021-P4–P6
#   BIP021-P4..BIP021-P6 / BIP021-P4-BIP021-P6 / BIP021-P4–BIP021-P6
CLI_SINGLE_RE = re.compile(r"^(BIP\d{3,})-P(\d+[a-z]?)$", re.IGNORECASE)
CLI_RANGE_RE = re.compile(
    r"^(BIP\d{3,})-P(\d+[a-z]?)(?:\.\.|-|–|—)(?:(BIP\d{3,})-)?P(\d+[a-z]?)$",
    re.IGNORECASE,
)

# Head of a reference chain in text: optional balanced backticks around BIP###-[PI]<tok>.
HEAD_RE = re.compile(
    r"(?<![A-Za-z0-9_])(?:`(BIP\d{3,})-([PI])(\d+[a-z]?)`|(BIP\d{3,})-([PI])(\d+[a-z]?))(?![A-Za-z0-9_])"
)

# Continuation of a chain (matched at pos via Pattern.match, so no leading ^):
#   1) Range dash (–, —, -) followed by optional BIP###- and [PI]<tok>
#   2) List separator (,, /, &) followed by UNPREFIXED [PI]<tok>
CONT_RANGE_RE = re.compile(
    r"(\s*[–—-]\s*)(?:`(?:(BIP\d{3,})-)?([PI])(\d+[a-z]?)`|(?:(BIP\d{3,})-)?([PI])(\d+[a-z]?))(?![A-Za-z0-9_])"
)
CONT_LIST_RE = re.compile(
    r"(\s*[,/&]\s*)(?:`([PI])(\d+[a-z]?)`|([PI])(\d+[a-z]?))(?![A-Za-z0-9_])"
)

# Any direct BIP###-P# match for --list-planned.
PLANNED_ID_RE = re.compile(r"(?<![A-Za-z0-9_])(BIP\d{3,}-P\d+[a-z]?)(?![A-Za-z0-9_])")

IGNORED_DIRS = {".git", "target", "__pycache__", ".idea", ".vscode", "node_modules"}


@dataclass
class ChainItem:
    bip: str
    status: str  # "P" or "I"
    token: str   # e.g. "0", "6a"
    prefixed: bool
    backticked: bool
    sep_before: str  # "" for head, else raw separator before this item
    is_range: bool   # True if sep_before is a range dash linking previous item to this one


def parse_phase_token(tok: str) -> tuple[int, str]:
    m = PHASE_TOKEN_RE.match(tok)
    if not m:
        raise ValueError(f"Invalid phase token : {tok}")
    return int(m.group(1)), m.group(2)


def parse_cli_spec(spec: str) -> list[tuple[str, str]]:
    """Expand a CLI phase specification into [(BIP###, token), ...]."""
    m = CLI_SINGLE_RE.match(spec)
    if m:
        return [(m.group(1).upper(), m.group(2).lower())]

    m = CLI_RANGE_RE.match(spec)
    if m:
        bip = m.group(1).upper()
        end_bip = m.group(3).upper() if m.group(3) else bip
        if end_bip != bip:
            raise ValueError(f"Range endpoints must share the same BIP : {spec}")
        start_num, start_suf = parse_phase_token(m.group(2).lower())
        end_num, end_suf = parse_phase_token(m.group(4).lower())
        if not start_suf and not end_suf:
            if start_num > end_num:
                raise ValueError(f"Range start exceeds end : {spec}")
            return [(bip, str(n)) for n in range(start_num, end_num + 1)]
        if start_num == end_num and start_suf and end_suf and start_suf <= end_suf:
            return [
                (bip, f"{start_num}{chr(c)}")
                for c in range(ord(start_suf), ord(end_suf) + 1)
            ]
        raise ValueError(
            f"Cannot expand mixed sub-phase range '{spec}' : list sub-phases explicitly"
        )

    raise ValueError(
        f"Invalid phase specification '{spec}' "
        "(expected BIP###-P#, e.g. BIP021-P4 or BIP021-P4..P6)"
    )


def format_token(
    bip: str,
    status: str,
    token: str,
    prefixed: bool,
    backticked: bool,
) -> str:
    text = f"{bip}-{status}{token}" if prefixed else f"{status}{token}"
    return f"`{text}`" if backticked else text


def expand_plain_range(start_tok: str, end_tok: str) -> list[str] | None:
    s_num, s_suf = parse_phase_token(start_tok)
    e_num, e_suf = parse_phase_token(end_tok)
    if not s_suf and not e_suf and s_num <= e_num:
        return [str(n) for n in range(s_num, e_num + 1)]
    if s_num == e_num and s_suf and e_suf and s_suf <= e_suf:
        return [f"{s_num}{chr(c)}" for c in range(ord(s_suf), ord(e_suf) + 1)]
    return None


def rewrite_range(
    start: ChainItem,
    end: ChainItem,
    targets: set[tuple[str, str]],
    warnings: list[str],
    context: str,
    prefix_if_planned: bool = False,
) -> tuple[str, bool, bool]:
    """Rewrite a range `start–end`.

    Returns (rendered, any_changed, ends_with_planned).
    """
    bip = start.bip
    dash = end.sep_before

    # Only planned ranges can be promoted.
    if start.status != "P" or end.status != "P":
        raw = (
            format_token(bip, start.status, start.token, start.prefixed, start.backticked)
            + dash
            + format_token(bip, end.status, end.token, end.prefixed, end.backticked)
        )
        return raw, False, (end.status == "P")

    start_prefixed_if_planned = start.prefixed or prefix_if_planned

    expanded = expand_plain_range(start.token, end.token)
    if expanded is not None:
        statuses = ["I" if (bip, tok) in targets else "P" for tok in expanded]
        if all(s == "P" for s in statuses):
            raw = (
                format_token(bip, "P", start.token, start_prefixed_if_planned, start.backticked)
                + dash
                + format_token(bip, "P", end.token, end.prefixed, end.backticked)
            )
            return raw, (start_prefixed_if_planned != start.prefixed), True
        if all(s == "I" for s in statuses):
            out = (
                format_token(bip, "I", start.token, start.prefixed, start.backticked)
                + dash
                + format_token(bip, "I", end.token, end.prefixed, end.backticked)
            )
            return out, True, False

        # Partial range: split into contiguous runs of I and P.
        runs: list[tuple[str, str, str]] = []
        run_status = statuses[0]
        run_start = expanded[0]
        run_end = expanded[0]
        for tok, st in zip(expanded[1:], statuses[1:]):
            if st == run_status:
                run_end = tok
            else:
                runs.append((run_status, run_start, run_end))
                run_status = st
                run_start = tok
                run_end = tok
        runs.append((run_status, run_start, run_end))

        parts: list[str] = []
        for idx, (st, r_start, r_end) in enumerate(runs):
            if idx > 0:
                head_prefixed = True
            else:
                head_prefixed = start_prefixed_if_planned if st == "P" else start.prefixed
            if r_start == r_end:
                parts.append(format_token(bip, st, r_start, head_prefixed, start.backticked))
            else:
                parts.append(
                    format_token(bip, st, r_start, head_prefixed, start.backticked)
                    + dash
                    + format_token(bip, st, r_end, end.prefixed, end.backticked)
                )
        return ", ".join(parts), True, (runs[-1][0] == "P")

    # Mixed sub-phase range (e.g. BIP008-P5–BIP008-P6b or P0b–BIP001-P3).
    s_num, _ = parse_phase_token(start.token)
    e_num, _ = parse_phase_token(end.token)
    start_hit = (bip, start.token) in targets
    end_hit = (bip, end.token) in targets
    middle_nums = list(range(s_num + 1, e_num))
    # Consider an intermediate integer covered if either itself or any sub-phase of it is targeted.
    bip_targets = {tok for b, tok in targets if b == bip}
    middle_hits = [
        str(k) in bip_targets or any(t.startswith(f"{k}") and len(t) > len(str(k)) for t in bip_targets)
        for k in middle_nums
    ]
    if start_hit and end_hit and all(middle_hits):
        out = (
            format_token(bip, "I", start.token, start.prefixed, start.backticked)
            + dash
            + format_token(bip, "I", end.token, end.prefixed, end.backticked)
        )
        return out, True, False
    if not start_hit and not end_hit and not any(middle_hits):
        raw = (
            format_token(bip, "P", start.token, start_prefixed_if_planned, start.backticked)
            + dash
            + format_token(bip, "P", end.token, end.prefixed, end.backticked)
        )
        return raw, (start_prefixed_if_planned != start.prefixed), True

    warnings.append(
        f"{context}: partial match on sub-phase range "
        f"{bip}-P{start.token}{dash.strip()}P{end.token} — please update manually"
    )
    raw = (
        format_token(bip, "P", start.token, start_prefixed_if_planned, start.backticked)
        + dash
        + format_token(bip, "P", end.token, end.prefixed, end.backticked)
    )
    return raw, (start_prefixed_if_planned != start.prefixed), True


def parse_chain(text: str, start_pos: int) -> tuple[list[ChainItem], int] | None:
    m = HEAD_RE.match(text, start_pos)
    if not m:
        return None
    if m.group(1) is not None:
        bip, status, token, backticked = m.group(1), m.group(2), m.group(3), True
    else:
        bip, status, token, backticked = m.group(4), m.group(5), m.group(6), False

    items = [
        ChainItem(
            bip=bip,
            status=status,
            token=token,
            prefixed=True,
            backticked=backticked,
            sep_before="",
            is_range=False,
        )
    ]
    pos = m.end()

    while pos < len(text):
        rm = CONT_RANGE_RE.match(text, pos)
        if rm:
            sep = rm.group(1)
            if rm.group(3) is not None:
                c_bip, c_status, c_tok, c_bt = rm.group(2), rm.group(3), rm.group(4), True
            else:
                c_bip, c_status, c_tok, c_bt = rm.group(5), rm.group(6), rm.group(7), False
            if c_bip is not None and c_bip != bip:
                break
            items.append(
                ChainItem(
                    bip=bip,
                    status=c_status,
                    token=c_tok,
                    prefixed=(c_bip is not None),
                    backticked=c_bt,
                    sep_before=sep,
                    is_range=True,
                )
            )
            pos = rm.end()
            continue

        lm = CONT_LIST_RE.match(text, pos)
        if lm:
            sep = lm.group(1)
            if lm.group(2) is not None:
                c_status, c_tok, c_bt = lm.group(2), lm.group(3), True
            else:
                c_status, c_tok, c_bt = lm.group(4), lm.group(5), False
            items.append(
                ChainItem(
                    bip=bip,
                    status=c_status,
                    token=c_tok,
                    prefixed=False,
                    backticked=c_bt,
                    sep_before=sep,
                    is_range=False,
                )
            )
            pos = lm.end()
            continue

        break

    return items, pos


def rewrite_chain(
    items: list[ChainItem],
    targets: set[tuple[str, str]],
    warnings: list[str],
    context: str,
) -> tuple[str, bool]:
    """Rewrite a parsed BIP phase chain. Returns (new_text, changed)."""
    out_parts: list[str] = []
    any_changed = False
    need_prefix_for_next_planned = False
    i = 0
    while i < len(items):
        cur = items[i]
        # Pair cur with next if next is linked by a range dash.
        if i + 1 < len(items) and items[i + 1].is_range:
            nxt = items[i + 1]
            rendered, ch, ends_planned = rewrite_range(
                cur,
                nxt,
                targets,
                warnings,
                context,
                prefix_if_planned=need_prefix_for_next_planned,
            )
            if ch:
                any_changed = True
            need_prefix_for_next_planned = (not ends_planned) and any_changed
            out_parts.append(cur.sep_before + rendered)
            i += 2
            continue

        new_status = cur.status
        if cur.status == "P" and (cur.bip, cur.token) in targets:
            new_status = "I"
            any_changed = True

        prefixed = cur.prefixed
        if new_status == "P" and need_prefix_for_next_planned and not prefixed:
            prefixed = True
            need_prefix_for_next_planned = False
        elif new_status == "I" and cur.status == "P":
            need_prefix_for_next_planned = True

        out_parts.append(
            cur.sep_before
            + format_token(cur.bip, new_status, cur.token, prefixed, cur.backticked)
        )
        i += 1

    return "".join(out_parts), any_changed


def rewrite_text(
    text: str,
    targets: set[tuple[str, str]],
    warnings: list[str],
    file_label: str,
) -> tuple[str, int]:
    """Rewrite all targeted BIP###-P# chains in text. Returns (new_text, num_replacements)."""
    out: list[str] = []
    pos = 0
    replacements = 0
    for m in HEAD_RE.finditer(text):
        if m.start() < pos:
            continue
        parsed = parse_chain(text, m.start())
        if parsed is None:
            continue
        items, end_pos = parsed
        bip = items[0].bip
        # Fast path: skip chains whose BIP isn't in targets.
        if not any(t_bip == bip for t_bip, _ in targets):
            continue
        line_no = text.count("\n", 0, m.start()) + 1
        new_chunk, changed = rewrite_chain(
            items, targets, warnings, f"{file_label}:{line_no}"
        )
        if changed:
            out.append(text[pos : m.start()])
            out.append(new_chunk)
            pos = end_pos
            replacements += 1
    if replacements == 0:
        return text, 0
    out.append(text[pos:])
    return "".join(out), replacements


def find_repo_root(explicit_root: str | None) -> Path:
    if explicit_root:
        return Path(explicit_root).resolve()
    try:
        out = subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
        if out:
            return Path(out).resolve()
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    # Fallback: two parents above src/tools/mark_bip_implemented.py.
    return Path(__file__).resolve().parents[2]


def iter_repo_files(root: Path, skip_paths: set[Path]) -> list[Path]:
    """Return tracked text files in the repository (falling back to walk if not a git repo)."""
    files: list[Path] = []
    git_ok = False
    try:
        out = subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "-z"],
            stderr=subprocess.DEVNULL,
        )
        git_ok = True
        for entry in out.split(b"\0"):
            if not entry:
                continue
            p = (root / entry.decode("utf-8", errors="surrogateescape")).resolve()
            if p in skip_paths or not p.is_file():
                continue
            files.append(p)
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass

    if not git_ok:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(d for d in dirnames if d not in IGNORED_DIRS)
            for fn in sorted(filenames):
                p = (Path(dirpath) / fn).resolve()
                if p in skip_paths or not p.is_file():
                    continue
                files.append(p)

    return files


def read_text_file(path: Path) -> str | None:
    try:
        raw = path.read_bytes()
    except OSError:
        return None
    if b"\0" in raw:
        return None
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return None


def list_planned(root: Path, skip_paths: set[Path], verbose: bool) -> int:
    counts: collections.Counter[str] = collections.Counter()
    files_by_id: dict[str, set[str]] = collections.defaultdict(set)

    for path in iter_repo_files(root, skip_paths):
        text = read_text_file(path)
        if text is None:
            continue
        rel = str(path.relative_to(root))
        pos = 0
        for m in HEAD_RE.finditer(text):
            if m.start() < pos:
                continue
            parsed = parse_chain(text, m.start())
            if not parsed:
                continue
            items, end_pos = parsed
            pos = end_pos
            bip = items[0].bip
            i = 0
            while i < len(items):
                cur = items[i]
                if i + 1 < len(items) and items[i + 1].is_range:
                    nxt = items[i + 1]
                    if cur.status == "P" and nxt.status == "P":
                        expanded = expand_plain_range(cur.token, nxt.token)
                        if expanded is not None:
                            for tok in expanded:
                                pid = f"{bip}-P{tok}"
                                counts[pid] += 1
                                files_by_id[pid].add(rel)
                        else:
                            for tok in (cur.token, nxt.token):
                                pid = f"{bip}-P{tok}"
                                counts[pid] += 1
                                files_by_id[pid].add(rel)
                    i += 2
                    continue
                if cur.status == "P":
                    pid = f"{bip}-P{cur.token}"
                    counts[pid] += 1
                    files_by_id[pid].add(rel)
                i += 1

    if not counts:
        print("No planned BIP###-P# references found.")
        return 0

    for pid in sorted(counts):
        if verbose:
            flist = ", ".join(sorted(files_by_id[pid]))
            print(f"{pid:12s} ({counts[pid]:3d} refs) : {flist}")
        else:
            print(f"{pid:12s} ({counts[pid]:3d} refs across {len(files_by_id[pid]):2d} files)")
    return 0


def strip_chains(text: str) -> str:
    """Remove all BIP###-[PI]# reference chains from text."""
    out: list[str] = []
    pos = 0
    for m in HEAD_RE.finditer(text):
        if m.start() < pos:
            continue
        parsed = parse_chain(text, m.start())
        if not parsed:
            continue
        _, end_pos = parsed
        out.append(text[pos : m.start()])
        pos = end_pos
    out.append(text[pos:])
    return "".join(out)


def find_bare_phase_hints(
    root: Path,
    targets: set[tuple[str, str]],
    rewritten_texts: dict[Path, str],
) -> list[str]:
    """Check BIP own docs, docs/design/README.md, and AGENTS.md for bare P# mentions."""
    hints: list[str] = []
    by_bip: dict[str, set[str]] = collections.defaultdict(set)
    for bip, tok in targets:
        by_bip[bip].add(tok)

    candidates: list[tuple[Path, set[str]]] = []
    design_dir = root / "docs" / "design"
    for bip, toks in sorted(by_bip.items()):
        bip_lower = bip.lower()
        if design_dir.is_dir():
            for p in sorted(design_dir.glob(f"{bip_lower}_*.md")):
                candidates.append((p.resolve(), toks))
    all_toks = {tok for _, tok in targets}
    for extra in (design_dir / "README.md", root / "AGENTS.md"):
        if extra.is_file():
            candidates.append((extra.resolve(), all_toks))

    for path, toks in candidates:
        text = rewritten_texts.get(path)
        if text is None:
            text = read_text_file(path)
        if text is None:
            continue
        cleaned = strip_chains(text)
        cleaned = re.sub(r"RM\d{3,}-P\d+[a-z]?", "", cleaned)
        found = sorted(
            {
                f"P{m.group(1)}"
                for m in re.finditer(r"(?<![A-Za-z0-9_-])P(\d+[a-z]?)(?![A-Za-z0-9_])", cleaned)
                if m.group(1) in toks
            }
        )
        if found:
            rel = str(path.relative_to(root))
            hints.append(
                f"Note : {rel} still contains bare shorthand {', '.join(found)} "
                "(review manually if these refer to implemented phases)"
            )
    return hints


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Rewrite planned BIP feature references (BIP###-P#) to implemented "
            "(BIP###-I#) across the repository."
        ),
    )
    parser.add_argument(
        "phases",
        nargs="*",
        metavar="PHASE",
        help="Planned phase ID(s) or ranges (e.g. BIP022-P2, BIP021-P4..P6, BIP008-P6a)",
    )
    parser.add_argument(
        "-n",
        "--dry-run",
        action="store_true",
        help="Report changes without modifying any files",
    )
    parser.add_argument(
        "--diff",
        action="store_true",
        help="Print unified diffs of changes",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print extra details (per-file counts or file lists with --list-planned)",
    )
    parser.add_argument(
        "--root",
        help="Repository root directory (default: git root)",
    )
    parser.add_argument(
        "--list-planned",
        action="store_true",
        help="List all remaining BIP###-P# references across the repository and exit",
    )
    args = parser.parse_args(argv)

    root = find_repo_root(args.root)
    self_path = Path(__file__).resolve()
    skip_paths = {self_path, (self_path.parent / "test_mark_bip_implemented.py").resolve()}

    if args.list_planned:
        return list_planned(root, skip_paths, args.verbose)

    if not args.phases:
        parser.error("Provide at least one PHASE (e.g. BIP022-P2) or --list-planned")

    targets: set[tuple[str, str]] = set()
    for spec in args.phases:
        try:
            targets.update(parse_cli_spec(spec))
        except ValueError as e:
            print(f"Error : {e}", file=sys.stderr)
            return 2

    warnings: list[str] = []
    rewritten_texts: dict[Path, str] = {}
    changed_files = 0
    total_replacements = 0

    for path in iter_repo_files(root, skip_paths):
        text = read_text_file(path)
        if text is None:
            continue
        rel = str(path.relative_to(root))
        new_text, count = rewrite_text(text, targets, warnings, rel)
        if count == 0:
            continue
        rewritten_texts[path] = new_text
        changed_files += 1
        total_replacements += count
        if args.diff:
            diff = difflib.unified_diff(
                text.splitlines(keepends=True),
                new_text.splitlines(keepends=True),
                fromfile=f"a/{rel}",
                tofile=f"b/{rel}",
            )
            sys.stdout.writelines(diff)
        elif args.verbose or args.dry_run:
            print(f"{rel} ({count} replacement{'s' if count != 1 else ''})")

        if not args.dry_run:
            path.write_text(new_text, encoding="utf-8")

    for w in warnings:
        print(f"Warning : {w}", file=sys.stderr)

    action = "Would update" if args.dry_run else "Updated"
    print(
        f"{action} {changed_files} file{'s' if changed_files != 1 else ''} "
        f"({total_replacements} replacement{'s' if total_replacements != 1 else ''})."
    )

    if changed_files > 0:
        for hint in find_bare_phase_hints(root, targets, rewritten_texts):
            print(hint, file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
