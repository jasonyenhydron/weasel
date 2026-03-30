#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""將 emoji.db 指定語系匯出成 Rime Lua 可讀的 TSV。"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path


VALID_LOCALES = {"tw", "cn", "en"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export emoji.db locale table to TSV for Rime Lua emoji query."
    )
    parser.add_argument("--db", required=True, help="Path to emoji.db")
    parser.add_argument("--out", required=True, help="Output TSV path")
    parser.add_argument(
        "--locale",
        default="tw",
        choices=sorted(VALID_LOCALES),
        help="Emoji locale table to export",
    )
    return parser.parse_args()


def escape_field(text: str) -> str:
    # 保留 TSV 結構，避免 tab 與換行破壞資料列。
    return (
        text.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    )


def load_rows(db_path: Path, locale: str) -> list[tuple[str, str]]:
    query = f'SELECT tag, value FROM "{locale}" WHERE tag IS NOT NULL AND value IS NOT NULL ORDER BY id ASC'
    conn = sqlite3.connect(db_path)
    try:
        cursor = conn.cursor()
        return [
            (str(tag).strip(), str(value).strip())
            for tag, value in cursor.execute(query).fetchall()
            if str(tag).strip() and str(value).strip()
        ]
    finally:
        conn.close()


def write_tsv(rows: list[tuple[str, str]], out_path: Path, locale: str) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="\n") as fp:
        fp.write(f"# LikeIME emoji export for Rime liur ({locale})\n")
        fp.write("# tag\tvalue\n")
        for tag, value in rows:
            fp.write(f"{escape_field(tag)}\t{escape_field(value)}\n")


def main() -> int:
    args = parse_args()
    db_path = Path(args.db).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()
    if not db_path.is_file():
        raise SystemExit(f"Database not found: {db_path}")
    rows = load_rows(db_path, args.locale)
    write_tsv(rows, out_path, args.locale)
    print(f"exported {len(rows)} emoji rows ({args.locale}) to {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
