# android-ime skill.md

## 目的
- 讓 `D:\CODE\weasel\android-ime` 可以快速完成「可在手機展示」的最小輸入法 demo。
- 優先確保可安裝、可啟用、可切換、可輸入、可看到候選字。

## 執行順序（最短路徑）
1. 同步並確認資源
- 需要更新方案時執行：`powershell -ExecutionPolicy Bypass -File scripts\sync-rime-liur.ps1`
- 檢查 `app/src/main/assets/rime/android_ime_profile.json` 的 `active_schema`。

2. 建置 APK
- 在 PowerShell 設定 SDK 後建置：
  - `$env:ANDROID_HOME='C:\Users\jason.yen\AppData\Local\Android\Sdk'`
  - `$env:ANDROID_SDK_ROOT='C:\Users\jason.yen\AppData\Local\Android\Sdk'`
  - `.\gradlew.bat app:assembleDebug`
- APK 路徑：`app/build/outputs/apk/debug/app-debug.apk`

3. 手機展示流程
- 安裝 `app-debug.apk`。
- Android 設定 > 語言與輸入法 > 啟用 `小企鵝蝦米輸入法`。
- 在任一輸入框切換到本輸入法。
- 驗證：英數輸入、空白/Enter/Backspace、組字與候選字、中英切換。

## 修改原則
- 以 `PenguinInputMethodService.kt` 現有流程為主，不新增高風險邏輯。
- 重要邏輯與變數保留中文註解。
- XML/文件使用 UTF-8，避免顯示亂碼。

## 常見問題
- 若出現 `SDK location not found`：先設定 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，再執行 Gradle。
- 若手機未顯示輸入法：確認系統已啟用該 IME，並在輸入框手動切換。

## 功能更新（2026-05-13）
### 本次修正範圍
1. 按鍵重疊與 UI
- 調整鍵高策略：移除額外強制加高，改為穩定最小鍵高，降低面板開啟時的擠壓重疊風險。
- 美化「加字加詞」面板按鈕：統一圓角、描邊、主題色與字體樣式。

2. 加字加詞搜尋（key in 即時）
- `字根` 欄位改為輸入即時查詢（包含刪字即時刷新），不需每次手動按「查詢」。

3. 剪貼簿搜尋（key in 即時）
- 搜尋條件改為：`已確認文字 + 當前組字候選`。
- 使用者在組字階段就能看到過濾結果，不必等 commit 後才更新。

### 主要檔案
- `app/src/main/java/com/weasel/androidime/PenguinInputMethodService.kt`
- `app/src/main/res/layout/keyboard_view.xml`
- `README.md`

### 回歸驗證清單（每次改版建議必跑）
1. 按鍵重疊檢查
- 開啟「加字加詞」面板，確認鍵盤列與面板無重疊、無文字互壓。

2. 加字加詞即時搜尋
- 在 `字根` 欄逐字輸入/刪除，確認查詢結果區會即時更新。

3. 剪貼簿即時搜尋
- 在剪貼簿搜尋面板輸入字根（含 composing 階段），確認結果即時過濾。
- 測試 Backspace：先刪組字，再刪已確認文字，結果需同步變化。

4. 基本輸入回歸
- 英數輸入、候選選字、空白、Enter、Backspace、中英切換都正常。

### 環境注意事項（Windows）
- `local.properties` 建議使用 ASCII 或 UTF-8 無 BOM。
- 若 `local.properties` 含 BOM，可能導致 Gradle 誤判 `sdk.dir` 並出現 `SDK location not found`。
