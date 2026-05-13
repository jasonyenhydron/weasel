package com.penguinInput.androidime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.Uri
import android.text.SpannableStringBuilder
import android.view.HapticFeedbackConstants
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.animation.ValueAnimator
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class PenguinInputMethodService : InputMethodService() {
    private val logTag = "WeaselIME"
    private var activeSchema: String = "liur"

    private val composing = StringBuilder()
    private lateinit var composingView: TextView
    private lateinit var composingDivider: View
    private lateinit var topHintView: TextView
    private lateinit var candidateContainer: LinearLayout
    private lateinit var btnExpandCandidates: Button
    private lateinit var extraPanel: LinearLayout
    private lateinit var extraScroll: View
    private lateinit var toolbarRow: View
    private lateinit var rowLetters1: View
    private lateinit var rowLetters2: View
    private lateinit var rowLetters3: View
    private lateinit var rowLetters4: View
    private var keyboardRootView: View? = null

    // 數字鍵次要字符（長按輸入）
    private val numSecondary = mapOf(
        R.id.key_1 to "`", R.id.key_2 to "[", R.id.key_3 to "]",
        R.id.key_4 to "\\", R.id.key_5 to ";", R.id.key_6 to "'",
        R.id.key_7 to "/", R.id.key_8 to "-", R.id.key_9 to "(",
        R.id.key_0 to ")"
    )

    // 字母鍵次要特殊字元（長按輸入，仿 SwiftKey 佈局）
    private val letterSecondary = mapOf(
        // QWERTY 列
        R.id.key_q to "~",  R.id.key_w to "!",  R.id.key_e to "@",
        R.id.key_r to "#",  R.id.key_t to "$",  R.id.key_y to "%",
        R.id.key_u to "^",  R.id.key_i to "&",  R.id.key_o to "*",
        R.id.key_p to "+",
        // ASDF 列（對應 SwiftKey 圖示）
        R.id.key_a to "=",  R.id.key_s to "-",  R.id.key_d to "_",
        R.id.key_f to "/",  R.id.key_g to "|",  R.id.key_h to ":",
        R.id.key_j to "\"", R.id.key_k to "?",  R.id.key_l to "!",
        // ZXCV 列
        R.id.key_z to "(",  R.id.key_x to ")",  R.id.key_c to "[",
        R.id.key_v to "]",  R.id.key_b to "{",  R.id.key_n to "}",
        R.id.key_m to "<"
    )
    private var currentExtraPanel: String = "NONE" // NONE | CLIPBOARD | NUMPAD | SYMBOLS | EMOJI

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var voiceButtonRef: ImageButton? = null
    private var voicePulseAnimator: ValueAnimator? = null
    private val uiPrefs by lazy { getSharedPreferences("weasel_ui", Context.MODE_PRIVATE) }
    private val candidatePrefs by lazy { getSharedPreferences("weasel_candidate", Context.MODE_PRIVATE) }

    private val codeMap: MutableMap<String, MutableList<String>> = ConcurrentHashMap()
    private val codeSetMap: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    private val removedByCode: MutableMap<String, MutableSet<String>> = linkedMapOf()
    private val pinnedByCode: MutableMap<String, MutableSet<String>> = linkedMapOf()
    private val relatedMap: MutableMap<String, MutableList<String>> = linkedMapOf()
    private var lastCommittedText: String = ""
    private val relatedPrefs by lazy { getSharedPreferences("weasel_related", Context.MODE_PRIVATE) }

    // 使用頻率：code → (word → score)，每選一次 +1，分數高的排愈前
    private val freqByCode: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()
    private val freqPrefs by lazy { getSharedPreferences("weasel_freq", Context.MODE_PRIVATE) }

    private val candidateButtonPool = mutableListOf<Button>()

    // ── AI 面板狀態 ──────────────────────────────────────────────
    private val aiQuestionBuffer = StringBuilder()
    private var aiAttachedImagePath: String? = null
    private var isAiPanelActive = false
    private var aiResponseText: String = ""
    private var aiQuestionDisplayView: TextView? = null
    private var aiResponseDisplayView: TextView? = null
    private var aiImageStatusView: TextView? = null
    private var aiInsertButton: Button? = null
    private var aiSendButton: Button? = null
    private val aiPrefs by lazy { getSharedPreferences("weasel_ai", Context.MODE_PRIVATE) }

    // ── 加字加詞面板 ────────────────────────────────────────────
    private var isAddWordPanelActive = false
    private var isClipboardSearchActive = false
    private val clipboardSearchBuf = StringBuilder()
    private var clipboardSearchView: TextView? = null
    private var clipboardResultsContainer: LinearLayout? = null
    private var addWordFocus = "CODE"  // "CODE" | "WORD"
    private val addWordCodeBuf = StringBuilder()
    private val addWordWordBuf = StringBuilder()
    private var addWordCodeView: TextView? = null
    private var addWordWordView: TextView? = null
    private var addWordResultView: TextView? = null

    // 輸入語言模式：ZH_TW(繁體) | ZH_CN(簡體) | JA(日文) | EN(英文)
    private var inputLangMode = "ZH_TW"

    companion object {
        var onImagePickedCallback: ((String) -> Unit)? = null
        var onDbPickedCallback: ((String) -> Unit)? = null

        // ── 繁體→簡體對照（常用 120 對）──────────────────────────
        val TW_TO_CN = mapOf(
            "點" to "点","會" to "会","來" to "来","給" to "给",
            "後" to "后","還" to "还","對" to "对","說" to "说",
            "這" to "这","個" to "个","開" to "开","關" to "关",
            "問" to "问","國" to "国","語" to "语","體" to "体",
            "樣" to "样","當" to "当","時" to "时","發" to "发",
            "從" to "从","為" to "为","沒" to "没","門" to "门",
            "學" to "学","長" to "长","動" to "动","過" to "过",
            "現" to "现","應" to "应","讓" to "让","邊" to "边",
            "種" to "种","實" to "实","線" to "线","電" to "电",
            "話" to "话","請" to "请","認" to "认","將" to "将",
            "業" to "业","進" to "进","號" to "号","興" to "兴",
            "網" to "网","萬" to "万","標" to "标","題" to "题",
            "錢" to "钱","員" to "员","廣" to "广","圖" to "图",
            "頁" to "页","頭" to "头","臉" to "脸","腦" to "脑",
            "機" to "机","氣" to "气","書" to "书","數" to "数",
            "風" to "风","紙" to "纸","類" to "类","確" to "确",
            "總" to "总","聽" to "听","達" to "达","億" to "亿",
            "見" to "见","兒" to "儿","兩" to "两","東" to "东",
            "農" to "农","親" to "亲","傳" to "传","聯" to "联",
            "難" to "难","輸" to "输","選" to "选","勞" to "劳",
            "愛" to "爱","報" to "报","幫" to "帮","張" to "张",
            "護" to "护","廠" to "厂","雲" to "云","樂" to "乐",
            "聲" to "声","觀" to "观","鍵" to "键","響" to "响",
            "燈" to "灯","顯" to "显","館" to "馆","裡" to "里",
            "優" to "优","價" to "价","導" to "导","壞" to "坏",
            "寫" to "写","遠" to "远","讀" to "读","強" to "强",
            "歡" to "欢","調" to "调","義" to "义","記" to "记",
            "畫" to "画","壓" to "压","戰" to "战","隊" to "队",
            "銀" to "银","藥" to "药","歷" to "历","識" to "识",
            "離" to "离","歲" to "岁","費" to "费","謝" to "谢",
            "嗎" to "吗","嗯" to "嗯","準" to "准","雖" to "虽",
            "飛" to "飞","亞" to "亚","產" to "产","換" to "换",
            "負" to "负","輕" to "轻","臺" to "台","讚" to "赞",
            "幣" to "币","劃" to "划","鬧" to "闹","韓" to "韩",
        )

        // ── 羅馬字→平假名對照 ─────────────────────────────────
        val ROMAJI_KANA = mapOf(
            // 母音
            "a" to "あ","i" to "い","u" to "う","e" to "え","o" to "お",
            // か行
            "ka" to "か","ki" to "き","ku" to "く","ke" to "け","ko" to "こ",
            // さ行
            "sa" to "さ","si" to "し","shi" to "し","su" to "す","se" to "せ","so" to "そ",
            // た行
            "ta" to "た","ti" to "ち","chi" to "ち","tu" to "つ","tsu" to "つ","te" to "て","to" to "と",
            // な行
            "na" to "な","ni" to "に","nu" to "ぬ","ne" to "ね","no" to "の",
            // は行
            "ha" to "は","hi" to "ひ","hu" to "ふ","fu" to "ふ","he" to "へ","ho" to "ほ",
            // ま行
            "ma" to "ま","mi" to "み","mu" to "む","me" to "め","mo" to "も",
            // や行
            "ya" to "や","yu" to "ゆ","yo" to "よ",
            // ら行
            "ra" to "ら","ri" to "り","ru" to "る","re" to "れ","ro" to "ろ",
            // わ行
            "wa" to "わ","wo" to "を",
            // ん
            "nn" to "ん",
            // が行
            "ga" to "が","gi" to "ぎ","gu" to "ぐ","ge" to "げ","go" to "ご",
            // ざ行
            "za" to "ざ","zi" to "じ","ji" to "じ","zu" to "ず","ze" to "ぜ","zo" to "ぞ",
            // だ行
            "da" to "だ","de" to "で","do" to "ど",
            // ば行
            "ba" to "ば","bi" to "び","bu" to "ぶ","be" to "べ","bo" to "ぼ",
            // ぱ行
            "pa" to "ぱ","pi" to "ぴ","pu" to "ぷ","pe" to "ぺ","po" to "ぽ",
            // 拗音
            "kya" to "きゃ","kyu" to "きゅ","kyo" to "きょ",
            "sha" to "しゃ","shu" to "しゅ","sho" to "しょ",
            "cha" to "ちゃ","chu" to "ちゅ","cho" to "ちょ",
            "nya" to "にゃ","nyu" to "にゅ","nyo" to "にょ",
            "hya" to "ひゃ","hyu" to "ひゅ","hyo" to "ひょ",
            "mya" to "みゃ","myu" to "みゅ","myo" to "みょ",
            "rya" to "りゃ","ryu" to "りゅ","ryo" to "りょ",
            "gya" to "ぎゃ","gyu" to "ぎゅ","gyo" to "ぎょ",
            "ja" to "じゃ","ju" to "じゅ","jo" to "じょ",
            "bya" to "びゃ","byu" to "びゅ","byo" to "びょ",
            "pya" to "ぴゃ","pyu" to "ぴゅ","pyo" to "ぴょ",
        )
    }

    data class ClipboardItem(val text: String, val createdAt: Long, var isFavorite: Boolean)
    private val clipboardItems = mutableListOf<ClipboardItem>()
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardPrefs by lazy { getSharedPreferences("weasel_clipboard", Context.MODE_PRIVATE) }
    private var ignoreNextClipboardEvent = false
    private var currentLanguageMode = "XM" // XM | EN
    private var isEnglishUppercase = false
    private var isEnglishCapsLock = false
    private var lastShiftTapAtMs = 0L
    private val letterKeyIds = listOf(
        R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
        R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p,
        R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
        R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l,
        R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v, R.id.key_b,
        R.id.key_n, R.id.key_m
    )
    private val numberKeyIds = listOf(
        R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
        R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0
    )
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (ignoreNextClipboardEvent) {
            ignoreNextClipboardEvent = false
            return@OnPrimaryClipChangedListener
        }
        captureClipboardPrimary()
    }

    /** 主題模式：依系統深淺色切換 */
    private fun resolveThemeMode(): String {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    override fun onCreate() {
        super.onCreate()
        activeSchema = loadActiveSchemaFromAssets()
        loadCandidatePrefs()
        loadFreqPrefs()
        loadRelatedState()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Thread { loadClipboardFromDb() }.start()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        captureClipboardPrimary()

        Log.i(logTag, "Active schema: $activeSchema — loading dictionaries in background")
        Thread {
            loadDictionaries()
            loadRelatedFromTsv("rime/likeime_related.tsv")
            Handler(Looper.getMainLooper()).post {
                Log.i(logTag, "Dictionaries loaded: ${codeMap.size} codes")
                // 詞庫載入完成，不顯示提示
            }
        }.start()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val inputType = attribute?.inputType ?: 0
        val cls = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        currentLanguageMode = when {
            cls == android.text.InputType.TYPE_CLASS_NUMBER -> "EN"
            variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "EN"
            variation == android.text.InputType.TYPE_TEXT_VARIATION_URI -> "EN"
            else -> currentLanguageMode
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopVoiceListeningUi()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardRootView?.let {
            applyUiSettings(it)
            refreshEnglishKeyCaps(it)
            refreshCompositionUi()
        }
    }

    override fun onCreateInputView(): View {
        val keyboardView = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null)
        keyboardRootView = keyboardView
        composingView      = keyboardView.findViewById(R.id.composing_text)
        composingDivider   = keyboardView.findViewById(R.id.composing_divider)
        topHintView        = keyboardView.findViewById(R.id.top_hint_text)
        candidateContainer = keyboardView.findViewById(R.id.candidate_container)
        btnExpandCandidates= keyboardView.findViewById(R.id.btn_expand_candidates)
        candidateButtonPool.clear()
        extraPanel  = keyboardView.findViewById(R.id.extra_panel)
        extraScroll = keyboardView.findViewById(R.id.extra_scroll)
        toolbarRow  = keyboardView.findViewById(R.id.toolbar_row)
        rowLetters1 = keyboardView.findViewById(R.id.row_letters_1)
        rowLetters2 = keyboardView.findViewById(R.id.row_letters_2)
        rowLetters3 = keyboardView.findViewById(R.id.row_letters_3)
        rowLetters4 = keyboardView.findViewById(R.id.row_letters_4)
        // 套用快捷鍵列顯示偏好
        toolbarRow.visibility = if (uiPrefs.getBoolean("show_toolbar", true)) View.VISIBLE else View.GONE

        // 字母鍵
        for (id in letterKeyIds) {
            val key = keyboardView.findViewById<Button>(id)
            // 重要：按鍵顯示文字可能被改成「次要符號+主字母」，輸入時必須固定使用原始字根
            val baseLetter = key.text.toString().trim().lowercase()
            key.tag = baseLetter
            key.setOnClickListener {
                val input = (key.tag as? String) ?: key.text.toString()
                onLetterPressed(input)
            }
        }
        // 數字鍵：主要數字點擊，次要符號長按
        for (id in numberKeyIds) {
            val key = keyboardView.findViewById<Button>(id)
            val baseDigit = key.text.toString().trim()
            key.tag = baseDigit
            key.setOnClickListener {
                val input = (key.tag as? String) ?: key.text.toString()
                onNumberPressed(input)
            }
        }
        // 設定次要符號標籤與長按
        setupNumSecondaryKeys(keyboardView)
        setupLetterSecondaryKeys(keyboardView)

        // ，和。
        keyboardView.findViewById<Button>(R.id.key_comma).setOnClickListener { onLetterPressed(",") }
        keyboardView.findViewById<Button>(R.id.key_period).setOnClickListener { onLetterPressed(".") }

        // 功能鍵
        val spaceBtn = keyboardView.findViewById<Button>(R.id.key_space)
        spaceBtn.setOnClickListener { commitOrSpace() }
        spaceBtn.setOnLongClickListener { showSpaceMenu(); true }
        setupSpaceDragCursor(spaceBtn)

        keyboardView.findViewById<Button>(R.id.key_enter).setOnClickListener { commitOrEnter() }
        val backspaceBtn = keyboardView.findViewById<Button>(R.id.key_backspace)
        backspaceBtn.setOnClickListener { onBackspacePressed() }
        setupBackspaceAutoRepeat(backspaceBtn)
        keyboardView.findViewById<Button>(R.id.key_shift).setOnClickListener { toggleEnglishShift() }
        voiceButtonRef = keyboardView.findViewById(R.id.key_voice)
        voiceButtonRef?.setOnClickListener { startVoiceInput() }
        keyboardView.findViewById<View>(R.id.key_apps).setOnClickListener { toggleLanguageMode() }
        keyboardView.findViewById<View>(R.id.key_clipboard).setOnClickListener { toggleClipboardPanel() }
        keyboardView.findViewById<View>(R.id.key_numpad).setOnClickListener { toggleNumpadPanel() }
        keyboardView.findViewById<View>(R.id.key_settings).setOnClickListener { openImeSettings() }
        keyboardView.findViewById<View>(R.id.key_ai).setOnClickListener { openAiAssist() }
        keyboardView.findViewById<View>(R.id.key_ai).setOnLongClickListener { showAiPanel(); true }
        keyboardView.findViewById<View>(R.id.key_symbols).setOnClickListener { toggleEmojiPanel() }
        keyboardView.findViewById<Button>(R.id.key_symbols_toggle).setOnClickListener { toggleSymbolsPanel() }
        keyboardView.findViewById<Button>(R.id.key_globe).setOnClickListener { cycleInputLangMode() }
        keyboardView.findViewById<Button>(R.id.key_globe).setOnLongClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
            true
        }
        btnExpandCandidates.setOnClickListener { showExpandedCandidates() }

        applyUiSettings(keyboardView)
        refreshEnglishKeyCaps(keyboardView)
        refreshCompositionUi()
        return keyboardView
    }

    /** 為數字鍵設定次要符號（SpannableString 顯示 + 長按輸入）*/
    /** 字母鍵次要特殊字元：顯示小字於右上角，長按直接 commit */
    private fun setupLetterSecondaryKeys(root: View) {
        for ((id, sec) in letterSecondary) {
            val btn = root.findViewById<Button>(id) ?: continue
            val letter = ((btn.tag as? String) ?: btn.text.toString()).trim().lowercase()
            renderLetterKeyWithSecondary(btn, letter, sec)
            btn.setOnLongClickListener { commitSpecialChar(sec); true }
        }
    }

    /** 直接輸出特殊字元（不進組字緩衝，但 AI 面板模式下加入問題欄）*/
    private fun commitSpecialChar(char: String) {
        if (isAiPanelActive) {
            aiQuestionBuffer.append(char)
            updateAiQuestionDisplay()
            return
        }
        // 若有組字中，先 commit 再輸出特殊字
        if (composing.isNotEmpty()) {
            val code = composing.toString()
            val candidates = resolveCandidatesForCode(code)
            val raw = candidates.firstOrNull() ?: code
            val out = if (inputLangMode == "ZH_CN") convertToSimplified(raw) else raw
            currentInputConnection?.commitText(out, 1)
            recordCommit(out)
            composing.clear()
        }
        currentInputConnection?.commitText(char, 1)
        recordCommit(char)
        refreshCompositionUi()
    }

    private fun setupNumSecondaryKeys(root: View) {
        for ((id, sec) in numSecondary) {
            val btn = root.findViewById<Button>(id) ?: continue
            val digit = ((btn.tag as? String) ?: btn.text.toString()).trim().let {
                if (it.length > 1) it.last().toString() else it
            }
            renderNumberKeyWithSecondary(btn, digit, sec)
            btn.setOnLongClickListener { onLetterPressed(sec); true }
        }
    }

    /**
     * Backspace 長按連刪：
     * - 按住超過 1 秒才開始連續刪除（避免誤觸）
     * - 放開或取消觸控立即停止
     */
    private fun setupBackspaceAutoRepeat(backspaceBtn: Button) {
        val mainHandler = Handler(Looper.getMainLooper())
        val longPressDelayMs = 1000L
        val repeatIntervalMs = 70L
        var isRepeating = false

        val repeater = object : Runnable {
            override fun run() {
                if (!isRepeating) return
                onBackspacePressed()
                mainHandler.postDelayed(this, repeatIntervalMs)
            }
        }
        val startRepeat = Runnable {
            isRepeating = true
            repeater.run()
        }

        backspaceBtn.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isRepeating = false
                    mainHandler.postDelayed(startRepeat, longPressDelayMs)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isRepeating = false
                    mainHandler.removeCallbacks(startRepeat)
                    mainHandler.removeCallbacks(repeater)
                    false
                }
                else -> false
            }
        }
    }

    /** 長按空白鍵選單 */
    /**
     * 長按空白鍵後左右滑動可移動游標。
     * - 偵測到水平拖移 → 取消長按事件，開始游標模式
     * - 每滑動 STEP px 移動一格游標
     * - 若無滑動：click = commitOrSpace，long click = showSpaceMenu（維持原行為）
     */
    private fun setupSpaceDragCursor(spaceBtn: Button) {
        val density   = resources.displayMetrics.density
        val threshold = 6f * density   // 開始進入游標模式的最小移動距離 (px)
        val stepPx    = 18f * density  // 每移動 18dp 移一格游標
        var downX     = 0f
        var lastX     = 0f
        var dragging  = false

        spaceBtn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX    = event.x
                    lastX    = event.x
                    dragging = false
                    false  // 讓 click / long-click 正常觸發
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.x - downX
                    if (!dragging && Math.abs(totalDx) > threshold) {
                        dragging = true
                        v.cancelLongPress()   // 取消長按選單，進入游標模式
                        topHintView.text = if (totalDx < 0) "◀ 游標移動" else "游標移動 ▶"
                    }
                    if (dragging) {
                        val delta = event.x - lastX
                        val steps = (delta / stepPx).toInt()
                        if (steps != 0) {
                            val keyCode = if (steps > 0)
                                KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                            repeat(Math.abs(steps)) {
                                currentInputConnection?.sendKeyEvent(
                                    KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                                currentInputConnection?.sendKeyEvent(
                                    KeyEvent(KeyEvent.ACTION_UP, keyCode))
                            }
                            lastX += steps * stepPx
                        }
                        true   // 消耗事件（不觸發 click）
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        topHintView.text = ""
                        true   // 消耗事件（不觸發 click）
                    } else {
                        dragging = false
                        false  // 讓 click 正常觸發
                    }
                }
                else -> false
            }
        }
    }

    private fun showSpaceMenu() {
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "MENU"
        setMainKeyboardVisible(false)

        val theme  = resolveThemeMode()
        val bgColor= when (theme) { "light" -> 0xFFEDF1F7.toInt(); "matcha" -> 0xFF10231D.toInt(); else -> 0xFF1A1D24.toInt() }
        val divClr = when (theme) { "light" -> 0xFFD1D5DB.toInt(); "matcha" -> 0xFF1C3528.toInt(); else -> 0xFF262B35.toInt() }
        val txtClr = when (theme) { "light" -> 0xFF1F2937.toInt(); "matcha" -> 0xFFD1FAE5.toInt(); else -> 0xFFE2E8F0.toInt() }
        val accentR= when (theme) { "light" -> 0xFFEF4444.toInt(); "matcha" -> 0xFFEF4444.toInt(); else -> 0xFFEF4444.toInt() }

        extraPanel.setBackgroundColor(bgColor)

        val items = listOf(
            // ── 文字操作
            Triple("☑️  全選", txtClr) {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                hideExtraPanelAndShowMainKeyboard()
            },
            Triple("✂️  剪下", txtClr) {
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
                hideExtraPanelAndShowMainKeyboard()
            },
            Triple("📄  複製", txtClr) {
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
                hideExtraPanelAndShowMainKeyboard()
            },
            Triple("📋  貼上", txtClr) {
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
                hideExtraPanelAndShowMainKeyboard()
            },
            Triple("🔍  搜尋（剪貼簿→Google）", txtClr) {
                hideExtraPanelAndShowMainKeyboard()
                searchWithClipboard()
            },
            // ── 工具
            Triple("✏️  加字加詞", txtClr) {
                hideExtraPanelAndShowMainKeyboard()
                showAddWordPanel()
            },
            Triple("🎤  語音輸入", txtClr) {
                topHintView.text = "啟動語音輸入..."
                hideExtraPanelAndShowMainKeyboard()
                startVoiceInput()
            },
            Triple("📁  剪貼簿記錄", txtClr) {
                showClipboardPanel()
                topHintView.text = "已開啟剪貼簿記錄"
            },
            Triple("📥  匯入資料庫", txtClr) {
                hideExtraPanelAndShowMainKeyboard()
                launchDbImport()
            },
            Triple("📤  匯出資料庫", txtClr) {
                hideExtraPanelAndShowMainKeyboard()
                exportLimeDb()
            },
            Triple("⚙️  設定", txtClr) {
                topHintView.text = "開啟輸入法設定..."
                openImeSettings()
                hideExtraPanelAndShowMainKeyboard()
            },
            Triple("✕  關閉", accentR) {
                hideExtraPanelAndShowMainKeyboard()
            }
        )
        items.forEachIndexed { idx, (label, color, action) ->
            if (idx > 0) {
                // 分隔線
                val div = android.view.View(this).apply {
                    setBackgroundColor(divClr)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                }
                extraPanel.addView(div)
            }
            val btn = Button(this).apply {
                text = label
                textSize = 15f
                setTextColor(color)
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setPadding(24.dpToPx(), 0, 0, 0)
                setBackgroundColor(0x00000000)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52.dpToPx())
                setOnClickListener { action() }
            }
            extraPanel.addView(btn)
        }
    }

    /** 展開所有候選字（顯示在 extra panel）*/
    private fun showExpandedCandidates() {
        val code = composing.toString()
        val related = relatedMap[lastCommittedText].orEmpty()
        val activeList = if (code.isEmpty()) related else resolveCandidatesForCode(code)
        if (activeList.isEmpty()) {
            topHintView.text = "目前沒有可展開的候選字"
            return
        }
        val allCandidates = if (inputLangMode == "ZH_CN") activeList.map { convertToSimplified(it) } else activeList

        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "CANDIDATES"
        setMainKeyboardVisible(true)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // 多行流式佈局：每行放入，超出換新 LinearLayout
        val rows = mutableListOf<LinearLayout>()
        var currentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rows.add(currentRow)

        allCandidates.forEachIndexed { idx, word ->
            val btn = Button(this).apply {
                text = word; textSize = 14f
                setPadding(14, 0, 14, 0)
                setOnClickListener {
                    selectCandidateFromExpanded(code, word)
                }
            }
            currentRow.addView(btn)
            if ((idx + 1) % 7 == 0) {
                currentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rows.add(currentRow)
            }
        }

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { container.addView(it) }
        extraPanel.addView(container)
    }

    private fun selectCandidateFromExpanded(code: String, word: String) {
        currentInputConnection?.commitText(word, 1)
        recordCommit(word)
        incrementFreq(code, word)
        composing.clear()
        hideExtraPanelAndShowMainKeyboard()
        refreshCompositionUi()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun applyUiSettings(root: View) {
        val isLight  = resolveThemeMode() == "light"
        val keyScale = uiPrefs.getInt("key_scale", 100).coerceIn(75, 140) / 100f
        val textScale= uiPrefs.getInt("text_scale", 100).coerceIn(80, 140) / 100f

        // drawable-night/ 自動選深色 drawable；這裡只需依主題調整 textColor
        val keyTxt   = if (isLight) 0xFF1A2332.toInt() else 0xFFF2F4FA.toInt()
        val funcTxt  = if (isLight) 0xFF4A5568.toInt() else 0xFF94A3B8.toInt()
        val spaceTxt = if (isLight) 0xFF6B7280.toInt() else 0xFF8096B4.toInt()

        // 候選列 / 提示文字
        if (::composingView.isInitialized)
            composingView.setTextColor(if (isLight) 0xFF374151.toInt() else 0xFFDDE7FF.toInt())
        if (::topHintView.isInitialized)
            topHintView.setTextColor(if (isLight) 0xFF4B5563.toInt() else 0xFFDDE7FF.toInt())

        // 按鍵縮放（高度）
        scaleKeyHeights(root, keyScale, textScale)

        // 所有按鍵文字色（drawable-night 負責背景色，這裡補文字色）
        (letterKeyIds + numberKeyIds).forEach { id ->
            root.findViewById<Button>(id)?.setTextColor(keyTxt)
        }
        listOf(R.id.key_shift, R.id.key_symbols_toggle, R.id.key_globe,
               R.id.key_comma, R.id.key_period).forEach { id ->
            root.findViewById<Button>(id)?.setTextColor(funcTxt)
        }
        root.findViewById<Button>(R.id.key_space)?.setTextColor(spaceTxt)
        root.findViewById<Button>(R.id.key_backspace)?.setTextColor(0xFFE53E3E.toInt())
        root.findViewById<Button>(R.id.key_enter)?.setTextColor(0xFFF8FAFC.toInt())
    }

    private fun scaleKeyHeights(root: View, keyScale: Float, textScale: Float) {
        val allKeyIds = letterKeyIds + numberKeyIds + listOf(
            R.id.key_shift, R.id.key_backspace, R.id.key_symbols_toggle,
            R.id.key_globe, R.id.key_comma, R.id.key_period, R.id.key_space, R.id.key_enter
        )
        allKeyIds.forEach { id ->
            root.findViewById<Button>(id)?.apply {
                val lp = layoutParams
                if (lp != null && lp.height > 0)
                    lp.height = (lp.height * keyScale).toInt().coerceAtLeast(30)
                layoutParams = lp
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    textSize / resources.displayMetrics.scaledDensity * textScale)
            }
        }
    }

    private fun styleButtonsRecursive(view: View, bgRes: Int, textColor: Int, keyScale: Float, textScale: Float) {
        when (view) {
            is Button -> {
                val isKeyButton = (view.id in letterKeyIds) || (view.id in numberKeyIds)
                val isSpecialStyledButton = isKeyButton || view.id == R.id.key_space || view.id == R.id.key_enter ||
                    view.id == R.id.key_shift || view.id == R.id.key_backspace || view.id == R.id.key_symbols_toggle ||
                    view.id == R.id.key_globe || view.id == R.id.key_comma || view.id == R.id.key_period
                if (!isSpecialStyledButton) {
                    view.setBackgroundResource(bgRes)
                    view.setTextColor(textColor)
                }
                if (isKeyButton) {
                    // 兩行按鍵避免文字重疊：主字縮小一點，保留次要字顯示空間
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * textScale)
                    view.includeFontPadding = false
                    view.setLineSpacing(1f, 1.0f)
                    view.setPadding(view.paddingLeft, 3.dpToPx(), view.paddingRight, 2.dpToPx())
                    view.gravity = android.view.Gravity.CENTER
                } else {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * textScale)
                }
                val lp = view.layoutParams
                if (lp != null && lp.height > 0) {
                    val minHeight = if (isKeyButton) 54 else 30
                    lp.height = (lp.height * keyScale).toInt().coerceAtLeast(minHeight)
                    if (isKeyButton) {
                        // 防重疊保守版：鍵高再增加 4dp，避免不同字型渲染造成兩行擠壓
                        lp.height += 4.dpToPx()
                    }
                }
                view.layoutParams = lp
            }
            is android.widget.ImageButton -> {
                val lp = view.layoutParams
                if (lp != null) {
                    if (lp.height > 0) lp.height = (lp.height * keyScale).toInt().coerceAtLeast(26)
                    if (lp.width > 0) lp.width = (lp.width * keyScale).toInt().coerceAtLeast(26)
                }
                view.layoutParams = lp
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    styleButtonsRecursive(view.getChildAt(i), bgRes, textColor, keyScale, textScale)
                }
            }
        }
    }

    private fun haptic() {
        keyboardRootView?.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun onLetterPressed(letter: String) {
        haptic()
        if (currentLanguageMode == "EN") {
            val out = if (isEnglishUppercase) letter.uppercase() else letter.lowercase()
            currentInputConnection?.commitText(out, 1)
            recordCommit(out)
            // 一次性大寫：輸出 1 個大寫字後自動回到小寫；Caps Lock 則保持
            if (isEnglishUppercase && !isEnglishCapsLock) {
                isEnglishUppercase = false
                keyboardRootView?.let { refreshEnglishKeyCaps(it) }
            }
            return
        }
        if (isClipboardSearchActive) { clipboardSearchBuf.append(letter); refreshClipboardSearch(); return }
        if (isAddWordPanelActive) { appendToAddWord(letter); return }
        if (isAiPanelActive) {
            aiQuestionBuffer.append(letter)
            updateAiQuestionDisplay()
            return
        }
        composing.append(letter)
        refreshCompositionUi()
    }

    private fun onBackspacePressed() {
        haptic()
        if (isClipboardSearchActive) {
            if (clipboardSearchBuf.isNotEmpty()) { clipboardSearchBuf.deleteCharAt(clipboardSearchBuf.length - 1); refreshClipboardSearch() }
            return
        }
        if (isAddWordPanelActive) { deleteFromAddWord(); return }
        if (isAiPanelActive) {
            if (aiQuestionBuffer.isNotEmpty()) {
                aiQuestionBuffer.deleteCharAt(aiQuestionBuffer.length - 1)
                updateAiQuestionDisplay()
            }
            return
        }
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            refreshCompositionUi()
            return
        }
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun commitOrSpace() {
        if (isClipboardSearchActive) { clipboardSearchBuf.append(" "); refreshClipboardSearch(); return }
        if (isAddWordPanelActive) {
            if (addWordFocus == "WORD") { addWordWordBuf.append(" "); refreshAddWordDisplay() }
            return
        }
        if (isAiPanelActive) {
            aiQuestionBuffer.append(" ")
            updateAiQuestionDisplay()
            return
        }
        if (!commitCandidateOrRaw()) {
            currentInputConnection?.commitText(" ", 1)
            recordCommit(" ")
        }
    }

    private fun onNumberPressed(number: String) {
        haptic()
        if (currentLanguageMode == "EN") {
            currentInputConnection?.commitText(number, 1)
            recordCommit(number)
            return
        }
        if (isClipboardSearchActive) { clipboardSearchBuf.append(number); refreshClipboardSearch(); return }
        if (isAddWordPanelActive) { appendToAddWord(number); return }
        if (isAiPanelActive) {
            aiQuestionBuffer.append(number)
            updateAiQuestionDisplay()
            return
        }
        composing.append(number)
        refreshCompositionUi()
    }

    private fun commitOrEnter() {
        if (isAiPanelActive) { triggerAiSend(); return }
        if (composing.isNotEmpty()) {
            val raw = composing.toString()
            val output = when (inputLangMode) {
                "JA"    -> convertRomaji(raw)
                "ZH_CN" -> raw  // Enter 輸出字根（不轉換）
                else    -> raw
            }
            currentInputConnection?.commitText(output, 1)
            recordCommit(output)
            composing.clear()
            refreshCompositionUi()
            return
        }
        currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun commitCandidateOrRaw(): Boolean {
        if (composing.isEmpty()) return false
        val code = composing.toString()

        // 日文：直接輸出假名
        if (inputLangMode == "JA") {
            val kana = convertRomaji(code)
            currentInputConnection?.commitText(kana, 1)
            recordCommit(kana)
            composing.clear()
            refreshCompositionUi()
            return true
        }

        val candidates = resolveCandidatesForCode(code)
        val candidate = candidates.firstOrNull()
        val raw = candidate ?: code
        val output = if (inputLangMode == "ZH_CN") convertToSimplified(raw) else raw
        currentInputConnection?.commitText(output, 1)
        recordCommit(output)
        if (candidate != null) incrementFreq(code, candidate)
        composing.clear()
        refreshCompositionUi()
        return true
    }

    private fun refreshCompositionUi() {
        if (isAiPanelActive) return

        // 英文模式
        if (inputLangMode == "EN") {
            composingView.text = "EN"
            composingDivider.visibility = View.VISIBLE
            candidateButtonPool.forEach { it.visibility = View.GONE }
            btnExpandCandidates.visibility = View.GONE
            currentInputConnection?.finishComposingText()
            return
        }

        // 日文模式：羅馬字→假名即時顯示，無候選
        if (inputLangMode == "JA") {
            val romaji = composing.toString()
            val kana = convertRomaji(romaji)
            composingView.text = if (romaji.isEmpty()) "あ" else romaji
            composingDivider.visibility = if (romaji.isNotEmpty()) View.VISIBLE else View.GONE
            candidateButtonPool.forEach { it.visibility = View.GONE }
            btnExpandCandidates.visibility = View.GONE
            if (romaji.isEmpty()) currentInputConnection?.finishComposingText()
            else currentInputConnection?.setComposingText(kana, 1)
            return
        }

        val code = composing.toString()
        val candidates = resolveCandidatesForCode(code)
        val related = relatedMap[lastCommittedText].orEmpty()
        val activeList = if (code.isEmpty()) related else candidates

        // 簡體模式：候選顯示繁體，commit 時轉換（在 renderCandidates 顯示簡體）
        val displayList = if (inputLangMode == "ZH_CN")
            activeList.map { convertToSimplified(it) }
        else activeList

        composingView.text = code
        composingDivider.visibility = if (code.isNotEmpty()) View.VISIBLE else View.GONE

        renderCandidates(displayList)
        btnExpandCandidates.visibility = if (activeList.size > 8) View.VISIBLE else View.GONE

        val topCandidate = displayList.firstOrNull().orEmpty()
        if (code.isEmpty()) currentInputConnection?.finishComposingText()
        else currentInputConnection?.setComposingText(topCandidate.ifEmpty { code }, 1)
    }

    private fun renderCandidates(candidates: List<String>) {
        val maxCount = minOf(8, candidates.size)
        // 擴展 pool
        while (candidateButtonPool.size < maxCount) {
            val btn = Button(this).apply {
                textSize = 13f
                setPadding(10, 0, 10, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
            }
            candidateContainer.addView(btn)
            candidateButtonPool.add(btn)
        }
        for (i in candidateButtonPool.indices) {
            val btn = candidateButtonPool[i]
            if (i < maxCount) {
                val word = candidates[i]
                btn.text = word
                btn.visibility = View.VISIBLE
                btn.setOnClickListener {
                    val code = composing.toString()
                    // displayWord 已是 ZH_CN 模式下的簡體字（renderCandidates 傳入的就是 displayList）
                    currentInputConnection?.commitText(word, 1)
                    recordCommit(word)
                    // 頻率記錄用原始字根 + 反轉換找原詞（或直接記 displayWord）
                    incrementFreq(code, word)
                    composing.clear()
                    refreshCompositionUi()
                }
                btn.setOnLongClickListener {
                    handleCandidateLongPress(word)
                    true
                }
                btn.setOnTouchListener(object : View.OnTouchListener {
                    private var downX = 0f
                    override fun onTouch(v: View?, event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> downX = event.x
                            MotionEvent.ACTION_UP -> {
                                val delta = event.x - downX
                                if (delta < -120f) {
                                    removeCandidateForCurrentContext(word)
                                    return true
                                }
                            }
                        }
                        return false
                    }
                })
            } else {
                btn.visibility = View.GONE
            }
        }
    }

    private fun resolveCandidatesForCode(code: String): List<String> {
        if (code.isEmpty()) return emptyList()
        // 重要：每次輸入字根都即時查 lime.db，確保 custom_user/custom 最新詞能立刻出現在候選列
        val liveFromDb = queryCandidatesFromLimeDb(code)
        liveFromDb.forEach { addCandidate(code, it) }

        val base = codeMap[code].orEmpty()
        val removed = removedByCode[code].orEmpty()
        val pinned = pinnedByCode[code].orEmpty()
        val freq = freqByCode[code] ?: emptyMap()
        val filtered = base.filter { !removed.contains(it) }
        val pinnedList = filtered.filter { pinned.contains(it) }
        // 未置頂的候選：先依頻率分數降序，同分則維持詞庫原始順序（stable sort）
        val normalList = filtered
            .filter { !pinned.contains(it) }
            .sortedByDescending { freq[it] ?: 0 }
        return pinnedList + normalList
    }

    /**
     * 即時查詢 lime.db：
     * 1) 先查 custom_user（使用者詞優先）
     * 2) 再查 custom
     * 3) 同字去重，保留先出現順序
     */
    private fun queryCandidatesFromLimeDb(code: String): List<String> {
        val dbFile = ensureLimeDbReady() ?: return emptyList()
        if (!dbFile.exists()) return emptyList()

        val result = linkedSetOf<String>()
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            queryCodeWordsFromTable(db, "custom_user", code).forEach { result.add(it) }
            queryCodeWordsFromTable(db, "custom", code).forEach { result.add(it) }
        } catch (e: Exception) {
            Log.w(logTag, "queryCandidatesFromLimeDb failed: ${e.message}")
        } finally {
            db?.close()
        }
        return result.toList()
    }

    /** 依指定 code 從單一 table 取出 word（按分數高到低） */
    private fun queryCodeWordsFromTable(db: SQLiteDatabase, table: String, code: String): List<String> {
        val out = mutableListOf<String>()
        val sql = "SELECT word FROM $table WHERE code = ? AND word IS NOT NULL ORDER BY score DESC"
        db.rawQuery(sql, arrayOf(code)).use { cursor ->
            val idxWord = cursor.getColumnIndex("word")
            while (cursor.moveToNext()) {
                val word = cursor.getString(idxWord)?.trim().orEmpty()
                if (word.isNotEmpty()) out.add(word)
            }
        }
        return out
    }

    private fun loadDictionaries() {
        codeMap.clear()
        codeSetMap.clear()
        loadFromLimeDb()   // 唯一來源：custom_user（優先） + custom
    }

    private fun toggleLanguageMode() {
        currentLanguageMode = if (currentLanguageMode == "XM") "EN" else "XM"
        if (currentLanguageMode == "EN") {
            composing.clear()
            isEnglishUppercase = false
            isEnglishCapsLock = false
        }
        keyboardRootView?.let { refreshEnglishKeyCaps(it) }
        refreshCompositionUi()
        topHintView.text = if (currentLanguageMode == "EN") "English Mode" else "嘸蝦米模式"
    }

    private fun toggleEnglishShift() {
        if (currentLanguageMode != "EN") {
            topHintView.text = "僅英文模式可切換大寫"
            return
        }
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastShiftTapAtMs) <= 400L
        lastShiftTapAtMs = now

        if (isEnglishCapsLock) {
            // Caps Lock 狀態下單按即關閉
            isEnglishCapsLock = false
            isEnglishUppercase = false
            keyboardRootView?.let { refreshEnglishKeyCaps(it) }
            topHintView.text = "EN 小寫"
            return
        }

        if (isDoubleTap) {
            // 雙擊：進入連續大寫
            isEnglishCapsLock = true
            isEnglishUppercase = true
            keyboardRootView?.let { refreshEnglishKeyCaps(it) }
            topHintView.text = "EN 連續大寫"
            return
        }

        // 單擊：只對下一個英文字母生效
        isEnglishUppercase = true
        isEnglishCapsLock = false
        keyboardRootView?.let { refreshEnglishKeyCaps(it) }
        topHintView.text = "EN 大寫一次"
    }

    private fun refreshEnglishKeyCaps(root: View) {
        val isEn = currentLanguageMode == "EN"
        for (id in letterKeyIds) {
            val btn = root.findViewById<Button>(id)
            val base = ((btn.tag as? String) ?: btn.text.toString()).lowercase()
            val mainChar = if (isEn && isEnglishUppercase) base.uppercase() else base
            val secondary = letterSecondary[id]
            if (!secondary.isNullOrEmpty()) {
                renderLetterKeyWithSecondary(btn, mainChar, secondary)
            } else {
                btn.text = mainChar
            }
        }
        // 數字鍵也需要跟模式同步：中文模式顯示全型，英文模式顯示原型
        for ((id, secondary) in numSecondary) {
            val btn = root.findViewById<Button>(id)
            val digit = ((btn.tag as? String) ?: btn.text.toString()).trim().let {
                if (it.length > 1) it.last().toString() else it
            }
            renderNumberKeyWithSecondary(btn, digit, secondary)
        }
        val shiftBtn = root.findViewById<Button>(R.id.key_shift)
        shiftBtn.text = when {
            !isEn -> "⇧"
            isEnglishCapsLock -> "⇪"
            isEnglishUppercase -> "⇧"
            else -> "⇧"
        }
        shiftBtn.visibility = if (isEn) View.VISIBLE else View.GONE
        // Space 列顯示模式標籤
        root.findViewById<Button>(R.id.key_space)?.let { sp ->
            sp.text = if (isEn) "English" else "嘸蝦米"
        }
    }

    private fun renderLetterKeyWithSecondary(btn: Button, mainChar: String, secondaryRaw: String) {
        val span = SpannableStringBuilder()
        val secDisplay = toDisplaySecondarySymbol(secondaryRaw)
        span.append(secDisplay)
        span.setSpan(RelativeSizeSpan(0.52f), 0, secDisplay.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(0xFFBFC5D2.toInt()), 0, secDisplay.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.append("\n$mainChar")
        btn.text = span
    }

    private fun renderNumberKeyWithSecondary(btn: Button, digit: String, secondaryRaw: String) {
        val span = SpannableStringBuilder()
        val secDisplay = toDisplaySecondarySymbol(secondaryRaw)
        span.append(secDisplay)
        span.setSpan(RelativeSizeSpan(0.58f), 0, secDisplay.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(0xFFBFC5D2.toInt()), 0, secDisplay.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.append("\n$digit")
        btn.text = span
    }

    /** 中文模式顯示全型特殊字元；英文模式顯示原型特殊字元 */
    private fun toDisplaySecondarySymbol(raw: String): String {
        if (currentLanguageMode == "EN") return raw
        val fullMap = mapOf(
            '~' to '～', '!' to '！', '@' to '＠', '#' to '＃', '$' to '＄', '%' to '％',
            '^' to '＾', '&' to '＆', '*' to '＊', '+' to '＋', '=' to '＝', '-' to '－',
            '_' to '＿', '/' to '／', '|' to '｜', ':' to '：', '"' to '＂', '?' to '？',
            '(' to '（', ')' to '）', '[' to '［', ']' to '］', '{' to '｛', '}' to '｝',
            '<' to '＜', '>' to '＞', ';' to '；', '\'' to '＇', '\\' to '＼', '`' to '｀'
        )
        val out = StringBuilder(raw.length)
        raw.forEach { ch -> out.append(fullMap[ch] ?: ch) }
        return out.toString()
    }

    // ── lime.db 輔助 ─────────────────────────────────────────────

    /** 取得 filesDir 中可寫的 lime.db File，首次從 assets 複製，並執行一次性去重 */
    private fun ensureLimeDbReady(): File? {
        return try {
            val targetDir = File(filesDir, "rime")
            if (!targetDir.exists()) targetDir.mkdirs()
            val target = File(targetDir, "lime.db")
            if (!target.exists() || target.length() == 0L) {
                assets.open("rime/lime.db").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            // 一次性去重（custom 中刪除 custom_user 已有的詞）
            dedupLimeDbOnce(target)
            target
        } catch (e: Exception) {
            Log.w(logTag, "ensureLimeDbReady failed: ${e.message}")
            null
        }
    }

    /** 一次性：從 custom table 刪除 custom_user 中已有的重複詞條 */
    private fun dedupLimeDbOnce(dbFile: File) {
        val setupPrefs = getSharedPreferences("weasel_setup", Context.MODE_PRIVATE)
        if (setupPrefs.getBoolean("lime_dedup_done", false)) return
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.execSQL("""
                DELETE FROM custom
                WHERE EXISTS (
                    SELECT 1 FROM custom_user
                    WHERE custom_user.code = custom.code
                      AND custom_user.word = custom.word
                )
            """.trimIndent())
            setupPrefs.edit().putBoolean("lime_dedup_done", true).apply()
            Log.i(logTag, "lime.db dedup done")
        } catch (e: Exception) {
            Log.w(logTag, "dedupLimeDbOnce failed: ${e.message}")
        } finally {
            db?.close()
        }
    }

    /** 造詞：將 word/code 寫入 custom_user，score = 現有最高分 + 1 */
    fun saveWordToLimeDb(word: String, code: String) {
        if (word.isEmpty() || code.isEmpty()) return
        val dbFile = ensureLimeDbReady() ?: return
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            val maxScore = db.rawQuery(
                "SELECT COALESCE(MAX(score), 0) FROM custom_user", null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val cv = ContentValues().apply {
                put("code", code)
                put("word", word)
                put("score", maxScore + 1)
                put("basescore", maxScore + 1)
            }
            db.insertWithOnConflict("custom_user", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            Log.i(logTag, "saveWordToLimeDb: $word -> $code (score=${maxScore + 1})")
        } catch (e: Exception) {
            Log.w(logTag, "saveWordToLimeDb failed: ${e.message}")
        } finally {
            db?.close()
        }
        // 立即更新記憶體候選（不需重啟）
        addCandidate(code, word)
    }

    private fun loadFromLimeDb() {
        val dbFile = ensureLimeDbReady()
        if (dbFile == null || !dbFile.exists()) return
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            loadCodeWordTable(db, "custom_user")  // 使用者個人詞優先
            loadCodeWordTable(db, "custom")       // 基底詞庫（已去重的詞自動略過）
        } catch (e: Exception) {
            Log.w(logTag, "loadFromLimeDb failed: ${e.message}")
        } finally {
            db?.close()
        }
    }

    private fun loadCodeWordTable(db: SQLiteDatabase, table: String) {
        val sql = "SELECT code, word FROM $table WHERE code IS NOT NULL AND word IS NOT NULL ORDER BY score DESC"
        db.rawQuery(sql, null).use { cursor ->
            val idxCode = cursor.getColumnIndex("code")
            val idxWord = cursor.getColumnIndex("word")
            while (cursor.moveToNext()) {
                val code = cursor.getString(idxCode)?.trim().orEmpty()
                val word = cursor.getString(idxWord)?.trim().orEmpty()
                if (code.isEmpty() || word.isEmpty()) continue
                addCandidate(code, word)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────

    private fun addCandidate(code: String, word: String) {
        val set = codeSetMap.getOrPut(code) { ConcurrentHashMap.newKeySet() }
        if (set.add(word)) {
            codeMap.getOrPut(code) { mutableListOf() }.add(word)
        }
    }

    private fun handleCandidateLongPress(word: String) {
        val code = composing.toString()
        if (code.isEmpty()) {
            topHintView.text = "聯想詞：左滑可刪除"
            return
        }
        val pinned = pinnedByCode.getOrPut(code) { mutableSetOf() }
        if (!pinned.contains(word)) {
            pinned.add(word)
            topHintView.text = "已置頂：$word"
        } else {
            pinned.remove(word)
            topHintView.text = "取消置頂：$word"
        }
        appendUserWordIfMissing(word, code)
        saveCandidatePrefs()
        refreshCompositionUi()
    }

    private fun removeCandidateForCurrentContext(word: String) {
        val code = composing.toString()
        if (code.isEmpty()) {
            val rel = relatedMap[lastCommittedText]
            rel?.remove(word)
            saveRelatedState()
            topHintView.text = "已刪除聯想：$word"
            refreshCompositionUi()
            return
        }
        val removed = removedByCode.getOrPut(code) { mutableSetOf() }
        removed.add(word)
        pinnedByCode[code]?.remove(word)
        saveCandidatePrefs()
        topHintView.text = "已刪除候選：$word"
        refreshCompositionUi()
    }

    private fun appendUserWordIfMissing(word: String, code: String) {
        // 改寫入 lime.db custom_user（背景執行避免主執行緒 I/O）
        Thread { saveWordToLimeDb(word, code) }.start()
    }

    // ── 使用頻率持久化 ────────────────────────────────────────────

    private fun incrementFreq(code: String, word: String) {
        if (code.isEmpty() || word.isEmpty()) return
        val map = freqByCode.getOrPut(code) { mutableMapOf() }
        map[word] = (map[word] ?: 0) + 1
        saveFreqPrefs()
    }

    private fun loadFreqPrefs() {
        freqByCode.clear()
        val json = freqPrefs.getString("freq_map", "{}") ?: "{}"
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { code ->
                val inner = obj.optJSONObject(code) ?: return@forEach
                val map = mutableMapOf<String, Int>()
                inner.keys().forEach { word -> map[word] = inner.optInt(word, 0) }
                if (map.isNotEmpty()) freqByCode[code] = map
            }
        } catch (_: Exception) {}
    }

    private fun saveFreqPrefs() {
        val obj = JSONObject()
        freqByCode.forEach { (code, map) ->
            val inner = JSONObject()
            // 只保留分數 > 0 的詞，並限制每個 code 最多記錄 100 筆
            map.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .take(100)
                .forEach { (word, score) -> inner.put(word, score) }
            if (inner.length() > 0) obj.put(code, inner)
        }
        freqPrefs.edit().putString("freq_map", obj.toString()).apply()
    }

    // ─────────────────────────────────────────────────────────────

    private fun loadCandidatePrefs() {
        removedByCode.clear()
        pinnedByCode.clear()
        val removedJson = candidatePrefs.getString("removed_by_code", "{}") ?: "{}"
        val pinnedJson = candidatePrefs.getString("pinned_by_code", "{}") ?: "{}"
        loadMapSetFromJson(removedJson, removedByCode)
        loadMapSetFromJson(pinnedJson, pinnedByCode)
    }

    private fun saveCandidatePrefs() {
        candidatePrefs.edit()
            .putString("removed_by_code", toJsonMapSet(removedByCode))
            .putString("pinned_by_code", toJsonMapSet(pinnedByCode))
            .apply()
    }

    private fun loadMapSetFromJson(json: String, target: MutableMap<String, MutableSet<String>>) {
        val obj = JSONObject(json)
        obj.keys().forEach { key ->
            val arr = obj.optJSONArray(key) ?: JSONArray()
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotEmpty()) set.add(v)
            }
            if (set.isNotEmpty()) target[key] = set
        }
    }

    private fun toJsonMapSet(map: Map<String, Set<String>>): String {
        val obj = JSONObject()
        map.forEach { (k, set) ->
            val arr = JSONArray()
            set.forEach { arr.put(it) }
            obj.put(k, arr)
        }
        return obj.toString()
    }

    private fun recordCommit(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (lastCommittedText.isNotEmpty() && lastCommittedText != clean) {
            val list = relatedMap.getOrPut(lastCommittedText) { mutableListOf() }
            list.remove(clean)
            list.add(0, clean)
            while (list.size > 12) list.removeAt(list.lastIndex)
            saveRelatedState()
        }
        lastCommittedText = clean
    }

    private fun loadRelatedFromTsv(assetPath: String) {
        val text = readAssetText(assetPath) ?: return
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = raw.split('\t')
            if (parts.size < 2) return@forEach
            val prev = parts[0].trim()
            val next = parts[1].trim()
            if (prev.length !in 1..12 || next.length !in 1..12) return@forEach
            if (prev.contains("\\n") || next.contains("\\n")) return@forEach
            val list = relatedMap.getOrPut(prev) { mutableListOf() }
            if (!list.contains(next)) {
                list.add(next)
                if (list.size > 12) list.removeAt(list.lastIndex)
            }
        }
    }

    private fun loadRelatedState() {
        relatedMap.clear()
        val json = relatedPrefs.getString("map", "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.keys().forEach { key ->
            val arr = obj.optJSONArray(key) ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val t = arr.optString(i)
                if (t.isNotEmpty()) list.add(t)
            }
            if (list.isNotEmpty()) relatedMap[key] = list
        }
    }

    private fun saveRelatedState() {
        val obj = JSONObject()
        relatedMap.forEach { (k, v) ->
            val arr = JSONArray()
            v.take(12).forEach { arr.put(it) }
            obj.put(k, arr)
        }
        relatedPrefs.edit().putString("map", obj.toString()).apply()
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // IME 可能在使用者未曾開啟主 App 的情況下直接使用，這裡主動導去權限頁流程
            showVoiceStatus("🎤 語音需要麥克風權限，正在開啟授權頁")
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("request_record_audio", true)
                }
                startActivity(intent)
            } catch (_: Exception) {}
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceStatus("❌ 此裝置未提供語音辨識服務")
            return
        }
        if (isListening) return

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { showVoiceStatus("🎤 請開始說話...") }
                override fun onBeginningOfSpeech() { showVoiceStatus("🔴 錄音中...") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { showVoiceStatus("⏳ 辨識中...") }
                override fun onError(error: Int) {
                    isListening = false
                    stopVoiceListeningUi()
                    showVoiceStatus(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麥克風權限不足，請到 App 授權"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "語音網路異常，請稍後再試"
                        SpeechRecognizer.ERROR_NO_MATCH -> "沒有辨識到內容，請再說一次"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "語音服務忙碌，請稍後重試"
                        SpeechRecognizer.ERROR_CLIENT -> "語音服務初始化失敗，請重試"
                        else -> "語音辨識失敗($error)"
                    }, true)
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    stopVoiceListeningUi()
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = texts?.firstOrNull().orEmpty()
                    if (best.isNotEmpty()) {
                        currentInputConnection?.commitText(best, 1); recordCommit(best)
                        showVoiceStatus("✅ 已輸入：$best")
                    } else {
                        showVoiceStatus("⚠️ 無語音結果", true)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        isListening = true
        startVoiceListeningUi()
        showVoiceStatus("🎤 啟動語音輸入中...")
        try {
            speechRecognizer?.startListening(intent)
        } catch (se: SecurityException) {
            isListening = false
            stopVoiceListeningUi()
            showVoiceStatus("❌ 無法啟動語音：缺少麥克風權限", true)
        } catch (e: Exception) {
            isListening = false
            stopVoiceListeningUi()
            showVoiceStatus("❌ 無法啟動語音：${e.message ?: "未知錯誤"}", true)
        }
    }

    /** 語音提示：顯示較醒目的文字與顏色 */
    private fun showVoiceStatus(text: String, isWarning: Boolean = false) {
        topHintView.text = text
        topHintView.setTextColor(
            if (isWarning) 0xFFFCA5A5.toInt() else 0xFF93C5FD.toInt()
        )
    }

    /** 語音進行中：語音鍵高亮閃爍 */
    private fun startVoiceListeningUi() {
        val btn = voiceButtonRef ?: return
        voicePulseAnimator?.cancel()
        btn.alpha = 1f
        voicePulseAnimator = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 420
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                btn.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    /** 結束語音：停止高亮並還原 */
    private fun stopVoiceListeningUi() {
        voicePulseAnimator?.cancel()
        voicePulseAnimator = null
        voiceButtonRef?.alpha = 1f
    }

    private fun openImeSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /** 開啟主 App 工具頁（加字加詞 / 設定） */
    private fun openMainToolPage(tool: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("open_tool", tool)
        }
        startActivity(intent)
    }

    private fun openAiAssist() {
        val intent = Intent(this, AiAssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ── 加字加詞面板 ──────────────────────────────────────────────

    private fun showAddWordPanel() {
        isAddWordPanelActive = true
        currentExtraPanel = "ADD_WORD"
        addWordCodeBuf.clear(); addWordWordBuf.clear()
        addWordFocus = "CODE"
        composing.clear()
        currentInputConnection?.finishComposingText()
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        setMainKeyboardVisible(true)

        val theme   = uiPrefs.getString("theme", "dark") ?: "dark"
        val bgColor = when (theme) { "light" -> 0xFFEDF1F7.toInt(); "matcha" -> 0xFF10231D.toInt(); else -> 0xFF1A1D24.toInt() }
        val keyTxt  = when (theme) { "light" -> 0xFF1F2937.toInt(); "matcha" -> 0xFFD1FAE5.toInt(); else -> 0xFFE2E8F0.toInt() }
        val accent  = when (theme) { "light" -> 0xFF2563EB.toInt(); else -> 0xFF34D399.toInt() }
        val lbl     = when (theme) { "light" -> 0xFF4B5563.toInt(); else -> 0xFF64748B.toInt() }
        extraPanel.setBackgroundColor(bgColor)

        fun row(block: LinearLayout.() -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                block()
            }
        }

        // 標題列
        val header = row {
            addView(TextView(this@PenguinInputMethodService).apply {
                text = "✏️  加字加詞"
                textSize = 14f; setTextColor(accent)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(12, 10, 0, 6)
            })
            addView(Button(this@PenguinInputMethodService).apply {
                text = "✕ 關閉"; textSize = 11f
                setOnClickListener { hideAddWordPanel() }
            })
        }
        extraPanel.addView(header)

        // 字根輸入列
        val codeRow = row {
            addView(TextView(this@PenguinInputMethodService).apply {
                text = "字根"; textSize = 12f; setTextColor(lbl)
                setPadding(12, 0, 8, 0); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addWordCodeView = TextView(this@PenguinInputMethodService).apply {
                text = ""; textSize = 15f; setTextColor(keyTxt)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(2, accent); cornerRadius = 6 * resources.displayMetrics.density; setColor(0x0F000000)
                }
                setPadding(10, 6, 10, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(0, 4, 8, 4) }
                setOnClickListener { addWordFocus = "CODE"; refreshAddWordDisplay() }
            }
            addView(addWordCodeView)
            addView(Button(this@PenguinInputMethodService).apply {
                text = "查詢"; textSize = 11f
                setOnClickListener { queryAddWordCode() }
            })
        }
        extraPanel.addView(codeRow)

        // 查詢結果
        addWordResultView = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(lbl)
            setPadding(12, 4, 12, 4)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        extraPanel.addView(addWordResultView)

        // 詞句輸入列
        val wordRow = row {
            addView(TextView(this@PenguinInputMethodService).apply {
                text = "詞句"; textSize = 12f; setTextColor(lbl)
                setPadding(12, 0, 8, 0); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addWordWordView = TextView(this@PenguinInputMethodService).apply {
                text = ""; textSize = 15f; setTextColor(keyTxt)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(2, 0x66888888.toInt()); cornerRadius = 6 * resources.displayMetrics.density; setColor(0x0F000000)
                }
                setPadding(10, 6, 10, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(0, 4, 4, 4) }
                setOnClickListener { addWordFocus = "WORD"; refreshAddWordDisplay() }
            }
            addView(addWordWordView)
        }
        extraPanel.addView(wordRow)

        // 操作按鈕列
        val btnRow = row {
            fun mkBtn(label: String, action: () -> Unit) = Button(this@PenguinInputMethodService).apply {
                text = label; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(4, 0, 4, 6) }
                setOnClickListener { action() }
            }
            addView(mkBtn("清除") { addWordCodeBuf.clear(); addWordWordBuf.clear(); refreshAddWordDisplay() })
            addView(mkBtn("新增") { confirmInsertWord() })
            addView(mkBtn("刪除") { deleteCurrentWord() })
        }
        extraPanel.addView(btnRow)

        refreshAddWordDisplay()
        topHintView.text = "目前輸入：字根"
    }

    private fun hideAddWordPanel() {
        isAddWordPanelActive = false
        addWordCodeBuf.clear(); addWordWordBuf.clear()
        addWordCodeView = null; addWordWordView = null; addWordResultView = null
        hideExtraPanelAndShowMainKeyboard()
    }

    private fun appendToAddWord(ch: String) {
        if (addWordFocus == "CODE") addWordCodeBuf.append(ch)
        else addWordWordBuf.append(ch)
        refreshAddWordDisplay()
    }

    private fun deleteFromAddWord() {
        if (addWordFocus == "CODE") { if (addWordCodeBuf.isNotEmpty()) addWordCodeBuf.deleteCharAt(addWordCodeBuf.length - 1) }
        else { if (addWordWordBuf.isNotEmpty()) addWordWordBuf.deleteCharAt(addWordWordBuf.length - 1) }
        refreshAddWordDisplay()
    }

    private fun refreshAddWordDisplay() {
        val codeStr = addWordCodeBuf.toString()
        val wordStr = addWordWordBuf.toString()
        // 高亮目前焦點的輸入框
        val accent  = 0xFF34D399.toInt()
        val neutral = 0x66888888.toInt()
        addWordCodeView?.apply {
            text = if (codeStr.isEmpty()) if (addWordFocus == "CODE") "▌" else "…" else codeStr + if (addWordFocus == "CODE") "▌" else ""
            (background as? android.graphics.drawable.GradientDrawable)?.setStroke(2, if (addWordFocus == "CODE") accent else neutral)
        }
        addWordWordView?.apply {
            text = if (wordStr.isEmpty()) if (addWordFocus == "WORD") "▌" else "…" else wordStr + if (addWordFocus == "WORD") "▌" else ""
            (background as? android.graphics.drawable.GradientDrawable)?.setStroke(2, if (addWordFocus == "WORD") accent else neutral)
        }
        topHintView.text = "目前輸入：${if (addWordFocus == "CODE") "字根" else "詞句"}"
    }

    private fun queryAddWordCode() {
        val code = addWordCodeBuf.toString().trim()
        if (code.isEmpty()) { addWordResultView?.text = "請先輸入字根"; return }
        val candidates = resolveCandidatesForCode(code)
        addWordResultView?.text = if (candidates.isEmpty()) "查無「$code」對應詞條"
        else "已有 ${candidates.size} 筆：${candidates.take(8).joinToString(" ")}"
    }

    private fun confirmInsertWord() {
        val code = addWordCodeBuf.toString().trim()
        val word = addWordWordBuf.toString().trim()
        if (code.isEmpty() || word.isEmpty()) {
            addWordResultView?.text = "字根與詞句均不能為空"
            return
        }
        Thread {
            saveWordToLimeDb(word, code)
            Handler(Looper.getMainLooper()).post {
                addWordResultView?.text = "✅ 已新增：$word → $code"
                addWordWordBuf.clear()
                refreshAddWordDisplay()
                topHintView.text = "已新增，可繼續輸入"
            }
        }.start()
    }

    private fun deleteCurrentWord() {
        val code = addWordCodeBuf.toString().trim()
        val word = addWordWordBuf.toString().trim()
        if (code.isEmpty() || word.isEmpty()) {
            addWordResultView?.text = "請輸入要刪除的字根和詞句"
            return
        }
        Thread {
            val dbFile = ensureLimeDbReady() ?: return@Thread
            try {
                val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                db.use { it.delete("custom_user", "code=? AND word=?", arrayOf(code, word)) }
                // 同步更新記憶體
                val list = codeMap[code]
                list?.remove(word)
                codeSetMap[code]?.remove(word)
                Handler(Looper.getMainLooper()).post {
                    addWordResultView?.text = "🗑 已刪除：$word（$code）"
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    addWordResultView?.text = "刪除失敗：${e.message}"
                }
            }
        }.start()
    }

    // ── 搜尋（剪貼簿文字 → Google）────────────────────────────

    // ── 資料庫匯入／匯出 ─────────────────────────────────────────

    /** 啟動 FilePickerHelperActivity，選取後呼叫 mergeImportedLimeDb() */
    private fun launchDbImport() {
        onDbPickedCallback = { path ->
            Handler(Looper.getMainLooper()).post {
                topHintView.text = "正在匯入資料庫..."
            }
            mergeImportedLimeDb(path)
        }
        val intent = Intent(this, FilePickerHelperActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        topHintView.text = "請選擇 lime.db 檔案"
    }

    /** 讀取匯入的 lime.db，將 custom_user 合併進目前 lime.db */
    private fun mergeImportedLimeDb(importedPath: String) {
        Thread {
            try {
                val importDb = SQLiteDatabase.openDatabase(
                    importedPath, null, SQLiteDatabase.OPEN_READONLY)
                val mainDbFile = ensureLimeDbReady() ?: throw Exception("目前詞庫不存在")
                val mainDb = SQLiteDatabase.openDatabase(
                    mainDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                var count = 0
                importDb.use { src ->
                    mainDb.use { dst ->
                        dst.beginTransaction()
                        try {
                            src.rawQuery(
                                "SELECT code, word, score, basescore FROM custom_user " +
                                "WHERE code IS NOT NULL AND word IS NOT NULL", null
                            ).use { cur ->
                                val iCode = cur.getColumnIndex("code")
                                val iWord = cur.getColumnIndex("word")
                                val iScore = cur.getColumnIndex("score")
                                val iBase  = cur.getColumnIndex("basescore")
                                while (cur.moveToNext()) {
                                    val cv = ContentValues().apply {
                                        put("code",      cur.getString(iCode))
                                        put("word",      cur.getString(iWord))
                                        put("score",     if (iScore >= 0) cur.getInt(iScore) else 100)
                                        put("basescore", if (iBase  >= 0) cur.getInt(iBase)  else 100)
                                    }
                                    dst.insertWithOnConflict(
                                        "custom_user", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                                    count++
                                }
                            }
                            dst.setTransactionSuccessful()
                        } finally {
                            dst.endTransaction()
                        }
                    }
                }
                // 重新載入記憶體詞庫
                loadDictionaries()
                Handler(Looper.getMainLooper()).post {
                    topHintView.text = "✅ 匯入完成，共 $count 筆"
                    Log.i(logTag, "mergeImportedLimeDb: $count rows imported")
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    topHintView.text = "匯入失敗：${e.message}"
                    Log.w(logTag, "mergeImportedLimeDb error: ${e.message}")
                }
            }
        }.start()
    }

    /** 匯出 lime.db 到 getExternalFilesDir() */
    private fun exportLimeDb() {
        val src = File(filesDir, "rime/lime.db")
        if (!src.exists()) {
            topHintView.text = "詞庫尚未初始化，請先開啟鍵盤"
            return
        }
        Thread {
            try {
                val dstDir = getExternalFilesDir(null) ?: filesDir
                dstDir.mkdirs()
                val dst = File(dstDir, "penguin_lime_export.db")
                src.copyTo(dst, overwrite = true)
                Handler(Looper.getMainLooper()).post {
                    topHintView.text = "✅ 已匯出：${dst.absolutePath}"
                    Log.i(logTag, "exportLimeDb: ${dst.absolutePath}")
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    topHintView.text = "匯出失敗：${e.message}"
                }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────

    private fun searchWithClipboard() {
        val clip = clipboardManager.primaryClip
        val query = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        else ""
        if (query.isEmpty()) { topHintView.text = "剪貼簿無文字內容"; return }
        try {
            val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            topHintView.text = "搜尋：$query"
        } catch (e: Exception) {
            topHintView.text = "無法開啟瀏覽器"
        }
    }

    // ─────────────────────────────────────────────────────────────

    // ── 語言模式切換 ──────────────────────────────────────────────

    private fun cycleInputLangMode() {
        inputLangMode = when (inputLangMode) {
            "ZH_TW" -> "ZH_CN"
            "ZH_CN" -> "JA"
            "JA"    -> "EN"
            else    -> "ZH_TW"
        }
        composing.clear()
        currentLanguageMode = if (inputLangMode == "EN") "EN" else "XM"
        keyboardRootView?.let { refreshEnglishKeyCaps(it) }
        topHintView.text = getModeLabel()
        refreshCompositionUi()
    }

    private fun getModeLabel() = when (inputLangMode) {
        "ZH_TW" -> "繁體中文"
        "ZH_CN" -> "简体中文"
        "JA"    -> "日本語（ローマ字）"
        else    -> "English"
    }

    /** 繁→簡轉換：對字串中每個字逐一查表 */
    private fun convertToSimplified(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(TW_TO_CN[ch.toString()] ?: ch.toString())
        }
        return sb.toString()
    }

    /** 羅馬字→平假名：貪婪左右掃描 */
    private fun convertRomaji(romaji: String): String {
        val result = StringBuilder()
        var i = 0
        val s = romaji.lowercase()
        while (i < s.length) {
            var matched = false
            for (len in minOf(4, s.length - i) downTo 2) {
                val sub = s.substring(i, i + len)
                ROMAJI_KANA[sub]?.let { result.append(it); i += len; matched = true }
                if (matched) break
            }
            if (!matched) {
                // n + 子音 → ん
                if (s[i] == 'n' && i + 1 < s.length && s[i+1] !in "aiueoy") {
                    result.append("ん"); i++
                } else {
                    ROMAJI_KANA[s[i].toString()]?.let { result.append(it) } ?: result.append(s[i])
                    i++
                }
            }
        }
        return result.toString()
    }

    // ─────────────────────────────────────────────────────────────

    // ── AI 面板 ───────────────────────────────────────────────────

    private fun showAiPanel() {
        composing.clear()
        currentInputConnection?.finishComposingText()
        isAiPanelActive = true
        currentExtraPanel = "AI"
        aiQuestionBuffer.clear()
        aiResponseText = ""
        aiAttachedImagePath = null

        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        setMainKeyboardVisible(true)

        // 設定 callback 接收 ImagePickerHelperActivity 的選圖結果
        onImagePickedCallback = { path ->
            aiAttachedImagePath = path
            Handler(Looper.getMainLooper()).post {
                aiImageStatusView?.visibility = View.VISIBLE
                aiImageStatusView?.text = "📷 已附圖：${java.io.File(path).name}"
                topHintView.text = "圖片已附加，輸入問題後按送出"
            }
        }

        // 標題列
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 4)
        }
        val titleView = TextView(this).apply {
            text = "🤖 AI 問答"
            textSize = 14f
            setTextColor(0xFF34D399.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8, 0, 0, 0)
        }
        val closeBtn = Button(this).apply {
            text = "✕ 關閉"
            textSize = 11f
            setOnClickListener { hideAiPanel() }
        }
        header.addView(titleView)
        header.addView(closeBtn)
        extraPanel.addView(header)

        // 問題顯示區
        val questionLabel = TextView(this).apply {
            text = "問題："
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(12, 4, 8, 0)
        }
        extraPanel.addView(questionLabel)

        aiQuestionDisplayView = TextView(this).apply {
            text = "（用下方鍵盤輸入問題）"
            textSize = 14f
            setTextColor(0xFFF1F5F9.toInt())
            setPadding(12, 4, 12, 4)
            minLines = 2
            maxLines = 4
        }
        extraPanel.addView(aiQuestionDisplayView)

        // 操作按鈕列
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
        }
        val attachBtn = Button(this).apply {
            text = "📎 附圖"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { launchImagePicker() }
        }
        val clearBtn = Button(this).apply {
            text = "🗑 清除"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                aiQuestionBuffer.clear()
                aiAttachedImagePath = null
                aiImageStatusView?.visibility = View.GONE
                updateAiQuestionDisplay()
            }
        }
        aiSendButton = Button(this).apply {
            text = "→ 送出"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { triggerAiSend() }
        }
        btnRow.addView(attachBtn)
        btnRow.addView(clearBtn)
        btnRow.addView(aiSendButton)
        extraPanel.addView(btnRow)

        // 圖片狀態
        aiImageStatusView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF34D399.toInt())
            setPadding(12, 2, 12, 2)
            visibility = View.GONE
        }
        extraPanel.addView(aiImageStatusView)

        // 回答標題
        val responseLabel = TextView(this).apply {
            text = "AI 回答："
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(12, 8, 8, 0)
        }
        extraPanel.addView(responseLabel)

        // 回答顯示區
        aiResponseDisplayView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFFF1F5F9.toInt())
            setPadding(12, 4, 12, 4)
            minLines = 3
        }
        extraPanel.addView(aiResponseDisplayView)

        // 插入按鈕（有回答後才顯示）
        aiInsertButton = Button(this).apply {
            text = "✓ 插入回答"
            textSize = 13f
            visibility = View.GONE
            setOnClickListener {
                currentInputConnection?.commitText(aiResponseText, 1)
                recordCommit(aiResponseText)
                hideAiPanel()
            }
        }
        extraPanel.addView(aiInsertButton)

        topHintView.text = "長按 AI 鍵可關閉面板"
    }

    private fun hideAiPanel() {
        isAiPanelActive = false
        onImagePickedCallback = null
        aiQuestionBuffer.clear()
        aiAttachedImagePath = null
        aiResponseText = ""
        aiQuestionDisplayView = null
        aiResponseDisplayView = null
        aiImageStatusView = null
        aiInsertButton = null
        aiSendButton = null
        hideExtraPanelAndShowMainKeyboard()
        topHintView.text = "AI 面板已關閉"
    }

    private fun updateAiQuestionDisplay() {
        val text = aiQuestionBuffer.toString()
        aiQuestionDisplayView?.text = if (text.isEmpty()) "（用下方鍵盤輸入問題）" else text
    }

    private fun triggerAiSend() {
        val question = aiQuestionBuffer.toString().trim()
        if (question.isEmpty()) {
            topHintView.text = "請先輸入問題"
            return
        }
        val apiKey = aiPrefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            topHintView.text = "請先在 App 設定頁填寫 Gemini API Key"
            return
        }
        aiSendButton?.isEnabled = false
        aiResponseDisplayView?.text = "⏳ 詢問中..."
        aiInsertButton?.visibility = View.GONE
        topHintView.text = "正在呼叫 Gemini..."
        callGeminiApi(question, aiAttachedImagePath) { result ->
            aiResponseText = result
            aiResponseDisplayView?.text = result
            aiInsertButton?.visibility = View.VISIBLE
            aiSendButton?.isEnabled = true
            topHintView.text = "AI 回答已就緒，按『插入回答』"
        }
    }

    private fun callGeminiApi(question: String, imagePath: String?, onResult: (String) -> Unit) {
        val apiKey = aiPrefs.getString("gemini_api_key", "") ?: ""
        val model = "gemini-2.0-flash"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        Thread {
            try {
                val parts = JSONArray()
                parts.put(JSONObject().put("text", question))
                if (imagePath != null) {
                    val imgFile = java.io.File(imagePath)
                    if (imgFile.exists()) {
                        val bytes = imgFile.readBytes()
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val mime = if (imagePath.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
                        parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", mime).put("data", b64)
                            )
                        )
                    }
                }
                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(JSONObject().put("parts", parts))
                )
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val raw = if (code == 200) {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } else {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP $code"
                }
                val text = if (code == 200) {
                    JSONObject(raw)
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                } else {
                    "API 錯誤 ($code)：$raw"
                }
                Handler(Looper.getMainLooper()).post { onResult(text) }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onResult("呼叫失敗：${e.message}") }
            }
        }.start()
    }

    private fun launchImagePicker() {
        val intent = Intent(this, ImagePickerHelperActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        topHintView.text = "選擇圖片後自動附加"
    }

    // ─────────────────────────────────────────────────────────────

    private fun pasteFromClipboardAsAiAnswer() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else ""
        if (text.isNotBlank()) {
            currentInputConnection?.commitText(text, 1)
            recordCommit(text)
            topHintView.text = "已貼上AI回覆"
        } else {
            currentInputConnection?.commitText(" ", 1)
            recordCommit(" ")
        }
    }

    private fun showClipboardPanel() {
        captureClipboardPrimary()
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "CLIPBOARD"
        setMainKeyboardVisible(true)
        isClipboardSearchActive = true
        clipboardSearchBuf.clear()

        val theme   = resolveThemeMode()
        val bgColor = if (theme == "light") 0xFFEDF1F7.toInt() else 0xFF1A1D24.toInt()
        val keyBg   = if (theme == "light") 0xFFFFFFFF.toInt() else 0xFF252830.toInt()
        val keyTxt  = if (theme == "light") 0xFF1F2937.toInt() else 0xFFE2E8F0.toInt()
        val secTxt  = if (theme == "light") 0xFF6B7280.toInt() else 0xFF64748B.toInt()
        val accent  = if (theme == "light") 0xFF2563EB.toInt() else 0xFF34D399.toInt()
        val dp      = resources.displayMetrics.density

        extraPanel.setBackgroundColor(bgColor)

        // ── 頂列：返回 + 搜尋輸入框 ──────────────────────────────
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (44*dp).toInt())
            setPadding((4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt(), (2*dp).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        topRow.addView(Button(this).apply {
            text = "←"; textSize = 14f; setTextColor(secTxt)
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams((36*dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { hideExtraPanelAndShowMainKeyboard() }
        })
        clipboardSearchView = TextView(this).apply {
            text = "（用鍵盤輸入關鍵字搜尋）"
            textSize = 13f; setTextColor(secTxt)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(keyBg); cornerRadius = 8*dp
                setStroke(2, accent)
            }
            setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).also {
                it.setMargins((4*dp).toInt(), 0, (4*dp).toInt(), 0)
            }
        }
        topRow.addView(clipboardSearchView)
        topRow.addView(Button(this).apply {
            text = "✕"; textSize = 13f; setTextColor(secTxt)
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams((36*dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                clipboardSearchBuf.clear()
                refreshClipboardSearch()
            }
        })
        extraPanel.addView(topRow)

        // ── 結果容器（由 refreshClipboardSearch 填充）───────────
        clipboardResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        extraPanel.addView(clipboardResultsContainer)

        refreshClipboardSearch()
        topHintView.text = "🔍 輸入關鍵字搜尋剪貼簿"
    }

    /** 依搜尋字串過濾並重繪結果 */
    private fun refreshClipboardSearch() {
        val container = clipboardResultsContainer ?: return
        container.removeAllViews()

        val theme   = resolveThemeMode()
        val keyBg   = if (theme == "light") 0xFFFFFFFF.toInt() else 0xFF252830.toInt()
        val keyTxt  = if (theme == "light") 0xFF1F2937.toInt() else 0xFFE2E8F0.toInt()
        val secTxt  = if (theme == "light") 0xFF6B7280.toInt() else 0xFF64748B.toInt()
        val accentG = if (theme == "light") 0xFF059669.toInt() else 0xFF34D399.toInt()
        val dp      = resources.displayMetrics.density
        val query   = clipboardSearchBuf.toString().trim()

        // 更新搜尋框顯示
        clipboardSearchView?.apply {
            text = if (query.isEmpty()) "（用鍵盤輸入關鍵字搜尋）▌"
                   else "🔍 $query▌"
            setTextColor(if (query.isEmpty()) secTxt else keyTxt)
        }

        val filtered = if (query.isEmpty()) clipboardItems
                       else clipboardItems.filter { it.text.contains(query, ignoreCase = true) }

        val favorites = filtered.filter { it.isFavorite }
        val recent    = filtered.filter { !it.isFavorite }

        if (query.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "🔍 搜尋「$query」：共 ${filtered.size} 筆"
                textSize = 11f; setTextColor(secTxt)
                setPadding((10*dp).toInt(), (4*dp).toInt(), (10*dp).toInt(), 2)
            })
        }

        if (favorites.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "★ 常用（${favorites.size} 筆）"; textSize = 12f; setTextColor(accentG)
                setPadding((10*dp).toInt(), (6*dp).toInt(), (10*dp).toInt(), 2)
            })
            favorites.forEach { item -> container.addView(buildClipRow(item, keyBg, keyTxt, secTxt, dp)) }
        }

        if (recent.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "🕐 最近7天（${recent.size} 筆）"; textSize = 12f; setTextColor(secTxt)
                setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), 2)
            })
            recent.forEach { item -> container.addView(buildClipRow(item, keyBg, keyTxt, secTxt, dp)) }
        }

        if (filtered.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (query.isEmpty()) "剪貼簿空白" else "找不到「$query」"
                textSize = 13f; setTextColor(secTxt); gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56*dp).toInt())
            })
        }

        container.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (8*dp).toInt())
        })
    }

    /** 建立單筆剪貼簿列 */
    private fun buildClipRow(item: ClipboardItem, keyBg: Int, keyTxt: Int, secTxt: Int, dp: Float): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding((6*dp).toInt(), (2*dp).toInt(), (6*dp).toInt(), (2*dp).toInt())
        }
        val preview = if (item.text.length > 20) item.text.substring(0, 20) + "…" else item.text
        val timeStr = formatRelativeTime(item.createdAt)

        // 主文字按鈕
        val mainBtn = Button(this).apply {
            text = preview; textSize = 13f
            setTextColor(keyTxt); gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            setPadding((10*dp).toInt(), 0, (8*dp).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(keyBg); cornerRadius = 8*dp
            }
            layoutParams = LinearLayout.LayoutParams(0, (44*dp).toInt(), 1f).also { it.setMargins(0,0,(4*dp).toInt(),0) }
            setOnClickListener {
                currentInputConnection?.commitText(item.text, 1); recordCommit(item.text)
                addClipboardItemToDb(item.text)
            }
            // 長按刪除
            setOnLongClickListener {
                deleteClipboardItemFromDb(item.text)
                showClipboardPanel()
                true
            }
        }
        // 時間標記
        val timeTv = TextView(this).apply {
            text = timeStr; textSize = 10f; setTextColor(secTxt)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((36*dp).toInt(), (44*dp).toInt())
        }
        // ★ 切換常用
        val starBtn = Button(this).apply {
            text = if (item.isFavorite) "★" else "☆"
            textSize = 16f
            setTextColor(if (item.isFavorite) 0xFFF59E0B.toInt() else secTxt)
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams((38*dp).toInt(), (44*dp).toInt())
            setOnClickListener {
                toggleClipboardFavoriteInDb(item.text)
                showClipboardPanel()
            }
        }
        row.addView(mainBtn); row.addView(timeTv); row.addView(starBtn)
        return row
    }

    private fun showNumpadPanel() {
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "NUMPAD"
        setMainKeyboardVisible(false)

        val theme   = resolveThemeMode()
        val bgColor = when (theme) { "light" -> 0xFFEDF1F7.toInt(); "matcha" -> 0xFF10231D.toInt(); else -> 0xFF1A1D24.toInt() }
        val keyBg   = when (theme) { "light" -> 0xFFFFFFFF.toInt(); "matcha" -> 0xFF1C3528.toInt(); else -> 0xFF2A2D38.toInt() }
        val keyTxt  = when (theme) { "light" -> 0xFF1F2937.toInt(); "matcha" -> 0xFFD1FAE5.toInt(); else -> 0xFFF2F4FA.toInt() }
        val secTxt  = when (theme) { "light" -> 0xFF6B7280.toInt(); "matcha" -> 0xFF6EE7B7.toInt(); else -> 0xFF6B7280.toInt() }
        val btnH    = (58 * resources.displayMetrics.density).toInt()
        val margin  = (3 * resources.displayMetrics.density).toInt()

        extraPanel.setBackgroundColor(bgColor)

        // 返回按鈕列
        val backRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(margin, margin, margin, 0)
        }
        val backBtn = Button(this).apply {
            text = "← 返回鍵盤"
            textSize = 13f
            setTextColor(secTxt)
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { hideExtraPanelAndShowMainKeyboard() }
        }
        backRow.addView(backBtn)
        extraPanel.addView(backRow)

        // 數字鍵配置：主數字 + 次要字符（電話鍵盤樣式）
        val numData = listOf(
            listOf("1" to "!@#", "2" to "abc", "3" to "def"),
            listOf("4" to "ghi", "5" to "jkl", "6" to "mno"),
            listOf("7" to "pqrs", "8" to "tuv", "9" to "wxyz"),
            listOf("*" to "×", "0" to "_", "⌫" to "")
        )

        numData.forEach { rowData ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins(margin, margin, margin, 0)
                }
            }
            rowData.forEach { (primary, secondary) ->
                // 每個按鍵用 FrameLayout 疊放主次字符
                val frame = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, btnH, 1f).also {
                        it.setMargins(margin, 0, margin, 0)
                    }
                    setBackgroundColor(keyBg)
                    // 圓角效果
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(keyBg)
                        cornerRadius = 10 * resources.displayMetrics.density
                    }
                }
                // 主字符（大）
                val mainTv = TextView(this).apply {
                    text = primary
                    textSize = if (primary == "⌫") 22f else 26f
                    setTextColor(if (primary == "⌫") 0xFFEF4444.toInt() else keyTxt)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                    setPadding(0, if (secondary.isEmpty()) 0 else (8 * resources.displayMetrics.density).toInt(), 0, 0)
                }
                frame.addView(mainTv)
                // 次要字符（小，右下角）
                if (secondary.isNotEmpty()) {
                    val secTv = TextView(this).apply {
                        text = secondary
                        textSize = 10f
                        setTextColor(secTxt)
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT).also {
                            it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                        }
                        setPadding(0, 0, (6 * resources.displayMetrics.density).toInt(),
                            (5 * resources.displayMetrics.density).toInt())
                    }
                    frame.addView(secTv)
                }
                frame.setOnClickListener {
                    when (primary) {
                        "⌫" -> onBackspacePressed()
                        else -> { currentInputConnection?.commitText(primary, 1); recordCommit(primary) }
                    }
                }
                row.addView(frame)
            }
            extraPanel.addView(row)
        }
        // 底部 margin
        val spacer = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, margin)
        }
        extraPanel.addView(spacer)
    }

    private fun showSymbolsPanel() {
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "SYMBOLS"
        setMainKeyboardVisible(false)

        val theme  = resolveThemeMode()
        val bgColor= when (theme) { "light" -> 0xFFEDF1F7.toInt(); "matcha" -> 0xFF10231D.toInt(); else -> 0xFF1A1D24.toInt() }
        val keyBg  = when (theme) { "light" -> 0xFFFFFFFF.toInt(); "matcha" -> 0xFF1C3528.toInt(); else -> 0xFF2A2D38.toInt() }
        val keyTxt = when (theme) { "light" -> 0xFF1F2937.toInt(); "matcha" -> 0xFFD1FAE5.toInt(); else -> 0xFFF2F4FA.toInt() }
        val margin = (3 * resources.displayMetrics.density).toInt()

        extraPanel.setBackgroundColor(bgColor)

        // 返回鍵
        val backBtn = Button(this).apply {
            text = "← 返回鍵盤"
            textSize = 13f
            setTextColor(when (theme) { "light" -> 0xFF6B7280.toInt(); "matcha" -> 0xFF6EE7B7.toInt(); else -> 0xFF6B7280.toInt() })
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { hideExtraPanelAndShowMainKeyboard() }
        }
        extraPanel.addView(backBtn)

        // 特殊字元（中文標點 + 常用符號）
        val symbols = listOf(
            "，","。","？","！","：","；",
            "（","）","《","》","「","」",
            "、","…","※","—","～","·",
            "★","♥","✓","→","←","↑",
            "↓","【","】","『","』","〔",
            "〕","＆","＠","＃","％","＊"
        )
        var idx = 0
        while (idx < symbols.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins(margin, margin, margin, 0)
                }
            }
            for (i in 0 until 6) {
                if (idx >= symbols.size) break
                val s = symbols[idx++]
                val btn = Button(this).apply {
                    text = s
                    textSize = 18f
                    setTextColor(keyTxt)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(keyBg); cornerRadius = 8 * resources.displayMetrics.density
                    }
                    layoutParams = LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f).also {
                        it.setMargins(margin, 0, margin, 0)
                    }
                    setOnClickListener { currentInputConnection?.commitText(s, 1); recordCommit(s) }
                }
                row.addView(btn)
            }
            extraPanel.addView(row)
        }
        val spacer = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, margin * 2)
        }
        extraPanel.addView(spacer)
    }

    private fun showEmojiPanel() {
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "EMOJI"
        setMainKeyboardVisible(false)

        val theme = resolveThemeMode()
        val bgColor = when (theme) { "light" -> 0xFFF3F6FB.toInt(); "matcha" -> 0xFF10231D.toInt(); else -> 0xFF1A1D24.toInt() }
        val keyBg = when (theme) { "light" -> 0xFFFFFFFF.toInt(); "matcha" -> 0xFF1C3528.toInt(); else -> 0xFF2A2D38.toInt() }
        val keyTxt = when (theme) { "light" -> 0xFF1F2937.toInt(); "matcha" -> 0xFFD1FAE5.toInt(); else -> 0xFFF2F4FA.toInt() }
        val hintTxt = when (theme) { "light" -> 0xFF64748B.toInt(); "matcha" -> 0xFF6EE7B7.toInt(); else -> 0xFF9CA3AF.toInt() }
        val margin = (3 * resources.displayMetrics.density).toInt()

        extraPanel.setBackgroundColor(bgColor)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(margin, margin, margin, margin)
        }
        val backBtn = Button(this).apply {
            text = "← 返回"
            textSize = 12f
            setTextColor(hintTxt)
            setBackgroundColor(0x00000000)
            setOnClickListener { hideExtraPanelAndShowMainKeyboard() }
        }
        val title = TextView(this).apply {
            text = "😀 Emoji"
            textSize = 13f
            setTextColor(keyTxt)
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8.dpToPx(), 0, 0, 0)
        }
        header.addView(backBtn)
        header.addView(title)
        extraPanel.addView(header)

        val categories = listOf(
            "常用" to listOf("😂","🤣","😊","😍","😘","😎","🥺","😭","😡","👍","🙏","❤️","🔥","🎉","👏","🤔","😴","🤝","🙌","😁","😉","😅","🤯","😱"),
            "表情" to listOf("😀","😃","😄","😁","😆","😅","😂","🤣","🙂","🙃","😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😋","😛","😝","🤪","🤗"),
            "手勢" to listOf("👍","👎","👌","✌️","🤞","🤟","🤘","🤙","👋","👏","🙌","🫶","🙏","💪","🫰","✍️","🫱","🫲","🤝","👊","✊","🤛","🤜","🖐️"),
            "愛心" to listOf("❤️","🩷","🧡","💛","💚","🩵","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟","💌","❤️‍🔥","❤️‍🩹"),
            "生活" to listOf("☕","🍔","🍟","🍕","🍜","🍣","🍩","🍎","🍺","🏀","⚽","🎵","🎮","📷","💻","📱","🚗","✈️","🏠","🌈","⭐","🌙","☀️","⛈️")
        )

        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val emojiGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun renderEmojiGrid(list: List<String>) {
            emojiGridContainer.removeAllViews()
            var idx = 0
            while (idx < list.size) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(margin, margin, margin, 0)
                    }
                }
                for (i in 0 until 8) {
                    if (idx >= list.size) break
                    val e = list[idx++]
                    val btn = Button(this).apply {
                        text = e
                        textSize = 20f
                        includeFontPadding = false
                        setTextColor(keyTxt)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(keyBg)
                            cornerRadius = 8 * resources.displayMetrics.density
                            setStroke((1 * resources.displayMetrics.density).toInt(), 0xFFD6DFEF.toInt())
                        }
                        layoutParams = LinearLayout.LayoutParams(0, (42 * resources.displayMetrics.density).toInt(), 1f).also {
                            it.setMargins(margin, 0, margin, 0)
                        }
                        setOnClickListener {
                            currentInputConnection?.commitText(e, 1)
                            recordCommit(e)
                        }
                    }
                    row.addView(btn)
                }
                emojiGridContainer.addView(row)
            }
        }

        categories.forEachIndexed { index, (label, list) ->
            val tab = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(if (index == 0) 0xFF2563EB.toInt() else hintTxt)
                setBackgroundColor(0x00000000)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    for (i in 0 until categoryRow.childCount) {
                        (categoryRow.getChildAt(i) as? Button)?.setTextColor(hintTxt)
                    }
                    setTextColor(0xFF2563EB.toInt())
                    renderEmojiGrid(list)
                }
            }
            categoryRow.addView(tab)
        }
        extraPanel.addView(categoryRow)
        renderEmojiGrid(categories.first().second)
        extraPanel.addView(emojiGridContainer)

        val spacer = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, margin * 2)
        }
        extraPanel.addView(spacer)
    }

    private fun captureClipboardPrimary() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        addClipboardItemToDb(text)
    }

    private fun toggleSymbolsPanel() {
        if (currentExtraPanel == "SYMBOLS" && extraScroll.visibility == View.VISIBLE) {
            hideExtraPanelAndShowMainKeyboard()
            topHintView.text = "已切回原鍵盤"
            return
        }
        showSymbolsPanel()
    }

    private fun toggleEmojiPanel() {
        if (currentExtraPanel == "EMOJI" && extraScroll.visibility == View.VISIBLE) {
            hideExtraPanelAndShowMainKeyboard()
            topHintView.text = "已切回原鍵盤"
            return
        }
        showEmojiPanel()
    }

    private fun hideExtraPanelAndShowMainKeyboard() {
        isClipboardSearchActive = false
        clipboardSearchBuf.clear()
        clipboardSearchView = null
        clipboardResultsContainer = null
        extraPanel.removeAllViews()
        extraScroll.visibility = View.GONE
        currentExtraPanel = "NONE"
        setMainKeyboardVisible(true)
    }

    private fun toggleNumpadPanel() {
        if (currentExtraPanel == "NUMPAD" && extraScroll.visibility == View.VISIBLE) {
            hideExtraPanelAndShowMainKeyboard()
            topHintView.text = "已切回原鍵盤"
            return
        }
        showNumpadPanel()
    }

    private fun setMainKeyboardVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        rowLetters1.visibility = v
        rowLetters2.visibility = v
        rowLetters3.visibility = v
        rowLetters4.visibility = v
    }

    private fun toggleClipboardPanel() {
        if (currentExtraPanel == "CLIPBOARD" && extraScroll.visibility == View.VISIBLE) {
            hideExtraPanelAndShowMainKeyboard()
            topHintView.text = "剪貼簿已關閉"
            return
        }
        showClipboardPanel()
    }

    // ── 剪貼簿 DB 方法 ──────────────────────────────────────────

    private val CLIP_TABLE = "clipboard_penguin"
    private val CLIP_EXPIRY_SECS = 7 * 24 * 3600L  // 7 天

    private fun ensureClipboardTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $CLIP_TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT UNIQUE NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    /** 載入剪貼簿（清除逾期 + 填充 clipboardItems），在背景執行 */
    private fun loadClipboardFromDb() {
        val dbFile = ensureLimeDbReady() ?: return
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.use {
                ensureClipboardTable(it)
                // 清除7天前的非常用
                val cutoff = System.currentTimeMillis() / 1000 - CLIP_EXPIRY_SECS
                it.delete(CLIP_TABLE, "is_favorite=0 AND created_at<?", arrayOf(cutoff.toString()))
                // 遷移舊 SharedPreferences 資料（首次）
                migrateClipboardFromPrefs(it)
                // 載入
                val items = mutableListOf<ClipboardItem>()
                it.rawQuery(
                    "SELECT text, created_at, is_favorite FROM $CLIP_TABLE ORDER BY is_favorite DESC, created_at DESC",
                    null
                ).use { cur ->
                    val iText = cur.getColumnIndex("text")
                    val iTime = cur.getColumnIndex("created_at")
                    val iFav  = cur.getColumnIndex("is_favorite")
                    while (cur.moveToNext()) {
                        items.add(ClipboardItem(
                            text       = cur.getString(iText),
                            createdAt  = cur.getLong(iTime),
                            isFavorite = cur.getInt(iFav) == 1
                        ))
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    clipboardItems.clear()
                    clipboardItems.addAll(items)
                }
            }
        } catch (e: Exception) {
            Log.w(logTag, "loadClipboardFromDb: ${e.message}")
        }
    }

    /** 新增/更新剪貼簿（保留常用標記，在背景執行） */
    private fun addClipboardItemToDb(text: String) {
        if (text.isBlank()) return
        Thread {
            val dbFile = ensureLimeDbReady() ?: return@Thread
            try {
                val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                val now = System.currentTimeMillis() / 1000
                db.use {
                    ensureClipboardTable(it)
                    val cv = ContentValues().apply { put("created_at", now) }
                    val updated = it.update(CLIP_TABLE, cv, "text=?", arrayOf(text))
                    if (updated == 0) {
                        cv.put("text", text); cv.put("is_favorite", 0)
                        it.insertWithOnConflict(CLIP_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
                // 更新記憶體
                Handler(Looper.getMainLooper()).post {
                    val idx = clipboardItems.indexOfFirst { it.text == text }
                    if (idx >= 0) {
                        clipboardItems[idx] = clipboardItems[idx].copy(createdAt = now)
                        clipboardItems.sortWith(compareByDescending<ClipboardItem> { it.isFavorite }.thenByDescending { it.createdAt })
                    } else {
                        clipboardItems.add(0, ClipboardItem(text, now, false))
                    }
                }
            } catch (e: Exception) {
                Log.w(logTag, "addClipboardItemToDb: ${e.message}")
            }
        }.start()
    }

    /** 切換常用標記 */
    private fun toggleClipboardFavoriteInDb(text: String) {
        val item = clipboardItems.find { it.text == text } ?: return
        val newFav = if (item.isFavorite) 0 else 1
        item.isFavorite = newFav == 1
        clipboardItems.sortWith(compareByDescending<ClipboardItem> { it.isFavorite }.thenByDescending { it.createdAt })
        Thread {
            val dbFile = ensureLimeDbReady() ?: return@Thread
            try {
                val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                db.use {
                    ensureClipboardTable(it)
                    val cv = ContentValues().apply { put("is_favorite", newFav) }
                    it.update(CLIP_TABLE, cv, "text=?", arrayOf(text))
                }
            } catch (e: Exception) {
                Log.w(logTag, "toggleClipboardFavoriteInDb: ${e.message}")
            }
        }.start()
    }

    /** 刪除剪貼簿項目 */
    private fun deleteClipboardItemFromDb(text: String) {
        clipboardItems.removeAll { it.text == text }
        Thread {
            val dbFile = ensureLimeDbReady() ?: return@Thread
            try {
                val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                db.use { it.delete(CLIP_TABLE, "text=?", arrayOf(text)) }
            } catch (e: Exception) {
                Log.w(logTag, "deleteClipboardItemFromDb: ${e.message}")
            }
        }.start()
    }

    /** 首次執行時，從 SharedPreferences 遷移舊剪貼簿資料 */
    private fun migrateClipboardFromPrefs(db: SQLiteDatabase) {
        val prefs = getSharedPreferences("weasel_clipboard", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migrated_to_db", false)) return
        val now = System.currentTimeMillis() / 1000
        val histJson = prefs.getString("history", "[]") ?: "[]"
        val favJson  = prefs.getString("favorites", "[]") ?: "[]"
        val favSet   = mutableSetOf<String>()
        val fArr = JSONArray(favJson)
        for (i in 0 until fArr.length()) { val t = fArr.optString(i); if (t.isNotEmpty()) favSet.add(t) }
        val hArr = JSONArray(histJson)
        for (i in 0 until hArr.length()) {
            val t = hArr.optString(i); if (t.isEmpty()) continue
            val cv = ContentValues().apply {
                put("text", t); put("created_at", now - i * 60)  // 假設每分鐘一筆
                put("is_favorite", if (favSet.contains(t)) 1 else 0)
            }
            db.insertWithOnConflict(CLIP_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
        prefs.edit().putBoolean("migrated_to_db", true).apply()
    }

    /** 相對時間格式 */
    private fun formatRelativeTime(epochSeconds: Long): String {
        val diff = System.currentTimeMillis() / 1000 - epochSeconds
        return when {
            diff < 60    -> "剛才"
            diff < 3600  -> "${diff/60}分前"
            diff < 86400 -> "${diff/3600}時前"
            else         -> "${diff/86400}天前"
        }
    }

    private fun readAssetText(assetPath: String): String? {
        return try {
            val bytes = assets.open(assetPath).use { it.readBytes() }
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadActiveSchemaFromAssets(): String {
        return try {
            assets.open("rime/android_ime_profile.json").bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                json.optString("active_schema", "liur")
            }
        } catch (_: Exception) {
            "liur"
        }
    }
}
