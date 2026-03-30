#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""將 lime.db 的 related 表匯出成 Rime 可讀的 TSV。"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path


HEADER = """# LikeIME related export for Rime liur
# pword\tcword\tscore
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export lime.db related table to TSV for Rime liur."
    )
    parser.add_argument("--db", required=True, help="Path to lime.db")
    parser.add_argument("--out", required=True, help="Output TSV path")
    return parser.parse_args()


def escape_field(text: str) -> str:
    # 保留 TSV 結構，避免 tab 與換行破壞資料列。
    return (
        text.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    )


def load_rows(db_path: Path) -> list[tuple[str, str, int]]:
    query = """
    SELECT pword, cword, COALESCE(score, 0) AS score
    FROM related
    WHERE pword IS NOT NULL
      AND cword IS NOT NULL
      AND trim(pword) <> ''
      AND trim(cword) <> ''
    ORDER BY score DESC, pword ASC, cword ASC
    """
    conn = sqlite3.connect(db_path)
    try:
        cursor = conn.cursor()
        return [
            (str(pword).strip(), str(cword).strip(), int(score or 0))
            for pword, cword, score in cursor.execute(query).fetchall()
        ]
    finally:
        conn.close()


def write_tsv(rows: list[tuple[str, str, int]], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="\n") as fp:
        fp.write(HEADER)
        for pword, cword, score in rows:
            fp.write(
                f"{escape_field(pword)}\t{escape_field(cword)}\t{int(score)}\n"
            )


def main() -> int:
    args = parse_args()
    db_path = Path(args.db).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()
    if not db_path.is_file():
        raise SystemExit(f"Database not found: {db_path}")
    rows = load_rows(db_path)
    write_tsv(rows, out_path)
    print(f"exported {len(rows)} related rows to {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
