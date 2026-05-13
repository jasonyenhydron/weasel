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
