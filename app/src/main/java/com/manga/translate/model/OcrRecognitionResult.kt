package com.manga.translate.model


sealed interface OcrRecognitionResult {
    data class Success(val text: String) : OcrRecognitionResult
    data class Failure(val error: Throwable) : OcrRecognitionResult
}

class OcrRecognitionException(cause: Throwable) : Exception("OCR recognition failed", cause)

/** Empty text is valid only after successful recognition. */
fun OcrRecognitionResult.textOrThrow(): String {
    return when (this) {
        is OcrRecognitionResult.Success -> text
        is OcrRecognitionResult.Failure -> throw OcrRecognitionException(error)
    }
}

