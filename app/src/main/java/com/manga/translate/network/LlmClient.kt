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
import com.manga.translate.translation.FullTranslationSynopsis
import com.manga.translate.translation.TextBudgetUnit
import com.manga.translate.translation.TextBatchPlan
import com.manga.translate.settings.SettingsStore
import java.io.InterruptedIOException
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
        return settingsStore.loadResolvedOcrApiSettings().isValid()
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
        apiSettings: ApiSettings?,
        glossaryProcessingEnabled: Boolean
    ): LlmBubbleTranslationResult? =
        withContext(Dispatchers.IO) {
            val settings = apiSettings ?: settingsStore.load()
            if (!settings.isValid()) return@withContext null
            if (items.isEmpty()) return@withContext LlmBubbleTranslationResult(emptyList(), emptyMap())
            require(items.map { it.id }.distinct().size == items.size)
            val grouped = items.groupBy { it.sourceId }
            require(grouped.values.flatten() == items) { "Source pages must be contiguous" }
            val units = grouped.map { (source, page) ->
                TextBudgetUnit(source, payloadBuilder.buildBubbleItemsUserPayload(page, emptyMap()))
            }
            val synopsis = if (promptAsset == "prompts/llm_prompts_FullTrans.json")
                currentCoroutineContext()[FullTranslationSynopsis]?.text else null
            val names = LinkedHashMap(glossary)
            val additions = linkedMapOf<String, String>()
            val results = runBudgetedText(units, settings, promptAsset, requestTimeoutMs, retryCount,
                glossaryProcessingEnabled, 8192,
                buildUser = { batch -> payloadBuilder.buildBubbleItemsUserPayload(
                    batch.flatMap { grouped.getValue(it.sourceId) }, names, synopsis) },
                parse = { content, batch -> validateTextBubbleIds(
                    responseParser.parseBubbleTranslationContent(content,
                        batch.flatMap { grouped.getValue(it.sourceId) }.map { it.id }),
                    batch.flatMap { grouped.getValue(it.sourceId) }.map { it.id }, content) },
                onSuccess = { result ->
                    if (glossaryProcessingEnabled) result.glossaryUsed.forEach { (key, value) ->
                        if (!names.containsKey(key)) { names[key] = value; additions[key] = value }
                    }
                })
            LlmBubbleTranslationResult(results.flatMap { it.items }, additions)
        }

    override suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String>? = extractGlossaryPages(
        listOf(TextBudgetUnit("page", text)), glossary, promptAsset)

    /** Each unit is one whole image. Successful glossary batches are merged before the next plan. */
    internal suspend fun extractGlossaryPages(
        pages: List<TextBudgetUnit>,
        glossary: Map<String, String>,
        promptAsset: String,
        onSuccess: suspend (Map<String, String>) -> Unit = {}
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val settings = settingsStore.load()
        if (!settings.isValid()) return@withContext null
        val names = LinkedHashMap(glossary)
        val additions = linkedMapOf<String, String>()
        val synopsis = currentCoroutineContext()[FullTranslationSynopsis]
        val pending = pages.filterNot { synopsis?.isComplete(it.sourceId) == true }
        runBudgetedText(pending, settings, promptAsset, null, RetryHandler.RETRY_COUNT, true,
            if (synopsis != null) 8192 else 4096,
            buildUser = { batch -> payloadBuilder.buildUserPayload(
                batch.joinToString("\n") { it.serializedText }, names, synopsis?.text) },
            parse = { content, batch -> Triple(responseParser.parseGlossaryContentStrict(content),
                if (synopsis != null) responseParser.parseSynopsis(content) else null,
                batch.map { it.sourceId }) },
            onSuccess = { (extracted, summary, sourceIds) ->
                val fresh = extracted.filterKeys { !names.containsKey(it) }
                onSuccess(fresh)
                names.putAll(fresh)
                additions.putAll(fresh)
                synopsis?.commit(sourceIds, requireNotNull(summary))
            })
        additions
    }

    private fun validateTextBubbleIds(
        result: LlmBubbleTranslationResult, requested: List<Int>, content: String
    ): LlmBubbleTranslationResult {
        val returned = result.items.map { it.id }
        if (returned.size != returned.toSet().size || returned.toSet() != requested.toSet()) {
            throw LlmResponseException(LlmErrorCode.MissingTranslationItems,
                "Requested IDs do not exactly match response IDs. response=${content.take(600)}")
        }
        return result
    }

    private suspend fun <T> runBudgetedText(
        units: List<TextBudgetUnit>, settings: ApiSettings, promptAsset: String,
        requestTimeoutMs: Int?, retryCount: Int, glossaryProcessingEnabled: Boolean,
        defaultOutput: Int, buildUser: (List<TextBudgetUnit>) -> String,
        parse: (String, List<TextBudgetUnit>) -> T,
        onSuccess: suspend (T) -> Unit
    ): List<T> {
        if (units.isEmpty()) return emptyList()
        val capability = settingsStore.loadModelContextSettings(settings)
        val template = BudgetedTextTemplate(payloadBuilder.buildPayload(settings, settings.modelName.trim(),
            promptAsset, settings.apiFormat, "", glossaryProcessingEnabled), settings.apiFormat, capability, defaultOutput)
        val timeout = requestTimeoutMs ?: RequestTimeoutPolicy.callTimeoutMs(settingsStore.loadApiTimeoutMs(), units.size)
        val policy = retryHandler.buildRetryPolicy(retryCount)
        val context = currentCoroutineContext()[RequestBudgetContext] ?: RequestBudgetContext()
        val deadline = context.getOrStart(timeout, policy.maxAttempts)
        val attempts = currentCoroutineContext()[TextAttemptBudget] ?: TextAttemptBudget(
            ((units.size.toLong() * 2 - 1) * maxOf(3, policy.maxAttempts)).coerceAtMost(512).toInt())
        return withContext(context + attempts) {
            deadline.run {
                executeBudgetedText(units, template, buildUser,
                    send = { payload, batch ->
                        var parsed: T? = null
                        executeRequestWithRetry("text:$promptAsset", promptAsset, settings, timeout,
                            policy.maxAttempts, "response", { payload },
                            validateContent = { parsed = parse(it, batch) }, retryPolicyOverride = policy)
                            ?: throw LlmResponseException(LlmErrorCode.EmptyResponse,
                                appContext.getString(R.string.model_response_empty_content))
                        requireNotNull(parsed)
                    }, onSuccess = onSuccess)
            }
        }
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
            val ocrSettings = settingsStore.loadResolvedOcrApiSettings()
            if (!ocrSettings.hasApiCredentials()) {
                return@withContext null
            }
            return@withContext recognizeWithOpenAi(ocrSettings, image)
        }

    override suspend fun recognizePageRegions(image: Bitmap, requestedIds: List<Int>): Map<Int, String> =
        withContext(Dispatchers.IO) {
            require(requestedIds.isNotEmpty() && requestedIds.size == requestedIds.toSet().size)
            val settings = settingsStore.loadResolvedOcrApiSettings()
            if (!settings.hasApiCredentials()) throw LlmResponseException(
                LlmErrorCode.MissingApiSettings, "OCR API unavailable")
            val content = recognizeWithOpenAi(settings, image, requestedIds)
                ?: throw LlmResponseException(LlmErrorCode.EmptyResponse, "Empty page OCR response")
            responseParser.parseImageItemsContent(content, requestedIds).items.associate {
                it.id to it.translation
            }
        }

    private suspend fun recognizeWithOpenAi(ocrSettings: OcrApiSettings, image: Bitmap,
        requestedIds: List<Int>? = null): String? {
        val endpoint = EndpointBuilder.buildOpenAiCompatibleChatEndpoint(ocrSettings.apiUrl)
        val payload = payloadBuilder.buildImageOcrPayload(ocrSettings, image, requestedIds)
        val timeoutMs = ocrSettings.timeoutSeconds * 1000
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        val retryPolicy = RetryPolicy(RetryHandler.RETRY_COUNT, RetryMode.DEFAULT)
        for (attempt in 1..retryPolicy.maxAttempts) {
            currentCoroutineContext().ensureActive()
            lastErrorCode = null
            lastErrorBody = null
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
                    ) { response ->
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
                                // 截断的 OCR 文本不完整，不能静默当正常结果使用。
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
            if (result != null || !retryHandler.canRetry(attempt, retryPolicy, lastErrorCode, lastErrorBody)) {
                if (result != null) return result
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "OCR request failed on ${redactEndpoint(endpoint)}: $lastErrorCode, body=${responseParser.summarizeBody(lastErrorBody)}"
                    )
                }
                return null
            }
            retryHandler.backoffBeforeRetry(attempt, retryPolicy, lastErrorCode)
        }
        return null
    }

    override suspend fun translateImageBubble(
        imageBase64: String,
        promptAsset: String,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): LlmTranslationResult? = withContext(Dispatchers.IO) {
        requestImageContent(
            imageBase64 = imageBase64,
            promptAsset = promptAsset,
            glossary = glossary,
            glossaryProcessingEnabled = glossaryProcessingEnabled,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            apiSettings = apiSettings
        )?.let { content ->
            responseParser.parseImageTranslationContent(content)?.let { translation ->
                LlmTranslationResult(
                    translation,
                    if (glossaryProcessingEnabled && content.trim().let { it.startsWith("{") || it.startsWith("```") }) {
                        responseParser.parseGlossaryContent(content)
                    } else emptyMap()
                )
            }
        }
    }

    override suspend fun translateImageItems(
        imageBase64: String,
        requestedIds: List<Int>,
        promptAsset: String,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int,
        retryCount: Int,
        apiSettings: ApiSettings
    ): LlmBubbleTranslationResult? = withContext(Dispatchers.IO) {
        requestImageItems(listOf(imageBase64), requestedIds, promptAsset, glossary,
            glossaryProcessingEnabled, requestTimeoutMs, retryCount, apiSettings)
    }

    override suspend fun translateMultiImageItems(
        imagesBase64: List<String>,
        requestedIds: List<Int>,
        promptAsset: String,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int,
        retryCount: Int,
        apiSettings: ApiSettings
    ): LlmBubbleTranslationResult? = withContext(Dispatchers.IO) {
        requestImageItems(imagesBase64, requestedIds, promptAsset, glossary,
            glossaryProcessingEnabled, requestTimeoutMs, retryCount, apiSettings)
    }

    private suspend fun requestImageItems(
        imagesBase64: List<String>,
        requestedIds: List<Int>,
        promptAsset: String,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int,
        retryCount: Int,
        apiSettings: ApiSettings
    ): LlmBubbleTranslationResult? {
        var parsed: LlmBubbleTranslationResult? = null
        requestImageContent(imagesBase64.first(), promptAsset, glossary, glossaryProcessingEnabled,
            requestTimeoutMs, retryCount, apiSettings, requestedIds, imagesBase64,
            validateContent = { parsed = responseParser.parseImageItemsContent(it, requestedIds) })
            ?: return null
        return requireNotNull(parsed).let { result ->
            if (glossaryProcessingEnabled) result else result.copy(glossaryUsed = emptyMap())
        }
    }

    private suspend fun requestContent(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String,
        useJsonPayload: Boolean,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RetryHandler.RETRY_COUNT,
        apiSettings: ApiSettings? = null,
        userPayloadOverride: String? = null,
        glossaryProcessingEnabled: Boolean = true
    ): String? {
        val settings = apiSettings ?: settingsStore.load()
        if (!settings.isValid()) return null
        val selectedModel = settings.modelName.trim()
        val userPayload = userPayloadOverride ?: if (useJsonPayload) {
            payloadBuilder.buildUserPayload(text, glossary)
        } else {
            text
        }
        val payloadTemplate = payloadBuilder.buildPayload(
            settings, selectedModel, promptAsset, settings.apiFormat, "", glossaryProcessingEnabled)
        val capability = settingsStore.loadModelContextSettings(settings)
        val template = BudgetedTextTemplate(payloadTemplate, settings.apiFormat, capability, 4096)
        val payload = template.payload(userPayload)
        val plan = template.check(payload)
        if (plan is TextBatchPlan.Rejected) throw LlmResponseException(
            LlmErrorCode.Custom("TEXT_BUDGET_EXCEEDED"),
            "${plan.reason}: input=${plan.budget.inputTokens}, available=${plan.budget.availableInputTokens}",
            retryable = false)
        val timeout = requestTimeoutMs ?: RequestTimeoutPolicy.callTimeoutMs(settingsStore.loadApiTimeoutMs())
        val retryPolicy = retryHandler.buildRetryPolicy(retryCount)
        val budgetContext = currentCoroutineContext()[RequestBudgetContext] ?: RequestBudgetContext()
        val deadline = budgetContext.getOrStart(timeout, retryPolicy.maxAttempts)
        val attempts = currentCoroutineContext()[TextAttemptBudget]
            ?: TextAttemptBudget(retryPolicy.maxAttempts * 3)
        return withContext(budgetContext + attempts) {
            deadline.run {
                executeRequestWithRetry(
                    operation = "text:$promptAsset", promptAsset = promptAsset, settings = settings,
                    requestTimeoutMs = timeout, retryCount = retryCount, contentLogLabel = "response",
                    buildPayload = { payload }, retryPolicyOverride = retryPolicy
                )
            }
        }
    }

    private suspend fun requestImageContent(
        imageBase64: String,
        promptAsset: String,
        glossary: Map<String, String> = emptyMap(),
        glossaryProcessingEnabled: Boolean = false,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RetryHandler.RETRY_COUNT,
        apiSettings: ApiSettings? = null,
        requestedIds: List<Int>? = null,
        imagesBase64: List<String> = listOf(imageBase64),
        validateContent: ((String) -> Unit)? = null
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
                    glossary = glossary,
                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                    apiFormat = settings.apiFormat,
                    requestedIds = requestedIds,
                    imagesBase64 = imagesBase64
                )
            },
            sanitizePayloadForLog = { payloadBuilder.sanitizeModelIoForLog(it) },
            validateContent = validateContent
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
     * - [validateContent]：结构化图片响应在记录成功前完成 JSON 和 ID 校验。
     */
    private suspend fun executeRequestWithRetry(
        operation: String,
        promptAsset: String,
        settings: ApiSettings,
        requestTimeoutMs: Int?,
        retryCount: Int,
        contentLogLabel: String,
        buildPayload: () -> JSONObject,
        sanitizePayloadForLog: ((String) -> String)? = null,
        validateContent: ((String) -> Unit)? = null,
        retryPolicyOverride: RetryPolicy? = null
    ): String? {
        val callTimeoutMs = RequestTimeoutPolicy.callTimeoutMs(requestTimeoutMs ?: settingsStore.loadApiTimeoutMs())
        val retryPolicy = retryPolicyOverride ?: retryHandler.buildRetryPolicy(retryCount)
        val budget = (currentCoroutineContext()[RequestBudgetContext] ?: RequestBudgetContext())
            .getOrStart(callTimeoutMs, retryPolicy.maxAttempts)
        return budget.run {
            executeRequestAttempts(operation, promptAsset, settings, callTimeoutMs, retryPolicy,
                contentLogLabel, buildPayload, sanitizePayloadForLog, budget, validateContent)
        }
    }

    private suspend fun executeRequestAttempts(
        operation: String,
        promptAsset: String,
        settings: ApiSettings,
        timeoutMs: Int,
        retryPolicy: RetryPolicy,
        contentLogLabel: String,
        buildPayload: () -> JSONObject,
        sanitizePayloadForLog: ((String) -> String)?,
        budget: RequestTimeoutBudget,
        validateContent: ((String) -> Unit)?
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
            lastErrorCode = null
            lastErrorBody = null
            lastResponseException = null
            val attemptTimeoutMs = budget.attemptTimeoutMs(timeoutMs)
            currentCoroutineContext()[TextAttemptBudget]?.consume()
            requestTrace.beginAttempt()
            val result = try {
                httpTransport.executeRequest(
                    request = httpTransport.buildJsonPostRequest(endpoint, payload, settings),
                    timeoutMs = attemptTimeoutMs,
                    writeTimeoutMs = if (operation.startsWith("text:")) RequestTimeoutPolicy.TEXT_WRITE_TIMEOUT_MS else timeoutMs
                ) { response ->
                    val code = response.code
                    requestTrace.recordStatus(code.toString())
                    val body = response.body.string()
                    if (code !in 200..299) {
                        AppLogger.log(
                            "LlmClient",
                            "HTTP $code on ${redactEndpoint(endpoint)}: ${responseParser.summarizeBody(body)}"
                        )
                        if (operation.startsWith("text:") && isContextOverflow(code, body)) {
                            throw LlmResponseException(LlmErrorCode.Custom("CONTEXT_LENGTH_EXCEEDED"), body)
                        }
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
                        } else {
                            if (logModelIo) {
                                AppLogger.log("LlmClient", "Model output: ${sanitizeForLog(content)}")
                            }
                            validateContent?.invoke(content)
                        }
                        content
                    }
                }
            } catch (e: LlmResponseException) {
                requestTrace.recordStatus(e.errorCode.value)
                lastResponseException = e
                null
            } catch (e: InterruptedIOException) {
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
            currentCoroutineContext().ensureActive()
            budget.requireRemainingMs()
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
            if (result != null || !retryHandler.canRetry(attempt, retryPolicy, lastErrorCode, lastErrorBody)) {
                if (result != null) {
                    requestTrace.logSummary(if (validateContent != null) "validated_response" else "response_received")
                    return result
                }
                val errorCode = lastErrorCode
                if (errorCode != null) {
                    requestTrace.logSummary("failed")
                    AppLogger.log(
                        "LlmClient",
                        "Request failed on ${redactEndpoint(endpoint)}: $lastErrorCode, body=${responseParser.summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(LlmErrorCode.from(errorCode), lastErrorBody)
                }
                requestTrace.logSummary("empty")
                return null
            }
            retryHandler.backoffBeforeRetry(attempt, retryPolicy, lastErrorCode)
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
        val retryPolicy = RetryPolicy(RetryHandler.RETRY_COUNT, RetryMode.DEFAULT)
        for (attempt in 1..retryPolicy.maxAttempts) {
            currentCoroutineContext().ensureActive()
            lastErrorCode = null
            lastErrorBody = null
            val result = try {
                httpTransport.executeRequest(
                    request = httpTransport.buildModelListRequest(endpoint, apiKey, apiFormat),
                    timeoutMs = timeoutMs
                ) { response ->
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
            if (!result.isNullOrEmpty() || !retryHandler.canRetry(attempt, retryPolicy, lastErrorCode, lastErrorBody)) {
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
            retryHandler.backoffBeforeRetry(attempt, retryPolicy, lastErrorCode)
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
