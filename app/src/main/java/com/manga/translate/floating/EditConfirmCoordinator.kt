package com.manga.translate.floating

import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.detection.LocalModelMemoryManager
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationResult
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.network.executeWithModelResponseRetries
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * State machine and execution driver for the floating overlay "edit session"
 * confirm flow (box-select / crop / pen edit results confirmed, cancelled or
 * committed).
 *
 * States:
 *  - [State.IDLE]: no edit session in progress.
 *  - [State.EDITING]: the user is modifying bubbles (add / move / delete).
 *  - [State.CONFIRMING]: a confirm (empty-bubble translation) is in flight.
 *  - [State.COMMITTED]: the last confirm was applied as a whole (terminal until
 *    the next [beginEdit] / [reset]).
 *  - [State.CANCELLED]: the last session was rolled back as a whole (terminal
 *    until the next [beginEdit] / [reset]).
 *
 * Transitions (all of them happen on the main thread):
 *  - IDLE / COMMITTED / CANCELLED --[beginEdit]--> EDITING
 *  - EDITING --[startConfirm]--> CONFIRMING
 *  - EDITING --[cancelEdit]--> CANCELLED
 *  - CONFIRMING --[cancelEdit]--> CANCELLED
 *  - CONFIRMING --[completeConfirm]--> COMMITTED
 *  - CONFIRMING --[acknowledgeConfirmEnded]--> EDITING   (attempt ended without commit)
 *  - any --[reset]--> IDLE
 *
 * Atomicity: the outcome of a confirm is only ever applied while the machine is
 * still [State.CONFIRMING]. A [cancelEdit] (or a new [beginEdit]) moves the
 * machine out of CONFIRMING before the in-flight result is dispatched, so a
 * pending confirm result is discarded instead of being layered on top of a
 * rollback: the confirm either commits as a whole or not at all.
 *
 * The Service drives this coordinator and receives results through
 * [ConfirmEffects]; no view, window or toast logic lives here.
 */
internal class EditConfirmCoordinator(
    private val emptyBubbleCoordinator: FloatingEmptyBubbleCoordinator,
    private val settingsStore: SettingsStore,
    private val localModelMemoryManager: LocalModelMemoryManager,
    private val retryCount: Int,
    private val floatPromptAsset: String,
    private val floatVlPromptAsset: String,
    private val maxVlConcurrency: Int
) {
    enum class State { IDLE, EDITING, CONFIRMING, COMMITTED, CANCELLED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var confirmInFlight: Boolean = false
        private set

    var createBubbleModeEnabled: Boolean = false
        private set

    var editSessionDirty: Boolean = false
        private set

    private var editSessionSnapshot: TranslationResult? = null

    /** Whether an edit session is active (including a confirm in flight). */
    val isEditing: Boolean
        get() = state == State.EDITING || state == State.CONFIRMING

    /** Whether a confirm is in flight and its outcome may still be committed. */
    fun isConfirmPending(): Boolean = state == State.CONFIRMING && confirmInFlight

    /**
     * Starts a new edit session over [session], storing a deep snapshot for
     * rollback. Overwrites any previous session state (matches the legacy
     * re-entry semantics of the service).
     */
    fun beginEdit(session: TranslationResult) {
        editSessionSnapshot = session.deepCopy()
        createBubbleModeEnabled = false
        editSessionDirty = false
        state = State.EDITING
    }

    /**
     * Cancels the current edit session and returns the snapshot to restore
     * (a deep copy), or null when no snapshot was available. Works both while
     * editing and while a confirm is in flight; an in-flight confirm result is
     * then discarded by the atomicity gate instead of being applied.
     */
    fun cancelEdit(): TranslationResult? {
        if (state != State.EDITING && state != State.CONFIRMING) return null
        val restored = editSessionSnapshot?.deepCopy()
        state = State.CANCELLED
        editSessionSnapshot = null
        createBubbleModeEnabled = false
        editSessionDirty = false
        return restored
    }

    /**
     * Abandons the edit session without rollback (detection restart, session
     * cleared). The edited session data itself is left untouched by the
     * coordinator; callers decide whether to keep or replace it.
     */
    fun reset() {
        editSessionSnapshot = null
        createBubbleModeEnabled = false
        editSessionDirty = false
        confirmInFlight = false
        state = State.IDLE
    }

    /**
     * EDITING -> CONFIRMING. Guarded against double confirm and against
     * confirming when no edit session is active.
     */
    fun startConfirm(): Boolean {
        if (state != State.EDITING || confirmInFlight) return false
        confirmInFlight = true
        state = State.CONFIRMING
        return true
    }

    /** CONFIRMING -> COMMITTED. The confirm outcome was applied as a whole. */
    fun completeConfirm() {
        if (state != State.CONFIRMING) return
        state = State.COMMITTED
        confirmInFlight = false
        editSessionSnapshot = null
        createBubbleModeEnabled = false
        editSessionDirty = false
    }

    /**
     * A confirm attempt ended without being committed (timeout / VL model
     * required / API error / user abandoned the retry / detection generation
     * invalidated). CONFIRMING -> EDITING so the user can retry or cancel.
     */
    fun acknowledgeConfirmEnded() {
        confirmInFlight = false
        if (state == State.CONFIRMING) {
            state = State.EDITING
        }
    }

    fun setCreateBubbleMode(enabled: Boolean) {
        createBubbleModeEnabled = enabled && isEditing
    }

    /** Flips create-bubble mode; only meaningful while editing. */
    fun toggleCreateBubbleMode(): Boolean {
        if (state != State.EDITING) return false
        createBubbleModeEnabled = !createBubbleModeEnabled
        return true
    }

    fun setDirty(value: Boolean) {
        editSessionDirty = value
    }

    /**
     * Runs one confirm attempt atomically: translates the still-empty bubbles
     * of [session] on top of [bitmapSnapshot], then dispatches the outcome to
     * [effects] on the main thread. [effects.onCommitted] is invoked only when
     * the machine is still pending confirm at dispatch time, so a cancel or a
     * new session that intervened mid-flight makes the attempt a no-op.
     *
     * @return true when the outcome was dispatched as committed, false when the
     * attempt ended without committing (timeout / VL required / API error /
     * user abandoned / cancelled).
     */
    suspend fun confirm(
        bitmapSnapshot: Bitmap,
        session: TranslationResult,
        language: TranslationLanguage,
        effects: ConfirmEffects
    ): Boolean {
        val localModelLease = localModelMemoryManager.acquire("FloatingEdit")
        try {
            while (true) {
                try {
                    val floatingTimeoutMs =
                        settingsStore.loadFloatingTranslateApiSettings().timeoutSeconds * 1000
                    val outcome = executeWithModelResponseRetries("FloatingEdit") {
                        emptyBubbleCoordinator.process(
                            bitmap = bitmapSnapshot,
                            baseTranslation = session,
                            timeoutMs = floatingTimeoutMs,
                            retryCount = retryCount,
                            floatPromptAsset = floatPromptAsset,
                            floatVlPromptAsset = floatVlPromptAsset,
                            maxVlConcurrency = maxVlConcurrency,
                            language = language
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (outcome.requiresVlModel) {
                            effects.onRequiresVlModel()
                        } else if (outcome.timedOut) {
                            effects.onTimedOut()
                        } else {
                            effects.onCommitted(outcome.translation)
                        }
                    }
                    return true
                } catch (e: LlmResponseException) {
                    AppLogger.log("FloatingOCR", "Floating edit model response invalid", e)
                    if (!effects.awaitModelErrorRetry(e.responseContent)) {
                        return false
                    }
                } catch (e: LlmRequestException) {
                    AppLogger.log("FloatingOCR", "Floating edit request failed", e)
                    withContext(Dispatchers.Main) {
                        effects.onApiError(e.errorCode, e.responseBody)
                    }
                    return false
                }
            }
        } finally {
            localModelLease.close()
        }
    }

    /** UI/effect callbacks the Service implements; all run on the main thread. */
    interface ConfirmEffects {
        /**
         * Called on a model-response error; the user may retry (true) or
         * abandon the confirm (false). Implementations show the model error
         * dialog and suspend until the user decides.
         */
        suspend fun awaitModelErrorRetry(responseContent: String): Boolean

        fun onApiError(errorCode: LlmErrorCode, detail: String?)

        /** Confirm finished but the floating VL model is required. */
        fun onRequiresVlModel()

        /** Confirm timed out; the edit session stays editable for retry. */
        fun onTimedOut()

        /** The confirm outcome must be applied now (all-or-nothing commit). */
        fun onCommitted(translation: TranslationResult)
    }
}

private fun TranslationResult.deepCopy(): TranslationResult {
    return copy(
        bubbles = bubbles.map { bubble ->
            bubble.copy(rect = RectF(bubble.rect))
        }
    )
}
