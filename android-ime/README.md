# Weasel Android IME Port (Bootstrap)

## 狀態
- 已新增 Android IME 專案骨架：`android-ime/`
- 目前可提供基本英文字母、空白、Enter、Backspace 輸入。
- 尚未接入 Rime 引擎與候選字 UI。
- 已可讀取 `D:\APP\rime-liur-lua-master` 作為方案來源，同步到 `assets/rime/`。

## 建置前置
1. 安裝 Android Studio (Ladybug 或更新版)
2. 安裝 Android SDK Platform 35 與 Build-Tools
3. 用 Android Studio 開啟 `android-ime/`
4. 先執行 Gradle Sync

## 產生 APK
1. `Build > Build Bundle(s) / APK(s) > Build APK(s)`
2. 輸出位置：`android-ime/app/build/outputs/apk/debug/app-debug.apk`

## 啟用輸入法
1. 手機安裝 `app-debug.apk`
2. 到 Android 設定 > 語言與輸入法 > 啟用 `Weasel IME`
3. 在任一輸入框切換到 `Weasel IME`

## 基礎可用版 Demo 指南
1. 建置 debug APK
- PowerShell 設定 SDK：
  - `$env:ANDROID_HOME='C:\Users\jason.yen\AppData\Local\Android\Sdk'`
  - `$env:ANDROID_SDK_ROOT='C:\Users\jason.yen\AppData\Local\Android\Sdk'`
- 執行：`.\gradlew.bat app:assembleDebug`

2. 手機安裝與啟用
- 安裝：`app/build/outputs/apk/debug/app-debug.apk`
- Android 設定 > 語言與輸入法 > 啟用 `小企鵝蝦米輸入法`
- 在任何輸入框切換到本輸入法

3. 現場展示最小驗證
- 英數輸入：輸入 `abc123`
- 編輯鍵：測試 `Space`、`Enter`、`Backspace`
- 組字與候選字：輸入一段碼後選字
- 中英切換：切換一次並確認輸出內容改變

4. 建議截圖點
- 系統「啟用輸入法」頁
- 聊天或記事本中的實際輸入畫面
- 候選字列顯示畫面

## 下一步（建議）
- 接入 Rime Android（JNI）以沿用 Weasel/Rime 配置。
- 導入 `rime_user` 方案與詞庫同步流程。
- 實作候選字列、組字區、符號頁與中英切換。

## 方案同步（rime-liur-lua-master）
1. 執行：
   `powershell -ExecutionPolicy Bypass -File D:\CODE\weasel\android-ime\scripts\sync-rime-liur.ps1`
2. 會把 `D:\APP\rime-liur-lua-master` 的方案檔同步到：
   `D:\CODE\weasel\android-ime\app\src\main\assets\rime`
3. 預設方案設定在：
   `assets/rime/android_ime_profile.json`（目前 `active_schema=liur`）

## 2026-05-13 修正紀錄
- 修正鍵位重疊體感問題：移除鍵高額外加成，改為穩定最小鍵高，避免面板與按鍵區擠壓。
- 美化「加字加詞」面板按鈕：統一圓角、描邊與主題色，改善按鈕視覺一致性。
- 修正「key in 搜尋」無法查詢加字加詞：字根欄位輸入時改為即時查詢，不用每次手動重按查詢。
- 修正「key in 搜尋」無法查詢剪貼簿：搜尋條件改為同時涵蓋已確認文字與當前組字候選，輸入中即可看到過濾結果。
