package com.manga.translate.network

import com.manga.translate.model.ApiFormat
import com.manga.translate.settings.ApiSettings
import java.util.LinkedHashMap
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * 纯传输职责：OkHttp 请求头组装、执行、超时、取消与按超时缓存连接配置的客户端池。
 *
 * 响应消费完成后才恢复协程，确保取消覆盖响应体读取。
 */
internal class HttpTransport(httpClient: OkHttpClient = OkHttpClient()) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    // Business-level limiters own concurrency, as they did with synchronous calls.
    // Do not silently introduce OkHttp's default five-request per-host queue.
    private val baseHttpClient = httpClient.newBuilder()
        .dispatcher(Dispatcher(httpClient.dispatcher.executorService).apply {
            maxRequests = Int.MAX_VALUE
            maxRequestsPerHost = Int.MAX_VALUE
        })
        .build()
    private val httpClientCache = object : LinkedHashMap<Pair<Int, Int>, OkHttpClient>(MAX_CACHED_HTTP_CLIENTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, OkHttpClient>?): Boolean {
            return size > MAX_CACHED_HTTP_CLIENTS
        }
    }

    /** JSON POST 请求：统一 Content-Type 头，OpenAI 兼容/Responses 格式附加 Bearer 鉴权头。 */
    fun buildJsonPostRequest(
        endpoint: String,
        payload: JSONObject,
        settings: ApiSettings
    ): Request {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
        if (settings.apiFormat.usesOpenAiAuth) {
            requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        return requestBuilder.build()
    }

    /** 模型列表 GET 请求：仅 OpenAI 兼容/Responses 格式且 apiKey 非空时附加 Bearer 鉴权头。 */
    fun buildModelListRequest(endpoint: String, apiKey: String, apiFormat: ApiFormat): Request {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .get()
            .header("Content-Type", "application/json")
        if (apiFormat.usesOpenAiAuth && apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        return requestBuilder.build()
    }

    suspend fun <T> executeRequest(
        request: Request,
        timeoutMs: Int,
        writeTimeoutMs: Int = timeoutMs,
        consume: (Response) -> T
    ): T {
        val client = getOrBuildClient(timeoutMs, writeTimeoutMs)
        return executeCallCancellable(client.newCall(request), consume)
    }

    internal fun getOrBuildClient(timeoutMs: Int, writeTimeoutMs: Int = timeoutMs): OkHttpClient {
        return synchronized(httpClientCache) {
            httpClientCache.getOrPut(timeoutMs to writeTimeoutMs) {
                baseHttpClient.newBuilder()
                    .connectTimeout(minOf(timeoutMs, RequestTimeoutPolicy.CONNECT_TIMEOUT_MS).toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .writeTimeout(minOf(timeoutMs, writeTimeoutMs).toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()
            }
        }
    }

    private suspend fun <T> executeCallCancellable(call: Call, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                // execute() would keep the suspending call on the stack during a
                // blocked DNS lookup, preventing its timeout from returning.
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (continuation.isActive) {
                                    val result = consume(it)
                                    if (continuation.isActive) continuation.resume(result)
                                }
                            }
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(t)
                        }
                    }
                })
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
}

/** 动态并发限流：OCR 请求并发数来自 OcrApiSettings，默认至少 1。 */
internal class DynamicConcurrencyLimiter {
    private val lock = Any()
    private var active = 0
    private var released = CompletableDeferred<Unit>()

    suspend fun <T> withPermit(limit: Int, block: suspend () -> T): T {
        val normalizedLimit = limit.coerceAtLeast(1)
        while (true) {
            currentCoroutineContext().ensureActive()
            val signal = synchronized(lock) {
                if (active < normalizedLimit) {
                    active++
                    null
                } else {
                    released
                }
            }
            if (signal == null) break
            signal.await()
        }
        return try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            // Releasing must not suspend: a cancelled caller must still return its permit.
            val signal = synchronized(lock) {
                active--
                released.also { released = CompletableDeferred() }
            }
            signal.complete(Unit)
        }
    }
}

private const val MAX_CACHED_HTTP_CLIENTS = 4
