package com.manga.translate.network

import com.manga.translate.settings.ModelContextSettings
import com.manga.translate.translation.TextTokenEstimator
import com.manga.translate.translation.Utf8TextTokenEstimator
import kotlin.math.ceil
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONObject

internal data class TextInputUsage(val tokens: Int, val modelVersion: String)

/** Bounded, process-local feedback for successful, direct, pure-text requests. */
internal class TextUsageCalibration {
    enum class Task { TRANSLATION, BUBBLE_TRANSLATION, GLOSSARY }

    data class Scope(
        val fingerprint: ByteString,
        val task: Task
    )

    data class Composition(val hasGlossary: Boolean, val hasSynopsis: Boolean)

    private data class Key(val scope: Scope, val composition: Composition, val sizeBand: Int)
    private class Estimate(val modelVersion: String, var count: Int = 0, var factor: Double = 1.0)

    private val estimates = object : LinkedHashMap<Key, Estimate>(MAX_SCOPES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Estimate>?): Boolean =
            size > MAX_SCOPES
    }

    fun session(
        context: ModelContextSettings,
        task: Task,
        promptAsset: String,
        template: JSONObject,
        composition: () -> Composition
    ): Session {
        val deployment = context.identity
        val fingerprint = JSONArray()
            .put(deployment.endpoint).put(deployment.apiFormat).put(deployment.modelName)
            .put(deployment.providerId).put(context.profileName).put(promptAsset).put(template)
            .toString().encodeUtf8().sha256()
        return Session(Scope(fingerprint, task), composition)
    }

    inner class Session internal constructor(private val scope: Scope, private val composition: () -> Composition) {
        private fun key(bytes: Long) = Key(scope, composition(), java.lang.Long.numberOfLeadingZeros(bytes))

        val estimator = TextTokenEstimator { text ->
            val bytes = Utf8TextTokenEstimator.estimate(text)
            if (bytes < MIN_REQUEST_BYTES) bytes else ceil(bytes * inputFactor(key(bytes))).toLong()
        }

        fun observer(bytes: Long): ((TextInputUsage) -> Unit)? {
            if (bytes < MIN_REQUEST_BYTES) return null
            val key = key(bytes)
            return { usage -> record(key, bytes, usage) }
        }
    }

    @Synchronized
    private fun inputFactor(key: Key): Double =
        estimates[key]?.takeIf { it.count >= MIN_SAMPLES }?.factor ?: 1.0

    @Synchronized
    private fun record(key: Key, bytes: Long, usage: TextInputUsage) {
        val ratio = usage.tokens.toDouble() / bytes
        // Keep extreme high usage as a bounded signal; very low counts can indicate partial billing.
        if (ratio < 0.01) return
        val estimate = estimates[key]?.takeIf { it.modelVersion == usage.modelVersion }
            ?: Estimate(usage.modelVersion).also { estimates[key] = it }
        val target = (ratio * HEADROOM).coerceIn(MIN_FACTOR, MAX_FACTOR)
        // Downward changes are slower: a sparse sample should not quickly erase observed headroom.
        estimate.factor += (target - estimate.factor) / if (target > estimate.factor) 8 else 16
        if (estimate.count < MIN_SAMPLES) estimate.count++
    }

    private companion object {
        const val MAX_SCOPES = 64
        const val MIN_REQUEST_BYTES = 1024
        const val MIN_SAMPLES = 4
        const val HEADROOM = 1.25
        const val MIN_FACTOR = 0.875
        const val MAX_FACTOR = 1.5
    }
}
