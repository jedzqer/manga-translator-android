package com.manga.translate.ocr

import android.content.Context
import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.DeviceResourceSnapshot

internal object LocalOcrConcurrency {
    private const val HARD_CAP = 8

    fun compute(snapshot: DeviceResourceSnapshot): Int =
        DeviceResourcePolicy.recommendConcurrency(
            snapshot = snapshot,
            perWorkerBytes = DeviceResourcePolicy.OCR_INSTANCE_BYTES,
            hardCap = HARD_CAP
        )

    fun compute(context: Context): Int =
        compute(DeviceResourcePolicy.readSnapshot(context))

    fun resolve(context: Context): Int = compute(context)
}
