#!/usr/bin/env python3
"""Unit tests for src/tools/mark_bip_implemented.py."""

from __future__ import annotations

import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import mark_bip_implemented as tool


class MarkBipImplementedTest(unittest.TestCase):
    def rewrite(self, text: str, *specs: str) -> tuple[str, list[str]]:
        targets: set[tuple[str, str]] = set()
        for spec in specs:
            targets.update(tool.parse_cli_spec(spec))
        warnings: list[str] = []
        result, _ = tool.rewrite_text(text, targets, warnings, "test.md")
        return result, warnings

    def test_parse_cli_spec(self) -> None:
        self.assertEqual(tool.parse_cli_spec("BIP999-P4"), [("BIP999", "4")])
        self.assertEqual(tool.parse_cli_spec("bip999-p6a"), [("BIP999", "6a")])
        self.assertEqual(
            tool.parse_cli_spec("BIP999-P1..P3"),
            [("BIP999", "1"), ("BIP999", "2"), ("BIP999", "3")],
        )
        self.assertEqual(
            tool.parse_cli_spec("BIP999-P1-BIP999-P2"),
            [("BIP999", "1"), ("BIP999", "2")],
        )
        self.assertEqual(
            tool.parse_cli_spec("BIP999-P6a..P6b"),
            [("BIP999", "6a"), ("BIP999", "6b")],
        )
        with self.assertRaises(ValueError):
            tool.parse_cli_spec("BIP999-P3..P1")
        with self.assertRaises(ValueError):
            tool.parse_cli_spec("BIP999-P1..BIP998-P2")
        with self.assertRaises(ValueError):
            tool.parse_cli_spec("BIP999-I1")

    def test_single_and_subphase_refs(self) -> None:
        got, w = self.rewrite("See BIP999-P4 and `BIP999-P5`.", "BIP999-P4")
        self.assertEqual(got, "See BIP999-I4 and `BIP999-P5`.")
        self.assertEqual(w, [])

        got, _ = self.rewrite("BIP999-P6 vs BIP999-P6a", "BIP999-P6")
        self.assertEqual(got, "BIP999-I6 vs BIP999-P6a")

        got, _ = self.rewrite("BIP999-P6 vs BIP999-P6a", "BIP999-P6a")
        self.assertEqual(got, "BIP999-P6 vs BIP999-I6a")

    def test_full_and_abbreviated_ranges(self) -> None:
        got, _ = self.rewrite("`BIP999-P0`–`BIP999-P3`", "BIP999-P0..P3")
        self.assertEqual(got, "`BIP999-I0`–`BIP999-I3`")

        got, _ = self.rewrite("`BIP999-P0`–`P2`", "BIP999-P0..P2")
        self.assertEqual(got, "`BIP999-I0`–`I2`")

        got, _ = self.rewrite("`BIP999-P0–P2`", "BIP999-P0..P2")
        self.assertEqual(got, "`BIP999-I0–I2`")

    def test_partial_range_splits(self) -> None:
        got, _ = self.rewrite("`BIP999-P0`–`P5`", "BIP999-P0..P2")
        self.assertEqual(got, "`BIP999-I0`–`I2`, `BIP999-P3`–`P5`")

        got, _ = self.rewrite("`BIP999-P4`–`P6`", "BIP999-P4")
        self.assertEqual(got, "`BIP999-I4`, `BIP999-P5`–`P6`")

        got, _ = self.rewrite("`BIP999-P4`–`P6`", "BIP999-P5")
        self.assertEqual(got, "`BIP999-P4`, `BIP999-I5`, `BIP999-P6`")

        got, _ = self.rewrite("`BIP999-P4`–`P6`", "BIP999-P6")
        self.assertEqual(got, "`BIP999-P4`–`P5`, `BIP999-I6`")

    def test_lists_slashes_and_incremental_promotion(self) -> None:
        got, _ = self.rewrite("BIP999-P4, P5, P6", "BIP999-P4..P6")
        self.assertEqual(got, "BIP999-I4, I5, I6")

        got, _ = self.rewrite("`BIP999-P1`/`P2`", "BIP999-P1..P2")
        self.assertEqual(got, "`BIP999-I1`/`I2`")

        got, _ = self.rewrite("`BIP999-P1`–`P4`, `P6`", "BIP999-P1..P4")
        self.assertEqual(got, "`BIP999-I1`–`I4`, `BIP999-P6`")

        # Incremental promotion preserves prefix for remaining planned items.
        s = "BIP999-P4, P5, P6"
        s, _ = self.rewrite(s, "BIP999-P4")
        self.assertEqual(s, "BIP999-I4, BIP999-P5, P6")
        s, _ = self.rewrite(s, "BIP999-P5")
        self.assertEqual(s, "BIP999-I4, BIP999-I5, BIP999-P6")
        s, _ = self.rewrite(s, "BIP999-P6")
        self.assertEqual(s, "BIP999-I4, BIP999-I5, BIP999-I6")

    def test_mixed_subphase_ranges_and_non_targets(self) -> None:
        got, _ = self.rewrite(
            "`BIP999-P0a`/`P0b`–`BIP999-P3`",
            "BIP999-P0a",
            "BIP999-P0b",
            "BIP999-P1..P3",
        )
        self.assertEqual(got, "`BIP999-I0a`/`I0b`–`BIP999-I3`")

        got, _ = self.rewrite("`BIP999-P3`/`D8`/§9", "BIP999-P3")
        self.assertEqual(got, "`BIP999-I3`/`D8`/§9")

        got, _ = self.rewrite(
            "Bip999P1TestCase BIP999-P1-based `BIP998-P1`", "BIP999-P1"
        )
        self.assertEqual(got, "Bip999P1TestCase BIP999-I1-based `BIP998-P1`")

    def test_cli_end_to_end(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            doc = root / "doc.md"
            doc.write_text("Phases `BIP999-P1`–`P3` and `BIP998-P1`.\n", encoding="utf-8")

            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = tool.main(["--root", str(root), "--dry-run", "BIP999-P1"])
            self.assertEqual(rc, 0)
            self.assertIn("Would update 1 file", buf.getvalue())
            self.assertEqual(
                doc.read_text(encoding="utf-8"),
                "Phases `BIP999-P1`–`P3` and `BIP998-P1`.\n",
            )

            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = tool.main(["--root", str(root), "BIP999-P1"])
            self.assertEqual(rc, 0)
            self.assertEqual(
                doc.read_text(encoding="utf-8"),
                "Phases `BIP999-I1`, `BIP999-P2`–`P3` and `BIP998-P1`.\n",
            )

            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = tool.main(["--root", str(root), "--list-planned"])
            self.assertEqual(rc, 0)
            out = buf.getvalue()
            self.assertNotIn("BIP999-P1", out)
            self.assertIn("BIP999-P2", out)
            self.assertIn("BIP999-P3", out)
            self.assertIn("BIP998-P1", out)


if __name__ == "__main__":
    unittest.main()
