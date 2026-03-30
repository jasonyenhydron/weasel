# Lime 詞庫同步

目前建議流程：

1. 維護主資料源 `[lime.db](/D:/CODE/weasel/data/lime.db)`
2. 執行 `[sync_lime_to_rime.ps1](/D:/CODE/weasel/scripts/sync_lime_to_rime.ps1)`
3. 腳本會自動：
   同步工作區內的 `liur.schema.yaml`、`rime.lua` 與 Lua 模組到使用者資料夾
   備份 `openxiami_CustomWord.dict.yaml`
   匯出 `custom_user` 與 `custom`
   匯出 `related` 成 `likeime_related.tsv`
   匯出 `emoji.db` 成 `likeime_emoji_tw.tsv`
   呼叫 `WeaselDeployer.exe /deploy`

執行範例：

```powershell
powershell -ExecutionPolicy Bypass -File D:\CODE\weasel\scripts\sync_lime_to_rime.ps1
```

或直接雙擊：

[`sync_lime_to_rime.bat`](/D:/CODE/weasel/scripts/sync_lime_to_rime.bat)

可選參數：

```powershell
powershell -ExecutionPolicy Bypass -File D:\CODE\weasel\scripts\sync_lime_to_rime.ps1 `
  -DbPath D:\CODE\weasel\data\lime.db `
  -RimeUserDir "$env:APPDATA\Rime" `
  -WeaselRoot "C:\Program Files (x86)\Rime\weasel-0.17.4"
```

回復方式：

將 `openxiami_CustomWord.dict.yaml.bak_時間戳` 覆蓋回原檔後，再重新執行部署。
