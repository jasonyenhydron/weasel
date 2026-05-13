package com.penguinInput.androidime

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private val requestRecordAudio = 1001
    private val uiPrefs by lazy { getSharedPreferences("weasel_ui", MODE_PRIVATE) }
    private val aiPrefs by lazy { getSharedPreferences("weasel_ai", MODE_PRIVATE) }

    private var keyScale: Int = 100
    private var textScale: Int = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openInputMethodSettingsIfLaunchedFromIcon()
        requestAllPermissionsIfNeeded()
        if (intent?.getBooleanExtra("request_record_audio", false) == true) {
            Toast.makeText(this, "請允許麥克風權限以啟用語音輸入", Toast.LENGTH_SHORT).show()
        }
        handleOpenToolIntent()
        loadUiState()
        bindActions()
        refreshScaleLabel()
    }

    private fun openInputMethodSettingsIfLaunchedFromIcon() {
        val action = intent?.action
        val fromLauncher = intent?.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
        if (action == Intent.ACTION_MAIN && fromLauncher) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Toast.makeText(this, "請在此啟用或切換小企鵝蝦米輸入法", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleOpenToolIntent() {
        when (intent?.getStringExtra("open_tool")) {
            "add_word" -> {
                findViewById<EditText>(R.id.edit_word).requestFocus()
                Toast.makeText(this, "已開啟：加字加詞", Toast.LENGTH_SHORT).show()
            }
            "settings" -> {
                Toast.makeText(this, "已開啟：輸入法設定頁", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestAllPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), requestRecordAudio)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestRecordAudio) {
            val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                Toast.makeText(this, "語音輸入需要麥克風權限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindActions() {
        // ── 啟用輸入法 ──
        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_switch_ime).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // ── 詞庫管理 ──
        findViewById<Button>(R.id.btn_add_word).setOnClickListener { saveUserWord() }
        findViewById<Button>(R.id.btn_export_dict).setOnClickListener { exportBackup() }
        findViewById<Button>(R.id.btn_import_dict).setOnClickListener { importBackup() }

        // ── 外觀 ──
        // 快捷鍵列開關
        val toolbarSwitch = findViewById<Switch>(R.id.switch_toolbar)
        toolbarSwitch.isChecked = uiPrefs.getBoolean("show_toolbar", true)
        toolbarSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            uiPrefs.edit().putBoolean("show_toolbar", checked).apply()
            Toast.makeText(this, if (checked) "快捷鍵列已顯示" else "快捷鍵列已隱藏", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_theme_dark).setOnClickListener { setThemePref("dark") }
        findViewById<Button>(R.id.btn_theme_light).setOnClickListener { setThemePref("light") }
        findViewById<Button>(R.id.btn_theme_matcha).setOnClickListener { setThemePref("matcha") }
        findViewById<Button>(R.id.btn_key_minus).setOnClickListener { updateScale(deltaKey = -5, deltaText = 0) }
        findViewById<Button>(R.id.btn_key_plus).setOnClickListener { updateScale(deltaKey = 5, deltaText = 0) }
        findViewById<Button>(R.id.btn_text_minus).setOnClickListener { updateScale(deltaKey = 0, deltaText = -5) }
        findViewById<Button>(R.id.btn_text_plus).setOnClickListener { updateScale(deltaKey = 0, deltaText = 5) }

        // ── AI 設定 ──
        val geminiKeyInput = findViewById<EditText>(R.id.edit_gemini_key)
        geminiKeyInput.setText(aiPrefs.getString("gemini_api_key", ""))
        findViewById<Button>(R.id.btn_save_gemini_key).setOnClickListener {
            val key = geminiKeyInput.text.toString().trim()
            aiPrefs.edit().putString("gemini_api_key", key).apply()
            Toast.makeText(this, if (key.isEmpty()) "API Key 已清除" else "API Key 已儲存", Toast.LENGTH_SHORT).show()
        }
    }


    private fun saveUserWord() {
        val word = findViewById<EditText>(R.id.edit_word).text.toString().trim()
        val code = findViewById<EditText>(R.id.edit_code).text.toString().trim().lowercase()
        if (word.isEmpty() || code.isEmpty()) {
            Toast.makeText(this, "請輸入字/詞與字根", Toast.LENGTH_SHORT).show()
            return
        }
        if (!code.matches(Regex("^[a-z,.'`/;\\[\\]\\-]+$"))) {
            Toast.makeText(this, "字根格式不正確", Toast.LENGTH_SHORT).show()
            return
        }
        // 寫入 lime.db custom_user（背景執行）
        Thread {
            try {
                val dbFile = File(filesDir, "rime/lime.db")
                if (!dbFile.exists()) {
                    runOnUiThread {
                        Toast.makeText(this, "詞庫尚未初始化，請先開啟鍵盤", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
                )
                db.use {
                    val maxScore = it.rawQuery(
                        "SELECT COALESCE(MAX(score),0) FROM custom_user", null
                    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    val cv = ContentValues().apply {
                        put("code", code); put("word", word)
                        put("score", maxScore + 1); put("basescore", maxScore + 1)
                    }
                    it.insertWithOnConflict(
                        "custom_user", null, cv, SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                runOnUiThread {
                    Toast.makeText(this, "已新增：$word → $code", Toast.LENGTH_SHORT).show()
                    findViewById<EditText>(R.id.edit_word).text.clear()
                    findViewById<EditText>(R.id.edit_code).text.clear()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "寫入失敗：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setThemePref(theme: String) {
        uiPrefs.edit().putString("theme", theme).apply()
        Toast.makeText(this, "主題已切換：$theme", Toast.LENGTH_SHORT).show()
    }

    private fun loadUiState() {
        keyScale = uiPrefs.getInt("key_scale", 100)
        textScale = uiPrefs.getInt("text_scale", 100)
    }

    private fun updateScale(deltaKey: Int, deltaText: Int) {
        keyScale = (keyScale + deltaKey).coerceIn(75, 140)
        textScale = (textScale + deltaText).coerceIn(80, 140)
        uiPrefs.edit().putInt("key_scale", keyScale).putInt("text_scale", textScale).apply()
        refreshScaleLabel()
        Toast.makeText(this, "外觀比例已更新", Toast.LENGTH_SHORT).show()
    }

    private fun refreshScaleLabel() {
        findViewById<TextView>(R.id.txt_scale).text = "鍵高 ${keyScale}% / 字級 ${textScale}%"
    }

    private fun exportBackup() {
        val outFile = File(getExternalFilesDir(null), "penguin_xm_backup.json")
        val userDict = File(filesDir, "user_dict.tsv")
        val related = getSharedPreferences("weasel_related", MODE_PRIVATE).getString("map", "{}") ?: "{}"
        val obj = JSONObject()
        obj.put("user_dict_tsv", if (userDict.exists()) userDict.readText(Charsets.UTF_8) else "")
        obj.put("related_map", JSONObject(related))
        obj.put("ui", JSONObject().put("theme", uiPrefs.getString("theme", "dark")).put("key_scale", keyScale).put("text_scale", textScale))
        outFile.writeText(obj.toString(), Charsets.UTF_8)
        val msg = "已匯出至 ${outFile.name}"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        findViewById<TextView>(R.id.txt_dict_path).text = "路徑：${outFile.absolutePath}"
    }

    private fun importBackup() {
        val inFile = File(getExternalFilesDir(null), "penguin_xm_backup.json")
        if (!inFile.exists()) {
            Toast.makeText(this, "找不到備份檔：${inFile.absolutePath}", Toast.LENGTH_LONG).show()
            return
        }
        val obj = JSONObject(inFile.readText(Charsets.UTF_8))
        val tsv = obj.optString("user_dict_tsv", "")
        File(filesDir, "user_dict.tsv").writeText(tsv, Charsets.UTF_8)

        val relatedObj = obj.optJSONObject("related_map") ?: JSONObject()
        getSharedPreferences("weasel_related", MODE_PRIVATE).edit().putString("map", relatedObj.toString()).apply()

        val uiObj = obj.optJSONObject("ui")
        if (uiObj != null) {
            keyScale = uiObj.optInt("key_scale", keyScale)
            textScale = uiObj.optInt("text_scale", textScale)
            uiPrefs.edit()
                .putString("theme", uiObj.optString("theme", "dark"))
                .putInt("key_scale", keyScale)
                .putInt("text_scale", textScale)
                .apply()
            refreshScaleLabel()
        }

        Toast.makeText(this, "已匯入完成，切回鍵盤即可生效", Toast.LENGTH_SHORT).show()
        findViewById<TextView>(R.id.txt_dict_path).text = "已從 ${inFile.name} 匯入"
    }
}
