package com.manga.translate

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class VlmModelManager(private val context: Context) {

    // Store models in app's external files directory to avoid scoped storage limits
    private val modelDir: File = File(context.getExternalFilesDir(null), "minicpm_models").apply {
        if (!exists()) mkdirs()
    }

    val textModelFile: File
        get() = File(modelDir, "minicpm-v-text.gguf")

    val mmprojModelFile: File
        get() = File(modelDir, "minicpm-v-mmproj.gguf")

    fun isModelReady(): Boolean {
        return textModelFile.exists() && mmprojModelFile.exists()
    }

    suspend fun importModelFromUri(uri: Uri, isMmproj: Boolean): Boolean = withContext(Dispatchers.IO) {
        val targetFile = if (isMmproj) mmprojModelFile else textModelFile
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    copyStream(inputStream, outputStream)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            if (targetFile.exists()) targetFile.delete()
            false
        }
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(8192)
        var length: Int
        while (input.read(buffer).also { length = it } > 0) {
            output.write(buffer, 0, length)
        }
        output.flush()
    }

    fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown.gguf"
    }
}