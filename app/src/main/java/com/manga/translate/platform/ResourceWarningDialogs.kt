package com.manga.translate.platform

import android.content.Context
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R

internal object ResourceWarningDialogs {
    fun createBuilder(context: Context, assessment: ResourceAssessment): AlertDialog.Builder {
        val available = Formatter.formatShortFileSize(
            context,
            assessment.snapshot.effectiveAvailableBytes
        )
        val estimated = assessment.estimatedPeakBytes?.let {
            Formatter.formatShortFileSize(context, it)
        } ?: context.getString(R.string.memory_size_unknown)
        val requested = assessment.requestedConcurrency
        val recommended = assessment.recommendedConcurrency
        val message = if (requested != null && recommended != null) {
            context.getString(
                R.string.resource_concurrency_warning_message,
                available,
                estimated,
                assessment.snapshot.cpuCoreCount,
                requested,
                recommended
            )
        } else if (assessment.snapshot.effectiveAvailableBytes > 0L) {
            context.getString(R.string.memory_warning_message, available, estimated)
        } else {
            context.getString(R.string.memory_warning_message_unavailable)
        }
        return AlertDialog.Builder(context)
            .setTitle(R.string.memory_warning_title)
            .setScrollableMessage(message)
    }
}
