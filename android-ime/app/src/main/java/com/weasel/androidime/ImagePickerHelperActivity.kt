package com.weasel.androidime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream

/**
 * 透明 Activity，專門用來讓 IME 能夠啟動圖片選擇器。
 * 選取後將圖片複製到 filesDir/ai/ai_temp_image.jpg，
 * 並透過 WeaselInputMethodService.onImagePickedCallback 通知 IME 面板。
 */
class ImagePickerHelperActivity : Activity() {

    companion object {
        private const val REQUEST_IMAGE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    val targetDir = File(filesDir, "ai")
                    targetDir.mkdirs()
                    val target = File(targetDir, "ai_temp_image.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    WeaselInputMethodService.onImagePickedCallback?.invoke(target.absolutePath)
                } catch (_: Exception) {
                    // 圖片複製失敗時靜默處理
                }
            }
        }
        finish()
    }
}
