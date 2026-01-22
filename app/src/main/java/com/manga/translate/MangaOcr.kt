package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max

class MangaOcr(private val context: Context) : OcrEngine {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoderSession: OrtSession = createSession("encoder_model.onnx")
    private val decoderSession: OrtSession = createSession("decoder_model.onnx")
    private val generationConfig = loadGenerationConfig()
    private val imageConfig = loadImageConfig()
    private val tokenizer = loadTokenizer()
    private val settingsStore = SettingsStore(context.applicationContext)

    override fun recognize(bitmap: Bitmap): String {
        val imageTensor = preprocess(bitmap)
        val encoderOutputs = encoderSession.run(mapOf(encoderInputName() to imageTensor))
        val encoderHidden = encoderOutputs[0] as? OnnxTensor ?: run {
            imageTensor.close()
            encoderOutputs.close()
            return ""
        }

        val encoderShape = (encoderHidden.info as TensorInfo).shape
        val encoderSeqLen = encoderShape.getOrNull(1)?.toInt() ?: 0
        val encoderAttention = createEncoderAttentionMask(encoderSeqLen)
        val decoderInputName = decoderInputName()
        val encoderHiddenName = encoderHiddenName()
        val encoderAttentionName = encoderAttentionName()

        val ids = ArrayList<Int>(generationConfig.maxLength)
        ids.add(generationConfig.decoderStartTokenId)
        var done = false
        var step = 0
        while (!done && step < generationConfig.maxLength) {
            val inputIds = ids.map { it.toLong() }.toLongArray()
            val inputTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(inputIds),
                longArrayOf(1, inputIds.size.toLong())
            )
            val inputs = LinkedHashMap<String, OnnxTensor>()
            inputs[decoderInputName] = inputTensor
            inputs[encoderHiddenName] = encoderHidden
            if (encoderAttentionName != null && encoderAttention != null) {
                inputs[encoderAttentionName] = encoderAttention
            }
            val outputs = decoderSession.run(inputs)
            val logits = extractLastLogits(outputs[0].value, inputIds.size)
            val nextId = logits?.let { argmax(it) } ?: generationConfig.eosTokenId
            outputs.close()
            inputTensor.close()

            ids.add(nextId)
            if (nextId == generationConfig.eosTokenId) {
                done = true
            }
            step++
        }

        encoderAttention?.close()
        encoderHidden.close()
        encoderOutputs.close()
        imageTensor.close()

        val text = tokenizer.decode(ids)
        if (settingsStore.loadModelIoLogging()) {
            AppLogger.log("MangaOcr", "Input ${bitmap.width}x${bitmap.height}, output: $text")
        }
        return text
    }

    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            imageConfig.width,
            imageConfig.height,
            true
        )
        val input = FloatArray(3 * imageConfig.width * imageConfig.height)
        var index = 0
        for (y in 0 until imageConfig.height) {
            for (x in 0 until imageConfig.width) {
                val pixel = resized.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) * imageConfig.rescaleFactor
                val g = ((pixel shr 8) and 0xFF) * imageConfig.rescaleFactor
                val b = (pixel and 0xFF) * imageConfig.rescaleFactor
                input[index] = ((r - imageConfig.mean[0]) / imageConfig.std[0]).toFloat()
                input[index + imageConfig.width * imageConfig.height] =
                    ((g - imageConfig.mean[1]) / imageConfig.std[1]).toFloat()
                input[index + 2 * imageConfig.width * imageConfig.height] =
                    ((b - imageConfig.mean[2]) / imageConfig.std[2]).toFloat()
                index++
            }
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, imageConfig.height.toLong(), imageConfig.width.toLong())
        )
    }

    private fun extractLastLogits(raw: Any, seqLen: Int): FloatArray? {
        val batch = raw as? Array<*> ?: return null
        val tokens = batch.firstOrNull() as? Array<*> ?: return null
        val last = tokens.getOrNull(seqLen - 1) as? FloatArray ?: return null
        return last
    }

    private fun argmax(values: FloatArray): Int {
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in values.indices) {
            if (values[i] > bestValue) {
                bestValue = values[i]
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun encoderInputName(): String {
        return encoderSession.inputInfo.keys.first()
    }

    private fun decoderInputName(): String {
        return decoderSession.inputInfo.keys.first { it.contains("input_ids") }
    }

    private fun encoderHiddenName(): String {
        return decoderSession.inputInfo.keys.first { it.contains("encoder_hidden") }
    }

    private fun encoderAttentionName(): String? {
        return decoderSession.inputInfo.keys.firstOrNull { it.contains("encoder_attention") }
    }

    private fun createEncoderAttentionMask(seqLen: Int): OnnxTensor? {
        if (seqLen <= 0) return null
        val mask = LongArray(seqLen) { 1L }
        return OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(mask),
            longArrayOf(1, seqLen.toLong())
        )
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

    private fun loadGenerationConfig(): GenerationConfig {
        val json = JSONObject(readAsset("generation_config.json"))
        return GenerationConfig(
            decoderStartTokenId = json.getInt("decoder_start_token_id"),
            eosTokenId = json.getInt("eos_token_id"),
            maxLength = json.getInt("max_length"),
            padTokenId = json.getInt("pad_token_id")
        )
    }

    private fun loadImageConfig(): ImageConfig {
        val json = JSONObject(readAsset("preprocessor_config.json"))
        val size = json.getJSONObject("size")
        val mean = json.getJSONArray("image_mean")
        val std = json.getJSONArray("image_std")
        return ImageConfig(
            width = size.getInt("width"),
            height = size.getInt("height"),
            rescaleFactor = json.getDouble("rescale_factor").toFloat(),
            mean = floatArrayOf(
                mean.getDouble(0).toFloat(),
                mean.getDouble(1).toFloat(),
                mean.getDouble(2).toFloat()
            ),
            std = floatArrayOf(
                std.getDouble(0).toFloat(),
                std.getDouble(1).toFloat(),
                std.getDouble(2).toFloat()
            )
        )
    }

    private fun loadTokenizer(): SimpleTokenizer {
        val tokenizerJson = JSONObject(readAsset("tokenizer.json"))
        val modelJson = tokenizerJson.getJSONObject("model")
        val vocabJson = modelJson.getJSONObject("vocab")
        val entries = ArrayList<Pair<String, Int>>(vocabJson.length())
        val keys = vocabJson.keys()
        var maxId = 0
        while (keys.hasNext()) {
            val token = keys.next()
            val id = vocabJson.getInt(token)
            maxId = max(maxId, id)
            entries.add(Pair(token, id))
        }
        val idToToken = Array(maxId + 1) { "" }
        for ((token, id) in entries) {
            if (id in idToToken.indices) {
                idToToken[id] = token
            }
        }

        val specialJson = JSONObject(readAsset("special_tokens_map.json"))
        val specialTokens = HashSet<String>()
        val specialKeys = specialJson.keys()
        while (specialKeys.hasNext()) {
            val key = specialKeys.next()
            val tokenObj = specialJson.getJSONObject(key)
            specialTokens.add(tokenObj.getString("content"))
        }
        return SimpleTokenizer(idToToken, specialTokens)
    }

    private fun readAsset(name: String): String {
        return context.assets.open(name).bufferedReader().use { it.readText() }
    }
}

private data class GenerationConfig(
    val decoderStartTokenId: Int,
    val eosTokenId: Int,
    val maxLength: Int,
    val padTokenId: Int
)

private data class ImageConfig(
    val width: Int,
    val height: Int,
    val rescaleFactor: Float,
    val mean: FloatArray,
    val std: FloatArray
)

private class SimpleTokenizer(
    private val idToToken: Array<String>,
    private val specialTokens: Set<String>
) {
    fun decode(ids: List<Int>): String {
        val builder = StringBuilder()
        for (id in ids) {
            if (id !in idToToken.indices) continue
            val token = idToToken[id]
            if (token.isEmpty() || token in specialTokens) continue
            if (token.startsWith("##")) {
                builder.append(token.substring(2))
            } else {
                builder.append(token)
            }
        }
        return builder.toString()
    }
}
