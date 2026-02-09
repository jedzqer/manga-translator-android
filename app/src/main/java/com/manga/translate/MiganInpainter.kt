package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

class MiganInpainter(
    private val context: Context,
    private val modelAssetName: String = "migan_512.onnx",
    private val externalDataAssetName: String = "migan_512.onnx.data",
    private val legacyExternalDataName: String = "migan_512_full_precision.onnx.data"
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession()
    private val inputName: String = session.inputInfo.keys.first()

    fun inpaint(source: Bitmap, eraseMask: BooleanArray): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source.copy(Bitmap.Config.ARGB_8888, true)
        if (eraseMask.size != width * height) return source.copy(Bitmap.Config.ARGB_8888, true)
        if (!eraseMask.any { it }) return source.copy(Bitmap.Config.ARGB_8888, true)

        val expandedMask = dilateMask(eraseMask, width, height, PRE_MODEL_DILATE_ITERATIONS)
        val resizedImage = Bitmap.createScaledBitmap(source, MODEL_SIZE, MODEL_SIZE, true)
        val resizedMask = resizeMaskConservative(expandedMask, width, height, MODEL_SIZE, MODEL_SIZE)
        val input = FloatArray(4 * MODEL_SIZE * MODEL_SIZE)
        val hw = MODEL_SIZE * MODEL_SIZE

        for (y in 0 until MODEL_SIZE) {
            for (x in 0 until MODEL_SIZE) {
                val idx = y * MODEL_SIZE + x
                val pixel = resizedImage.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val preserve = if (resizedMask[idx]) 0f else 1f

                val rNorm = r * 2f - 1f
                val gNorm = g * 2f - 1f
                val bNorm = b * 2f - 1f

                input[idx] = preserve - 0.5f
                input[hw + idx] = rNorm * preserve
                input[2 * hw + idx] = gNorm * preserve
                input[3 * hw + idx] = bNorm * preserve
            }
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 4, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())
        )

        val generated = tensor.use {
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0]
                val flattened = flattenOutput(output.value) ?: return@use source.copy(Bitmap.Config.ARGB_8888, true)
                decodeOutputToBitmap(flattened)
            }
        }

        val generatedScaled = Bitmap.createScaledBitmap(generated, width, height, true)
        if (generatedScaled !== generated) {
            generated.recycle()
        }

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                if (expandedMask[idx]) {
                    output.setPixel(x, y, generatedScaled.getPixel(x, y))
                }
            }
        }
        generatedScaled.recycle()
        return output
    }

    private fun resizeMaskConservative(
        mask: BooleanArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): BooleanArray {
        val out = BooleanArray(dstW * dstH)
        for (y in 0 until dstH) {
            val outRow = y * dstW
            val sy0 = (y.toFloat() * srcH / dstH).toInt().coerceIn(0, srcH - 1)
            val sy1 = (((y + 1).toFloat() * srcH / dstH).toInt().coerceAtLeast(sy0 + 1)).coerceIn(1, srcH)
            for (x in 0 until dstW) {
                val sx0 = (x.toFloat() * srcW / dstW).toInt().coerceIn(0, srcW - 1)
                val sx1 = (((x + 1).toFloat() * srcW / dstW).toInt().coerceAtLeast(sx0 + 1)).coerceIn(1, srcW)
                var filled = false
                loop@ for (sy in sy0 until sy1) {
                    val row = sy * srcW
                    for (sx in sx0 until sx1) {
                        if (mask[row + sx]) {
                            filled = true
                            break@loop
                        }
                    }
                }
                out[outRow + x] = filled
            }
        }
        return out
    }

    private fun dilateMask(mask: BooleanArray, width: Int, height: Int, iterations: Int): BooleanArray {
        var current = mask
        repeat(iterations.coerceAtLeast(1)) {
            val out = current.clone()
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    if (!current[row + x]) continue
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val nrow = ny * width
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx !in 0 until width) continue
                            out[nrow + nx] = true
                        }
                    }
                }
            }
            current = out
        }
        return current
    }

    private fun flattenOutput(raw: Any): FloatArray? {
        val batch = raw as? Array<*> ?: return null
        val channels = batch.firstOrNull() as? Array<*> ?: return null
        if (channels.size < 3) return null

        val hw = MODEL_SIZE * MODEL_SIZE
        val out = FloatArray(3 * hw)
        for (c in 0 until 3) {
            val rows = channels[c] as? Array<*> ?: return null
            if (rows.size < MODEL_SIZE) return null
            for (y in 0 until MODEL_SIZE) {
                val row = rows[y] as? FloatArray ?: return null
                if (row.size < MODEL_SIZE) return null
                System.arraycopy(row, 0, out, c * hw + y * MODEL_SIZE, MODEL_SIZE)
            }
        }
        return out
    }

    private fun decodeOutputToBitmap(output: FloatArray): Bitmap {
        val bitmap = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        val hw = MODEL_SIZE * MODEL_SIZE
        for (y in 0 until MODEL_SIZE) {
            for (x in 0 until MODEL_SIZE) {
                val idx = y * MODEL_SIZE + x
                val r = (((output[idx] + 1f) * 0.5f).coerceIn(0f, 1f) * 255f).toInt()
                val g = (((output[hw + idx] + 1f) * 0.5f).coerceIn(0f, 1f) * 255f).toInt()
                val b = (((output[2 * hw + idx] + 1f) * 0.5f).coerceIn(0f, 1f) * 255f).toInt()
                val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
    }

    private fun createSession(): OrtSession {
        val modelFile = copyAssetToCacheIfMissing(modelAssetName)
        ensureExternalDataFiles()
        return env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    private fun ensureExternalDataFiles() {
        val primary = copyAssetToCacheIfMissing(externalDataAssetName, required = false)
        val legacy = File(context.cacheDir, legacyExternalDataName)
        if (!legacy.exists() && primary.exists()) {
            runCatching {
                primary.inputStream().use { input ->
                    FileOutputStream(legacy).use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure { e ->
                AppLogger.log("MiganInpainter", "Failed to create legacy external data copy", e)
            }
        }
        if (!primary.exists()) {
            copyAssetToCacheIfMissing(legacyExternalDataName, required = false)
        }
    }

    private fun copyAssetToCacheIfMissing(assetName: String, required: Boolean = true): File {
        val file = File(context.cacheDir, assetName)
        if (file.exists()) return file
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            if (required) {
                throw e
            }
            AppLogger.log("MiganInpainter", "Optional asset not found: $assetName", e)
        }
        return file
    }

    companion object {
        private const val MODEL_SIZE = 512
        private const val PRE_MODEL_DILATE_ITERATIONS = 2
    }
}
