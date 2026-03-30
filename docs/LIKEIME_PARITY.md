# LikeIme 與目前 Weasel liur 差異清單

## 已完成

| 功能 | 狀態 | 說明 |
|---|---|---|
| `custom_user` / `custom` 自定詞載入 | 已完成 | 以 `lime.db` 為主資料源，透過同步腳本匯出到 `openxiami_CustomWord.dict.yaml`。 |
| 自定詞完整匹配 | 已完成 | `liu_custom_word_translator.lua` 會依輸入碼產生完整匹配候選。 |
| 自定詞補全候選 | 已完成 | 目前以 Trie 做前綴補全，4 碼以上啟用。 |
| related 候選重排 | 已完成 | `liu_related_filter.lua` 會讀 `likeime_related.tsv`，依上一個上屏詞提升候選排序。 |
| related 即時學習 | 已完成 | 上屏後會將 `(上一詞, 目前上屏詞)` 寫入 `likeime_related.tsv.user` 覆蓋檔。 |
| emoji 查詢 | 已完成 | `,,e` 可查 `emoji.db` 匯出的 `likeime_emoji_tw.tsv`。 |
| 一鍵同步與部署 | 已完成 | `sync_lime_to_rime.ps1` / `.bat` 會同步配置、匯出資料並重新部署。 |

## 可補

| 功能 | 狀態 | 說明 |
|---|---|---|
| `custom_user` 即時寫回 `lime.db` | 可補 | 目前仍以匯出詞典為主；若要在 Rime 內直接增刪改 `custom_user`，需要額外工具或外部腳本。 |
| emoji 多語系切換 | 可補 | 目前先匯出 `tw`；可再加 `,,ec` / `,,ee` 或 schema 選項切換 `cn`、`en`。 |
| `hanconvertv2.db` 對齊 | 可補 | 現在主要靠 OpenCC 與既有 Lua 邏輯，還沒直接比照 LikeIme 的 DB 查詢。 |
| related 匯出增量同步 | 可補 | 目前每次同步會重寫基礎 `likeime_related.tsv`，再疊加 `.user` 覆蓋檔；可再做更細的 merge 工具。 |
| 更完整的 emoji 排序 | 可補 | 目前是 tag contains + 匯出順序；若需要 LikeIme 更細緻的排序，可再加分詞或權重。 |

## 不建議補

| 功能 | 狀態 | 說明 |
|---|---|---|
| Lua 直接讀 `lime.db` / `emoji.db` | 不建議補 | 目前 Rime Lua 環境沒有穩定的 SQLite 模組，部署與維護成本高。 |
| 修改 Weasel C++ 直接查 SQLite | 不建議補 | 需要重編安裝版，未來升級小狼毫時維護成本最高。 |
| 把 LikeIme AskAI 全搬進 Weasel | 不建議補 | Weasel/Rime 的使用場景偏輸入法核心，AskAI 屬於另一套桌面工作流，耦合會很高。 |
| 完全複製 LikeIme Windows 常駐工具 UI | 不建議補 | Weasel 已有成熟 TSF/IME UI，重做另一套桌面常駐工具只會增加複雜度。 |
