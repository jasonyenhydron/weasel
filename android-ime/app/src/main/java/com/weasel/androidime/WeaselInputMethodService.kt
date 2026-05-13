package com.weasel.androidime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class WeaselInputMethodService : InputMethodService() {
    private val logTag = "WeaselIME"
    private var activeSchema: String = "liur"

    private val composing = StringBuilder()
    private lateinit var composingView: TextView
    private lateinit var candidateView: TextView
    private lateinit var topHintView: TextView
    private lateinit var candidateContainer: LinearLayout
    private lateinit var extraPanel: LinearLayout
    private lateinit var extraScroll: View
    private lateinit var rowLetters1: View
    private lateinit var rowLetters2: View
    private lateinit var rowLetters3: View
    private lateinit var rowLetters4: View
    private lateinit var rowSpecial: View
    private var keyboardRootView: View? = null
    private var currentExtraPanel: String = "NONE" // NONE | CLIPBOARD | NUMPAD | SYMBOLS

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val uiPrefs by lazy { getSharedPreferences("weasel_ui", Context.MODE_PRIVATE) }
    private val candidatePrefs by lazy { getSharedPreferences("weasel_candidate", Context.MODE_PRIVATE) }

    private val codeMap: MutableMap<String, MutableList<String>> = ConcurrentHashMap()
    private val codeSetMap: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    private val removedByCode: MutableMap<String, MutableSet<String>> = linkedMapOf()
    private val pinnedByCode: MutableMap<String, MutableSet<String>> = linkedMapOf()
    private val relatedMap: MutableMap<String, MutableList<String>> = linkedMapOf()
    private var lastCommittedText: String = ""
    private val relatedPrefs by lazy { getSharedPreferences("weasel_related", Context.MODE_PRIVATE) }

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

    companion object {
        /** ImagePickerHelperActivity 選圖完成後呼叫此 callback 通知 IME */
        var onImagePickedCallback: ((String) -> Unit)? = null
    }

    private val clipboardHistory = mutableListOf<String>()
    private val clipboardFavorites = mutableListOf<String>()
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardPrefs by lazy { getSharedPreferences("weasel_clipboard", Context.MODE_PRIVATE) }
    private var ignoreNextClipboardEvent = false
    private var currentLanguageMode = "XM" // XM | EN
    private var isEnglishUppercase = false
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
    // 嘸蝦米特殊字根符號鍵
    private val specialKeyIds = listOf(
        R.id.key_lbracket, R.id.key_semicolon, R.id.key_quote,
        R.id.key_slash, R.id.key_rbracket, R.id.key_backslash,
        R.id.key_minus, R.id.key_backtick,
        R.id.key_comma, R.id.key_period
    )

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (ignoreNextClipboardEvent) {
            ignoreNextClipboardEvent = false
            return@OnPrimaryClipChangedListener
        }
        captureClipboardPrimary()
    }

    override fun onCreate() {
        super.onCreate()
        activeSchema = loadActiveSchemaFromAssets()
        loadCandidatePrefs()
        loadRelatedState()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        loadClipboardState()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        captureClipboardPrimary()

        Log.i(logTag, "Active schema: $activeSchema — loading dictionaries in background")
        Thread {
            loadDictionaries()
            loadRelatedFromTsv("rime/likeime_related.tsv")
            Handler(Looper.getMainLooper()).post {
                Log.i(logTag, "Dictionaries loaded: ${codeMap.size} codes")
                if (::topHintView.isInitialized) topHintView.text = "詞庫已就緒"
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
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
    }

    override fun onCreateInputView(): View {
        val keyboardView = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null)
        keyboardRootView = keyboardView
        composingView = keyboardView.findViewById(R.id.composing_text)
        candidateView = keyboardView.findViewById(R.id.candidate_text)
        topHintView = keyboardView.findViewById(R.id.top_hint_text)
        candidateContainer = keyboardView.findViewById(R.id.candidate_container)
        candidateButtonPool.clear()
        extraPanel = keyboardView.findViewById(R.id.extra_panel)
        extraScroll = keyboardView.findViewById(R.id.extra_scroll)
        rowLetters1 = keyboardView.findViewById(R.id.row_letters_1)
        rowLetters2 = keyboardView.findViewById(R.id.row_letters_2)
        rowLetters3 = keyboardView.findViewById(R.id.row_letters_3)
        rowLetters4 = keyboardView.findViewById(R.id.row_letters_4)
        rowSpecial  = keyboardView.findViewById(R.id.row_special)

        for (id in letterKeyIds) {
            val key = keyboardView.findViewById<Button>(id)
            key.setOnClickListener { onLetterPressed(key.text.toString()) }
        }
        for (id in numberKeyIds) {
            val key = keyboardView.findViewById<Button>(id)
            key.setOnClickListener { onNumberPressed(key.text.toString()) }
        }
        // 特殊字根鍵（[ ; ' / ] \ , . - `）
        for (id in specialKeyIds) {
            val key = keyboardView.findViewById<Button>(id)
            key.setOnClickListener { onLetterPressed(key.text.toString()) }
        }

        keyboardView.findViewById<Button>(R.id.key_space).setOnClickListener { commitOrSpace() }
        keyboardView.findViewById<Button>(R.id.key_enter).setOnClickListener { commitOrEnter() }
        keyboardView.findViewById<Button>(R.id.key_backspace).setOnClickListener { onBackspacePressed() }
        keyboardView.findViewById<Button>(R.id.key_shift).setOnClickListener { toggleEnglishShift() }
        keyboardView.findViewById<View>(R.id.key_voice).setOnClickListener { startVoiceInput() }
        keyboardView.findViewById<View>(R.id.key_apps).setOnClickListener { toggleLanguageMode() }
        keyboardView.findViewById<View>(R.id.key_clipboard).setOnClickListener { toggleClipboardPanel() }
        keyboardView.findViewById<View>(R.id.key_numpad).setOnClickListener { toggleNumpadPanel() }
        keyboardView.findViewById<View>(R.id.key_settings).setOnClickListener { openImeSettings() }
        keyboardView.findViewById<View>(R.id.key_ai).setOnClickListener { openAiAssist() }
        keyboardView.findViewById<View>(R.id.key_ai).setOnLongClickListener { showAiPanel(); true }
        keyboardView.findViewById<View>(R.id.key_symbols).setOnClickListener { toggleSymbolsPanel() }
        keyboardView.findViewById<Button>(R.id.key_symbols_toggle).setOnClickListener { toggleSymbolsPanel() }
        // 🌐 → 切換輸入法
        keyboardView.findViewById<Button>(R.id.key_globe).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
        candidateView.setOnClickListener { commitCandidateOrRaw() }

        applyUiSettings(keyboardView)
        refreshEnglishKeyCaps(keyboardView)
        refreshCompositionUi()
        return keyboardView
    }

    private fun applyUiSettings(root: View) {
        val theme = uiPrefs.getString("theme", "dark") ?: "dark"
        val keyScale = uiPrefs.getInt("key_scale", 100).coerceIn(75, 140) / 100f
        val textScale = uiPrefs.getInt("text_scale", 100).coerceIn(80, 140) / 100f

        val (bgColor, keyBg, keyText, accent) = when (theme) {
            "light" -> arrayOf(0xFFEDEFF2.toInt(), R.drawable.bg_tool_btn, 0xFF1F2937.toInt(), 0xFF2563EB.toInt())
            "matcha" -> arrayOf(0xFF10231D.toInt(), R.drawable.bg_key, 0xFFE7F9EE.toInt(), 0xFF34D399.toInt())
            else -> arrayOf(0xFF171B1F.toInt(), R.drawable.bg_key, 0xFFF1F5F9.toInt(), 0xFF34D399.toInt())
        }
        root.setBackgroundColor(bgColor as Int)
        topHintView.setTextColor(if (theme == "light") 0xFF4B5563.toInt() else 0xFFC7D0CF.toInt())
        candidateView.setTextColor(accent as Int)

        styleButtonsRecursive(root, keyBg as Int, keyText as Int, keyScale, textScale)
    }

    private fun styleButtonsRecursive(view: View, bgRes: Int, textColor: Int, keyScale: Float, textScale: Float) {
        when (view) {
            is Button -> {
                view.setBackgroundResource(bgRes)
                view.setTextColor(textColor)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * textScale)
                val lp = view.layoutParams
                if (lp != null && lp.height > 0) {
                    lp.height = (lp.height * keyScale).toInt().coerceAtLeast(30)
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

    private fun onLetterPressed(letter: String) {
        if (currentLanguageMode == "EN") {
            val out = if (isEnglishUppercase) letter.uppercase() else letter.lowercase()
            currentInputConnection?.commitText(out, 1)
            recordCommit(out)
            return
        }
        if (isAiPanelActive) {
            aiQuestionBuffer.append(letter)
            updateAiQuestionDisplay()
            return
        }
        composing.append(letter)
        refreshCompositionUi()
    }

    private fun onBackspacePressed() {
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
        if (currentLanguageMode == "EN") {
            currentInputConnection?.commitText(number, 1)
            recordCommit(number)
            return
        }
        if (isAiPanelActive) {
            aiQuestionBuffer.append(number)
            updateAiQuestionDisplay()
            return
        }
        composing.append(number)
        refreshCompositionUi()
    }

    private fun commitOrEnter() {
        if (isAiPanelActive) {
            triggerAiSend()
            return
        }
        if (composing.isNotEmpty()) {
            val raw = composing.toString()
            currentInputConnection?.commitText(raw, 1)
            recordCommit(raw)
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
        val candidate = codeMap[code]?.firstOrNull()
        val output = candidate ?: code
        currentInputConnection?.commitText(output, 1)
        recordCommit(output)
        composing.clear()
        refreshCompositionUi()
        return true
    }

    private fun refreshCompositionUi() {
        if (currentLanguageMode == "EN") {
            composingView.text = "模式: EN"
            candidateView.text = ""
            candidateContainer.removeAllViews()
            currentInputConnection?.finishComposingText()
            return
        }
        val code = composing.toString()
        val candidates = resolveCandidatesForCode(code)
        val related = relatedMap[lastCommittedText].orEmpty()
        val activeList = if (code.isEmpty()) related else candidates
        val topCandidate = activeList.firstOrNull().orEmpty()
        composingView.text = if (code.isEmpty()) "" else "字根: $code"
        candidateView.text = when {
            topCandidate.isEmpty() -> ""
            code.isEmpty() -> "聯想: $topCandidate"
            else -> "候選: $topCandidate"
        }
        renderCandidates(activeList)
        if (code.isEmpty()) {
            currentInputConnection?.finishComposingText()
        } else {
            currentInputConnection?.setComposingText(topCandidate.ifEmpty { code }, 1)
        }
    }

    private fun renderCandidates(candidates: List<String>) {
        val maxCount = minOf(8, candidates.size)
        while (candidateButtonPool.size < maxCount) {
            val btn = Button(this).apply {
                textSize = 15f
                setPadding(18, 0, 18, 0)
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
                    currentInputConnection?.commitText(word, 1)
                    recordCommit(word)
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
        val base = codeMap[code].orEmpty()
        val removed = removedByCode[code].orEmpty()
        val pinned = pinnedByCode[code].orEmpty()
        val filtered = base.filter { !removed.contains(it) }
        val pinnedList = filtered.filter { pinned.contains(it) }
        val normalList = filtered.filter { !pinned.contains(it) }
        return pinnedList + normalList
    }

    private fun loadDictionaries() {
        codeMap.clear()
        codeSetMap.clear()
        loadFromLimeDb()
        loadFromUserTsv()
        loadFromCustomDict("rime/likeime_custom.dict.yaml")
        loadFromCustomDict("rime/liur.extended.dict.yaml")
        loadFromCustomDict("rime/liur_shortcuts.dict.yaml")
        loadFromLiurTable("rime/liur.txt")
    }

    private fun toggleLanguageMode() {
        currentLanguageMode = if (currentLanguageMode == "XM") "EN" else "XM"
        if (currentLanguageMode == "EN") {
            composing.clear()
            isEnglishUppercase = false
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
        isEnglishUppercase = !isEnglishUppercase
        keyboardRootView?.let { refreshEnglishKeyCaps(it) }
        topHintView.text = if (isEnglishUppercase) "EN 大寫" else "EN 小寫"
    }

    private fun refreshEnglishKeyCaps(root: View) {
        val isEn = currentLanguageMode == "EN"
        for (id in letterKeyIds) {
            val btn = root.findViewById<Button>(id)
            val base = btn.text.toString().lowercase()
            btn.text = if (isEn && isEnglishUppercase) base.uppercase() else base
        }
        val shiftBtn = root.findViewById<Button>(R.id.key_shift)
        shiftBtn.text = if (isEn && isEnglishUppercase) "⇪" else "⇧"
        shiftBtn.visibility = if (isEn) View.VISIBLE else View.GONE
        // 特殊字根列：英文模式隱藏（英文不需要字根符號列）
        if (::rowSpecial.isInitialized) {
            rowSpecial.visibility = if (isEn) View.GONE else View.VISIBLE
        }
        // Space 列顯示模式標籤
        root.findViewById<Button>(R.id.key_space)?.let { sp ->
            sp.text = if (isEn) "English" else "嘸蝦米"
        }
    }

    private fun loadFromLimeDb() {
        val dbFile = ensureLimeDbCopied()
        if (dbFile == null || !dbFile.exists()) return

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            loadCodeWordTable(db, "custom")
            loadCodeWordTable(db, "custom_user")
        } catch (e: Exception) {
            Log.w(logTag, "loadFromLimeDb failed: ${e.message}")
        } finally {
            db?.close()
        }
    }

    private fun ensureLimeDbCopied(): File? {
        return try {
            val targetDir = File(filesDir, "rime")
            if (!targetDir.exists()) targetDir.mkdirs()
            val target = File(targetDir, "lime.db")
            if (!target.exists() || target.length() == 0L) {
                assets.open("rime/lime.db").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            target
        } catch (e: Exception) {
            Log.w(logTag, "ensureLimeDbCopied failed: ${e.message}")
            null
        }
    }

    private fun loadCodeWordTable(db: SQLiteDatabase, table: String) {
        // LikeIME 對齊：以 score 高到低優先放入 code -> word 候選。
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

    private fun loadFromUserTsv() {
        val file = File(filesDir, "user_dict.tsv")
        if (!file.exists()) return
        file.readLines(Charsets.UTF_8).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split('\t')
            if (parts.size < 2) return@forEach
            val word = parts[0].trim()
            val code = parts[1].trim()
            if (word.isEmpty() || code.isEmpty()) return@forEach
            addCandidate(code, word)
        }
    }

    private fun loadFromCustomDict(assetPath: String) {
        val text = readAssetText(assetPath) ?: return
        var started = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!started) {
                if (line == "...") started = true
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = raw.split('\t')
            if (parts.size < 2) return@forEach
            val word = parts[0].trim()
            val code = parts[1].trim()
            if (word.isEmpty() || code.isEmpty()) return@forEach
            addCandidate(code, word)
        }
    }

    private fun loadFromLiurTable(assetPath: String) {
        val text = readAssetText(assetPath) ?: return
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = raw.split('\t')
            if (parts.size < 2) return@forEach
            val word = parts[0].trim()
            val code = parts[1].trim()
            if (word.isEmpty() || code.isEmpty()) return@forEach
            addCandidate(code, word)
        }
    }

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
        val file = File(filesDir, "user_dict.tsv")
        file.parentFile?.mkdirs()
        val exists = if (file.exists()) {
            file.readLines(Charsets.UTF_8).any { it.trim() == "$word\t$code" }
        } else false
        if (!exists) {
            file.appendText("$word\t$code\n", Charsets.UTF_8)
        }
    }

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
            topHintView.text = "請先從 App 圖示頁啟用麥克風權限"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            topHintView.text = "此裝置未提供語音辨識服務"
            return
        }
        if (isListening) return

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { topHintView.text = "Speak now" }
                override fun onBeginningOfSpeech() { topHintView.text = "Listening..." }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { topHintView.text = "辨識中..." }
                override fun onError(error: Int) { isListening = false; topHintView.text = "語音辨識失敗($error)" }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = texts?.firstOrNull().orEmpty()
                    if (best.isNotEmpty()) {
                        currentInputConnection?.commitText(best, 1); recordCommit(best)
                        topHintView.text = "Voice OK"
                    } else {
                        topHintView.text = "無語音結果"
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
        }

        isListening = true
        topHintView.text = "Speak now"
        speechRecognizer?.startListening(intent)
    }

    private fun openImeSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openAiAssist() {
        val intent = Intent(this, AiAssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

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

        if (clipboardFavorites.isNotEmpty()) {
            val favTitle = TextView(this).apply { text = "最愛 (最多5)"; textSize = 13f }
            extraPanel.addView(favTitle)
            val favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            clipboardFavorites.take(5).forEach { item -> favRow.addView(makeClipboardButton(item, true)) }
            extraPanel.addView(favRow)
        }

        val title = TextView(this).apply { text = "剪貼簿最近30筆"; textSize = 13f }
        extraPanel.addView(title)

        clipboardHistory.take(30).forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeClipboardButton(item, false))
            row.addView(makeFavoriteToggleButton(item))
            extraPanel.addView(row)
        }
    }

    private fun makeClipboardButton(text: String, isFavorite: Boolean): Button {
        val label = if (text.length > 12) text.substring(0, 12) + "…" else text
        return Button(this).apply {
            this.text = if (isFavorite) "★ $label" else label
            setOnClickListener {
                currentInputConnection?.commitText(text, 1); recordCommit(text)
                addClipboardItem(text)
            }
        }
    }

    private fun makeFavoriteToggleButton(text: String): Button {
        val isFav = clipboardFavorites.contains(text)
        return Button(this).apply {
            this.text = if (isFav) "★" else "☆"
            setOnClickListener {
                if (clipboardFavorites.contains(text)) {
                    clipboardFavorites.remove(text)
                } else {
                    clipboardFavorites.add(0, text)
                    if (clipboardFavorites.size > 5) clipboardFavorites.removeAt(clipboardFavorites.lastIndex)
                }
                saveClipboardState()
                showClipboardPanel()
            }
        }
    }

    private fun showNumpadPanel() {
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "NUMPAD"
        setMainKeyboardVisible(false)

        val btnHeightPx = (56 * resources.displayMetrics.density).toInt()
        val rows = listOf(
            listOf("7", "8", "9"),
            listOf("4", "5", "6"),
            listOf("1", "2", "3"),
            listOf("0", ".", "⌫")
        )
        rows.forEach { rowVals ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowVals.forEach { v ->
                val btn = Button(this).apply {
                    text = v
                    textSize = 20f
                    layoutParams = LinearLayout.LayoutParams(0, btnHeightPx, 1f)
                    setOnClickListener {
                        when (v) {
                            "⌫" -> onBackspacePressed()
                            else -> { currentInputConnection?.commitText(v, 1); recordCommit(v) }
                        }
                    }
                }
                row.addView(btn)
            }
            extraPanel.addView(row)
        }
    }

    private fun showSymbolsPanel() {
        extraPanel.removeAllViews()
        extraScroll.visibility = View.VISIBLE
        currentExtraPanel = "SYMBOLS"
        setMainKeyboardVisible(false)
        val title = TextView(this).apply { text = "特殊符號"; textSize = 13f }
        extraPanel.addView(title)

        val symbols = listOf("，", "。", "？", "！", "：", "；", "（", "）", "《", "》", "「", "」", "、", "…", "※", "★", "♥", "✓", "→", "←", "↑", "↓")
        var idx = 0
        while (idx < symbols.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 6) {
                if (idx >= symbols.size) break
                val s = symbols[idx++]
                row.addView(Button(this).apply {
                    text = s
                    setOnClickListener { currentInputConnection?.commitText(s, 1); recordCommit(s) }
                })
            }
            extraPanel.addView(row)
        }
    }

    private fun captureClipboardPrimary() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        addClipboardItem(text)
    }

    private fun toggleSymbolsPanel() {
        if (currentExtraPanel == "SYMBOLS" && extraScroll.visibility == View.VISIBLE) {
            hideExtraPanelAndShowMainKeyboard()
            topHintView.text = "已切回原鍵盤"
            return
        }
        showSymbolsPanel()
    }

    private fun hideExtraPanelAndShowMainKeyboard() {
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
        rowSpecial.visibility  = v
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

    private fun addClipboardItem(text: String) {
        clipboardHistory.remove(text)
        clipboardHistory.add(0, text)
        while (clipboardHistory.size > 30) clipboardHistory.removeAt(clipboardHistory.lastIndex)
        saveClipboardState()
    }

    private fun loadClipboardState() {
        clipboardHistory.clear()
        clipboardFavorites.clear()

        val historyJson = clipboardPrefs.getString("history", "[]") ?: "[]"
        val favJson = clipboardPrefs.getString("favorites", "[]") ?: "[]"

        val hArr = JSONArray(historyJson)
        for (i in 0 until hArr.length()) {
            val t = hArr.optString(i)
            if (t.isNotEmpty()) clipboardHistory.add(t)
        }

        val fArr = JSONArray(favJson)
        for (i in 0 until fArr.length()) {
            val t = fArr.optString(i)
            if (t.isNotEmpty()) clipboardFavorites.add(t)
        }
    }

    private fun saveClipboardState() {
        val hArr = JSONArray()
        clipboardHistory.take(30).forEach { hArr.put(it) }
        val fArr = JSONArray()
        clipboardFavorites.take(5).forEach { fArr.put(it) }
        clipboardPrefs.edit().putString("history", hArr.toString()).putString("favorites", fArr.toString()).apply()
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
