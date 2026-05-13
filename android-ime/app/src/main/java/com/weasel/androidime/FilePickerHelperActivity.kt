package com.penguinInput.androidime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream

/**
 * 透明 Activity，讓 IME 能夠開啟系統檔案選取器。
 * 選取後將檔案複製到 filesDir/rime/import_lime.db，
 * 並透過 PenguinInputMethodService.onDbPickedCallback 通知 Service。
 */
class FilePickerHelperActivity : Activity() {

    companion object {
        private const val REQUEST_FILE = 3001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "選取 lime.db 資料庫檔")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    val targetDir = File(filesDir, "rime")
                    targetDir.mkdirs()
                    val target = File(targetDir, "import_lime.db")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    PenguinInputMethodService.onDbPickedCallback?.invoke(target.absolutePath)
                } catch (_: Exception) {
                    // 複製失敗時靜默處理
                }
            }
        }
        finish()
    }
}
