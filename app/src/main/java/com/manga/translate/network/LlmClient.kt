package com.manga.translate.network

import android.content.Context
import android.graphics.Bitmap
import com.manga.translate.R
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.RequestPerfTrace
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.OCR_PROVIDER_ID
import com.manga.translate.settings.OcrApiSettings
import com.manga.translate.settings.SettingsStore
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 门面类：对外契约保持不变（构造器、LlmGateway 接口、全部公开方法与 companion 函数）。
 *
 * 拆分后的职责委托：
 * - HttpTransport：OkHttp 执行、超时、取消、请求头、连接配置；
 * - PayloadBuilder：请求体组装（OpenAI chat / OpenAI Responses / Gemini，含 OCR 与图片载荷）；
 * - ResponseParser：响应解析、结构化内容提取、模型列表解析；
 * - RetryHandler：重试判定与退避（重试次数来自 SettingsStore）。
 */
class LlmClient(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) : LlmGateway {
    private val appContext = context.applicationContext
    private val httpTransport = HttpTransport()
    private val payloadBuilder = PayloadBuilder(appContext, settingsStore)
    private val responseParser = ResponseParser()
    private val retryHandler = RetryHandler(settingsStore)
    private val ocrConcurrencyLimiter = DynamicConcurrencyLimiter()

    override fun isConfigured(apiSettings: ApiSettings?): Boolean {
        return (apiSettings ?: settingsStore.load()).isValid()
    }

    override fun isOcrConfigured(): Boolean {
        return settingsStore.loadOcrApiSettings().isValid()
    }

    suspend fun translate(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String = PayloadBuilder.PROMPT_CONFIG_ASSET,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RetryHandler.RETRY_COUNT,
        apiSettings: ApiSettings? = null
    ): LlmTranslationResult? =
        withContext(Dispatchers.IO) {
            val content = requestContent(
                text = text,
                glossary = glossary,
                promptAsset = promptAsset,
                useJsonPayload = true,
                requestTimeoutMs = requestTimeoutMs,
                retryCount = retryCount,
                apiSettings = apiSettings
            )
                ?: return@withContext null
            responseParser.parseTranslationContent(content)
        }

    override suspend fun translateBubbleItems(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): LlmBubbleTranslationResult? =
        withContext(Dispatchers.IO) {
            val content = requestContent(
                text = "",
                glossary = glossary,
                promptAsset = promptAsset,
                useJsonPayload = true,
                requestTimeoutMs = requestTimeoutMs,
                retryCount = retryCount,
                apiSettings = apiSettings,
                userPayloadOverride = payloadBuilder.buildBubbleItemsUserPayload(items, glossary)
            )
                ?: return@withContext null
            responseParser.parseBubbleTranslationContent(content, items.map { it.id })
        }

    override suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        requestContent(text, glossary, promptAsset, useJsonPayload = true)
            ?.let { responseParser.parseGlossaryContent(it) }
    }

    suspend fun fetchModelList(
        apiUrl: String,
        apiKey: String,
        apiFormat: ApiFormat
    ): List<String> = withContext(Dispatchers.IO) {
        requestModelList(apiUrl, apiKey, apiFormat)
    }

    override suspend fun recognizeImageText(image: Bitmap, language: TranslationLanguage): String? =
        withContext(Dispatchers.IO) {
            val ocrSettings = settingsStore.loadOcrApiSettings()
            if (!ocrSettings.isValid() || ocrSettings.useLocalOcr) {
                return@withContext null
            }
            return@withContext recognizeWithOpenAi(ocrSettings, image)
        }

    private suspend fun recognizeWithOpenAi(ocrSettings: OcrApiSettings, image: Bitmap): String? {
        val endpoint = EndpointBuilder.buildOpenAiCompatibleChatEndpoint(ocrSettings.apiUrl)
        val payload = payloadBuilder.buildImageOcrPayload(ocrSettings, image)
        val timeoutMs = ocrSettings.timeoutSeconds * 1000
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..RetryHandler.RETRY_COUNT) {
            currentCoroutineContext().ensureActive()
            val result = try {
                ocrConcurrencyLimiter.withPermit(ocrSettings.apiOcrConcurrencyLimit) {
                    httpTransport.executeRequest(
                        request = httpTransport.buildJsonPostRequest(
                            endpoint = endpoint,
                            payload = payload,
                            settings = ApiSettings(
                                apiUrl = ocrSettings.apiUrl,
                                apiKey = ocrSettings.apiKey,
                                modelName = ocrSettings.modelName,
                                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                                providerId = OCR_PROVIDER_ID
                            )
                        ),
                        timeoutMs = timeoutMs
                    ).use { response ->
                        val code = response.code
                        val body = response.body.string()
                        if (code !in 200..299) {
                            AppLogger.log(
                                "LlmClient",
                                "OCR HTTP $code on ${redactEndpoint(endpoint)}: ${responseParser.summarizeBody(body)}"
                            )
                            lastErrorCode = "HTTP $code"
                            lastErrorBody = body
                            null
                        } else {
                            val ocrContent = responseParser
                                .parseResponseContent(body, ApiFormat.OPENAI_COMPATIBLE)?.trim()
                            if (ocrContent != null &&
                                responseParser.isTruncatedResponse(body, ApiFormat.OPENAI_COMPATIBLE)
                            ) {
                                // 截断的 OCR 文本不完整，不能静默当正常结果使用；置空走重试。
                                AppLogger.log(
                                    "LlmClient",
                                    "Truncated OCR response on ${redactEndpoint(endpoint)}: " +
                                        responseParser.summarizeBody(body)
                                )
                                null
                            } else {
                                ocrContent
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("LlmClient", "OCR request failed on ${redactEndpoint(endpoint)} (attempt $attempt)", e)
                lastErrorCode = "NETWORK_ERROR"
                null
            }
            if (result != null || attempt == RetryHandler.RETRY_COUNT) {
                if (result != null) return result
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "OCR request failed on ${redactEndpoint(endpoint)}: $lastErrorCode, body=${responseParser.summarizeBody(lastErrorBody)}"
                    )
                }
                return null
            }
            retryHandler.maybeBackoffBeforeRetry(
                attempt,
                RetryPolicy(maxAttempts = RetryHandler.RETRY_COUNT, mode = RetryMode.DEFAULT),
                lastErrorCode,
                lastErrorBody
            )
        }
        return null
    }

    override suspend fun translateImageBubble(
        imageBase64: String,
        promptAsset: String,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): String? = withContext(Dispatchers.IO) {
        requestImageContent(
            imageBase64 = imageBase64,
            promptAsset = promptAsset,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            apiSettings = apiSettings
        )?.let { responseParser.parseImageTranslationContent(it) }
    }

    private suspend fun requestContent(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String,
        useJsonPayload: Boolean,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RetryHandler.RETRY_COUNT,
        apiSettings: ApiSettings? = null,
        userPayloadOverride: String? = null
    ): String? {
        val settings = apiSettings ?: settingsStore.load()
        if (!settings.isValid()) return null
        val selectedModel = settings.modelName.trim()
        val userPayload = userPayloadOverride ?: if (useJsonPayload) {
            payloadBuilder.buildUserPayload(text, glossary)
        } else {
            text
        }
        return executeRequestWithRetry(
            operation = "text:$promptAsset",
            promptAsset = promptAsset,
            settings = settings,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            contentLogLabel = "response",
            buildPayload = {
                payloadBuilder.buildPayload(
                    settings = settings,
                    modelName = selectedModel,
                    promptAsset = promptAsset,
                    apiFormat = settings.apiFormat,
                    userPayload = userPayload
                )
            }
        )
    }

    private suspend fun requestImageContent(
        imageBase64: String,
        promptAsset: String,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RetryHandler.RETRY_COUNT,
        apiSettings: ApiSettings? = null
    ): String? {
        val settings = apiSettings ?: settingsStore.load()
        if (!settings.isValid()) return null
        val selectedModel = settings.modelName.trim()
        return executeRequestWithRetry(
            operation = "image:$promptAsset",
            promptAsset = promptAsset,
            settings = settings,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            contentLogLabel = "image response",
            buildPayload = {
                payloadBuilder.buildImageTranslationPayload(
                    settings = settings,
                    modelName = selectedModel,
                    imageBase64 = imageBase64,
                    promptAsset = promptAsset,
                    apiFormat = settings.apiFormat
                )
            },
            sanitizePayloadForLog = { payloadBuilder.sanitizeModelIoForLog(it) }
        )
    }

    /**
     * 文本 / 图片两条请求路径的公共实现，封装两者完全重复的逻辑：
     * 重试循环、HTTP 调用、异常处理、RequestPerfTrace 追踪、
     * lastErrorCode / lastErrorBody / lastResponseException 状态管理以及 Model I/O 日志。
     *
     * 两条路径的差异全部通过参数注入：
     * - [operation]：RequestPerfTrace 的操作标识（"text:..." / "image:..."）；
     * - [promptAsset]：用于 "Model input" 日志；
     * - [contentLogLabel]：日志中的内容称谓（"response" / "image response"），
     *   决定 "Empty or invalid ... content" 与 "... invalid on" 两条日志的前缀；
     * - [buildPayload]：请求体构建；
     * - [sanitizePayloadForLog]：Model I/O 日志的脱敏函数（null 表示原样输出）。
     */
    private suspend fun executeRequestWithRetry(
        operation: String,
        promptAsset: String,
        settings: ApiSettings,
        requestTimeoutMs: Int?,
        retryCount: Int,
        contentLogLabel: String,
        buildPayload: () -> JSONObject,
        sanitizePayloadForLog: ((String) -> String)? = null
    ): String? {
        val selectedModel = settings.modelName.trim()
        val endpoint = EndpointBuilder.buildEndpoint(settings, selectedModel)
        val payload = buildPayload()
        val sanitizeForLog: (String) -> String = sanitizePayloadForLog ?: { it }
        val logModelIo = settingsStore.loadModelIoLogging()
        if (logModelIo) {
            AppLogger.log("LlmClient", "Model input ($promptAsset): ${sanitizeForLog(payload.toString())}")
            AppLogger.log("LlmClient", "Selected model: $selectedModel")
        }
        val timeoutMs = requestTimeoutMs?.coerceAtLeast(1_000) ?: settingsStore.loadApiTimeoutMs()
        val retryPolicy = retryHandler.buildRetryPolicy(retryCount)
        val retries = retryPolicy.maxAttempts
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        var lastResponseException: LlmResponseException? = null
        val requestTrace = RequestPerfTrace(
            tag = "LlmClient",
            operation = operation,
            enabled = logModelIo
        )
        for (attempt in 1..retries) {
            currentCoroutineContext().ensureActive()
            lastResponseException = null
            requestTrace.beginAttempt()
            val result = try {
                httpTransport.executeRequest(
                    request = httpTransport.buildJsonPostRequest(endpoint, payload, settings),
                    timeoutMs = timeoutMs
                ).use { response ->
                    val code = response.code
                    requestTrace.recordStatus(code.toString())
                    val body = response.body.string()
                    if (code !in 200..299) {
                        AppLogger.log(
                            "LlmClient",
                            "HTTP $code on ${redactEndpoint(endpoint)}: ${responseParser.summarizeBody(body)}"
                        )
                        lastErrorCode = "HTTP $code"
                        lastErrorBody = body
                        null
                    } else {
                        // 先检测截断：截断但内容可解析的响应也必须报错，避免静默返回不完整译文。
                        // 截断优先于空内容判定，给用户更准确的错误原因（达到 max token 上限）。
                        val truncated = responseParser.isTruncatedResponse(body, settings.apiFormat)
                        val content = responseParser.parseResponseContent(body, settings.apiFormat)
                        if (truncated) {
                            AppLogger.log(
                                "LlmClient",
                                "Truncated $contentLogLabel on ${redactEndpoint(endpoint)}: " +
                                    responseParser.summarizeBody(body)
                            )
                            requestTrace.recordStatus(LlmErrorCode.ResponseTruncated.value)
                            lastResponseException = LlmResponseException(
                                errorCode = LlmErrorCode.ResponseTruncated,
                                responseContent = appContext.getString(R.string.model_response_truncated)
                            )
                        } else if (content == null) {
                            AppLogger.log(
                                "LlmClient",
                                "Empty or invalid $contentLogLabel content from ${redactEndpoint(endpoint)}"
                            )
                            lastResponseException = LlmResponseException(
                                errorCode = LlmErrorCode.InvalidResponse,
                                responseContent = body.ifBlank {
                                    appContext.getString(R.string.model_response_empty_content)
                                }
                            )
                        } else if (logModelIo) {
                            AppLogger.log("LlmClient", "Model output: ${sanitizeForLog(content)}")
                        }
                        content
                    }
                }
            } catch (e: SocketTimeoutException) {
                AppLogger.log("LlmClient", "Request timeout on ${redactEndpoint(endpoint)} (attempt $attempt)", e)
                requestTrace.recordStatus(LlmErrorCode.Timeout.value)
                lastErrorCode = LlmErrorCode.Timeout.value
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("LlmClient", "Request failed on ${redactEndpoint(endpoint)} (attempt $attempt)", e)
                requestTrace.recordStatus(LlmErrorCode.NetworkError.value)
                lastErrorCode = LlmErrorCode.NetworkError.value
                null
            }
            val responseException = lastResponseException
            if (responseException != null) {
                // Invalid / empty content is retried by the caller's
                // executeWithModelResponseRetries; retrying here too would multiply the
                // attempt counts into up to retries x silentRetries real HTTP requests.
                requestTrace.logSummary("invalid_response")
                // 首字母大写以保持原日志格式："Response invalid on ..." / "Image response invalid on ..."
                AppLogger.log(
                    "LlmClient",
                    "${contentLogLabel.replaceFirstChar { it.uppercaseChar() }} invalid on ${redactEndpoint(endpoint)}: " +
                        responseParser.summarizeBody(responseException.responseContent)
                )
                throw responseException
            }
            if (result != null || attempt == retries) {
                if (result != null) {
                    requestTrace.logSummary("success")
                    return result
                }
                if (lastErrorCode != null) {
                    requestTrace.logSummary("failed")
                    AppLogger.log(
                        "LlmClient",
                        "Request failed on ${redactEndpoint(endpoint)}: $lastErrorCode, body=${responseParser.summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(LlmErrorCode.from(lastErrorCode), lastErrorBody)
                }
                requestTrace.logSummary("empty")
                return null
            }
            retryHandler.maybeBackoffBeforeRetry(attempt, retryPolicy, lastErrorCode, lastErrorBody)
        }
        return null
    }

    private suspend fun requestModelList(
        apiUrl: String,
        apiKey: String,
        apiFormat: ApiFormat
    ): List<String> {
        if (apiUrl.isBlank()) {
            throw LlmRequestException("MISSING_URL")
        }
        val endpoint = when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE,
            ApiFormat.OPENAI_RESPONSES -> EndpointBuilder.buildOpenAiCompatibleModelsEndpoint(apiUrl)
            ApiFormat.GEMINI -> EndpointBuilder.buildGeminiModelsEndpoint(apiUrl, apiKey)
        }
        val timeoutMs = settingsStore.loadApiTimeoutMs()
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..RetryHandler.RETRY_COUNT) {
            currentCoroutineContext().ensureActive()
            val result = try {
                httpTransport.executeRequest(
                    request = httpTransport.buildModelListRequest(endpoint, apiKey, apiFormat),
                    timeoutMs = timeoutMs
                ).use { response ->
                    val code = response.code
                    val body = response.body.string()
                    if (code !in 200..299) {
                        AppLogger.log(
                            "LlmClient",
                            "Model list HTTP $code on ${redactEndpoint(endpoint)}: ${responseParser.summarizeBody(body)}"
                        )
                        lastErrorCode = "HTTP $code"
                        lastErrorBody = body
                        null
                    } else {
                        val models = responseParser.parseModelList(body, apiFormat)
                        if (models.isEmpty()) {
                            lastErrorCode = "EMPTY_RESPONSE"
                            lastErrorBody = body
                        }
                        models
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log(
                    "LlmClient",
                    "Model list request failed on ${redactEndpoint(endpoint)} (attempt $attempt)",
                    e
                )
                lastErrorCode = "NETWORK_ERROR"
                null
            }
            if (!result.isNullOrEmpty() || attempt == RetryHandler.RETRY_COUNT) {
                if (!result.isNullOrEmpty()) {
                    return result
                }
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "Model list failed on ${redactEndpoint(endpoint)}: $lastErrorCode, body=${responseParser.summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(lastErrorCode, lastErrorBody)
                }
                return emptyList()
            }
            retryHandler.maybeBackoffBeforeRetry(
                attempt,
                RetryPolicy(maxAttempts = RetryHandler.RETRY_COUNT, mode = RetryMode.DEFAULT),
                lastErrorCode,
                lastErrorBody
            )
        }
        return emptyList()
    }

    private fun redactEndpoint(endpoint: String): String =
        EndpointBuilder.redactEndpoint(endpoint)

    companion object {
        internal fun buildOpenAiCompatibleChatEndpoint(baseUrl: String): String {
            return EndpointBuilder.buildOpenAiCompatibleChatEndpoint(baseUrl)
        }

        internal fun buildOpenAiResponsesApiEndpoint(baseUrl: String): String {
            return EndpointBuilder.buildOpenAiResponsesApiEndpoint(baseUrl)
        }

        internal fun buildOpenAiCompatibleModelsEndpoint(baseUrl: String): String {
            return EndpointBuilder.buildOpenAiCompatibleModelsEndpoint(baseUrl)
        }

        fun reservedRequestKeys(apiFormat: ApiFormat): Set<String> {
            return ReservedRequestKeys.forFormat(apiFormat)
        }
    }

    override fun resourceContext(): Context = appContext
}
