#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""將 lime.db 的 custom_user/custom 匯出成 Rime 可讀的自定詞詞典。"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path


HEADER = """# Rime dictionary
# encoding: utf-8
#
# 自定詞字典（由 lua_translator@liu_custom_word_translator 載入）
# 來源：lime.db 的 custom_user、custom
# 說明：custom_user 優先於 custom，重複的「字詞 + 編碼」只保留第一筆
#
---
name: openxiami_CustomWord
version: "1"
sort: original
...
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export lime.db custom tables to openxiami_CustomWord.dict.yaml."
    )
    parser.add_argument("--db", required=True, help="Path to lime.db")
    parser.add_argument("--out", required=True, help="Output YAML dictionary path")
    return parser.parse_args()


def load_entries(db_path: Path) -> list[tuple[str, str, str, int]]:
    # 先讀 custom_user，再讀 custom，並依分數由高到低輸出，讓使用者詞優先。
    sql = """
    SELECT code, word, src, COALESCE(score, 0) AS score
    FROM (
      SELECT code, word, score, 0 AS src FROM custom_user
      UNION ALL
      SELECT code, word, score, 1 AS src FROM custom
    )
    WHERE code IS NOT NULL
      AND word IS NOT NULL
      AND trim(code) <> ''
      AND trim(word) <> ''
    ORDER BY src ASC, score DESC, code ASC, word ASC
    """

    conn = sqlite3.connect(db_path)
    try:
      cursor = conn.cursor()
      rows = cursor.execute(sql).fetchall()
    finally:
      conn.close()

    deduped: list[tuple[str, str, str, int]] = []
    seen: set[tuple[str, str]] = set()
    for code, word, src, score in rows:
        key = (str(code).strip().lower(), str(word).strip())
        if not key[0] or not key[1] or key in seen:
            continue
        seen.add(key)
        source_name = "custom_user" if int(src) == 0 else "custom"
        deduped.append((key[0], key[1], source_name, int(score or 0)))
    return deduped


def write_yaml(entries: list[tuple[str, str, str, int]], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="\n") as fp:
        fp.write(HEADER)
        for code, word, source_name, score in entries:
            fp.write(f"{word}\t{code}\t{source_name}\t{score}\n")


def main() -> int:
    args = parse_args()
    db_path = Path(args.db).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()

    if not db_path.is_file():
        raise SystemExit(f"Database not found: {db_path}")

    entries = load_entries(db_path)
    write_yaml(entries, out_path)
    print(f"exported {len(entries)} entries to {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
