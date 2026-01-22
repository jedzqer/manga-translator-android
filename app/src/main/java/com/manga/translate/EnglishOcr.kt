package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

/**
 * 英文OCR识别器，基于PP-OCRv5识别模型
 * 需要配合TextDetector使用Multilingual_PP-OCRv3_det_infer.onnx进行文字检测
 */
class EnglishOcr(private val context: Context) : OcrEngine {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession("en_PP-OCRv5_rec_mobile_infer.onnx")
    private val charset: List<String> = readCharset()
    private val inputName: String = session.inputInfo.keys.first()
    private val settingsStore = SettingsStore(context.applicationContext)

    /**
     * 识别裁剪后的文字图像
     * @param bitmap 裁剪后的文字区域图像
     * @return 识别出的文字
     */
    override fun recognize(bitmap: Bitmap): String {
        return recognizeWithScore(bitmap).text
    }

    fun recognizeWithScore(bitmap: Bitmap): OcrResult {
        val preprocessed = preprocess(bitmap)
        preprocessed.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0]
                val outputShape = (output.info as TensorInfo).shape
                val decoded = ctcDecodeWithScore(output.value, outputShape)
                if (settingsStore.loadModelIoLogging()) {
                    AppLogger.log(
                        "EnglishOcr",
                        "Input ${bitmap.width}x${bitmap.height}, output: ${decoded.text}"
                    )
                }
                return decoded
            }
        }
    }

    /**
     * 预处理图像
     * 参考Python脚本的preprocess_rec函数
     */
    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val imgH = 48
        val imgW = 320
        
        val h = bitmap.height
        val w = bitmap.width
        val ratio = w.toFloat() / h.toFloat()
        var targetW = (imgH * ratio).toInt()
        targetW = targetW.coerceAtMost(imgW)
        
        // 调整图像大小
        val resized = Bitmap.createScaledBitmap(bitmap, targetW, imgH, true)
        
        // 归一化并转换为CHW格式（BGR顺序）
        val input = FloatArray(3 * imgH * imgW)
        for (y in 0 until imgH) {
            for (x in 0 until targetW) {
                val pixel = resized.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val bNorm = (b - 0.5f) / 0.5f
                val gNorm = (g - 0.5f) / 0.5f
                val rNorm = (r - 0.5f) / 0.5f
                val base = y * imgW + x
                input[base] = bNorm
                input[base + imgH * imgW] = gNorm
                input[base + 2 * imgH * imgW] = rNorm
            }
        }
        
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, imgH.toLong(), imgW.toLong())
        )
    }

    /**
     * CTC解码
     * 参考Python脚本的ctc_decode函数
     */
    private fun ctcDecode(raw: Any, shape: LongArray): String {
        return ctcDecodeWithScore(raw, shape).text
    }

    private fun ctcDecodeWithScore(raw: Any, shape: LongArray): OcrResult {
        val batch = raw as? Array<*> ?: return OcrResult("", 0f)
        val first = batch.firstOrNull() as? Array<*> ?: return OcrResult("", 0f)
        val firstVec = first.firstOrNull()
        if (firstVec !is FloatArray) return OcrResult("", 0f)

        val result = StringBuilder()
        val confs = ArrayList<Float>()
        var prevIdx = -1

        fun appendIndex(maxIdx: Int, maxProb: Float) {
            // CTC解码规则：
            // 1. 跳过重复的索引
            if (maxIdx == prevIdx) {
                prevIdx = maxIdx
                return
            }
            prevIdx = maxIdx
            
            // 2. 跳过blank (索引0)
            if (maxIdx == 0) {
                return
            }
            
            // 添加字符
            if (maxIdx < charset.size) {
                result.append(charset[maxIdx])
                confs.add(maxProb)
            }
        }

        fun argmax(probs: FloatArray): Int {
            var maxIdx = 0
            var maxProb = probs[0]
            for (i in 1 until probs.size) {
                if (probs[i] > maxProb) {
                    maxProb = probs[i]
                    maxIdx = i
                }
            }
            return maxIdx
        }

        fun maxProb(probs: FloatArray, maxIdx: Int): Float {
            return if (maxIdx in probs.indices) probs[maxIdx] else 0f
        }

        val dim0 = batch.size
        val dim1 = first.size
        val dim2 = firstVec.size
        val looksLikeClasses: (Int) -> Boolean = { value ->
            value == charset.size || value == charset.size - 1 || value == charset.size + 1
        }

        when {
            dim1 == 1 && dim0 > 1 -> {
                for (t in 0 until dim0) {
                    val inner = batch[t] as? Array<*> ?: continue
                    val probs = inner.firstOrNull() as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            dim0 == 1 && looksLikeClasses(dim2) -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            dim0 == 1 && looksLikeClasses(dim1) -> {
                val classArrays = first.mapNotNull { it as? FloatArray }
                val timeCount = classArrays.firstOrNull()?.size ?: 0
                for (t in 0 until timeCount) {
                    var maxIdx = 0
                    var maxProb = Float.NEGATIVE_INFINITY
                    for (c in classArrays.indices) {
                        val arr = classArrays[c]
                        if (t >= arr.size) continue
                        val v = arr[t]
                        if (v > maxProb) {
                            maxProb = v
                            maxIdx = c
                        }
                    }
                    appendIndex(maxIdx, maxProb)
                }
            }
            shape.size == 3 && shape[0] == 1L -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            else -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
        }

        val score = if (confs.isEmpty()) 0f else confs.sum() / confs.size
        return OcrResult(result.toString(), score)
    }

    /**
     * 从模型元数据读取字符集
     * 参考Python脚本的read_rec_charset函数
     */
    private fun readCharset(): List<String> {
        try {
            val meta = session.metadata
            val customMetadata = meta.customMetadata
            val charString = customMetadata["character"] ?: return getDefaultCharset()
            
            val charList = charString.lines().filter { it.isNotBlank() }.toMutableList()
            
            // 与PaddleOCR/RapidOCR对齐：blank在索引0，空格在末尾
            charList.add(0, "blank")
            charList.add(" ")
            
            AppLogger.log("EnglishOcr", "Loaded ${charList.size} characters from model metadata")
            return charList
        } catch (e: Exception) {
            AppLogger.log("EnglishOcr", "Failed to read charset from model, using default", e)
            return getDefaultCharset()
        }
    }

    /**
     * 默认英文字符集（如果无法从模型读取）
     */
    private fun getDefaultCharset(): List<String> {
        val chars = mutableListOf("blank")
        
        // 添加数字
        for (i in '0'..'9') {
            chars.add(i.toString())
        }
        
        // 添加大写字母
        for (i in 'A'..'Z') {
            chars.add(i.toString())
        }
        
        // 添加小写字母
        for (i in 'a'..'z') {
            chars.add(i.toString())
        }
        
        // 添加常见标点符号
        val punctuation = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~ "
        for (c in punctuation) {
            chars.add(c.toString())
        }
        
        AppLogger.log("EnglishOcr", "Using default charset with ${chars.size} characters")
        return chars
    }

    private fun createSession(assetName: String): OrtSession {
        val modelFile = File(context.cacheDir, assetName)
        if (!modelFile.exists()) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    data class OcrResult(
        val text: String,
        val score: Float
    )
}
