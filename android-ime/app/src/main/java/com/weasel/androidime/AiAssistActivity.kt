package com.weasel.androidime

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AiAssistActivity : AppCompatActivity() {
    private lateinit var spinner: Spinner
    private lateinit var questionEdit: EditText

    private val candidatePackages = listOf(
        "com.google.android.apps.bard", // Gemini
        "com.openai.chatgpt",           // ChatGPT
        "com.anthropic.claude",         // Claude
        "com.microsoft.copilot"         // Copilot
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_assist)

        spinner = findViewById(R.id.spinner_ai_apps)
        questionEdit = findViewById(R.id.edit_ai_question)

        val aiApps = findInstalledAiApps()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, aiApps.map { it.second })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        findViewById<Button>(R.id.btn_open_ai).setOnClickListener {
            val q = questionEdit.text.toString().trim()
            if (q.isEmpty()) {
                Toast.makeText(this, "請先輸入問題", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val index = spinner.selectedItemPosition
            if (index < 0 || index >= aiApps.size) {
                Toast.makeText(this, "請先選擇 AI App", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pkg = aiApps[index].first

            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai_question", q))

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, q)
                if (pkg.isNotEmpty()) setPackage(pkg)
            }

            try {
                startActivity(intent)
                Toast.makeText(this, "已開啟 AI App，回答後請複製，回鍵盤按『貼上AI』", Toast.LENGTH_LONG).show()
                finish()
            } catch (_: Exception) {
                Toast.makeText(this, "無法開啟該 AI App", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findInstalledAiApps(): List<Pair<String, String>> {
        val pm = packageManager
        val result = mutableListOf<Pair<String, String>>()
        candidatePackages.forEach { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                result.add(pkg to label)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        if (result.isEmpty()) {
            result.add("" to "系統分享選擇器")
        }
        return result
    }
}
