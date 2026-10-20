package com.manga.translate.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.manga.translate.detection.BubbleDetection
import com.manga.translate.detection.DetectionTile
import com.manga.translate.detection.PageRegionDetectionResult
import com.manga.translate.detection.lineBelongsToRegion
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal data class VlPageLayout(
    val width: Int,
    val height: Int,
    val targets: List<BubbleTranslation>,
    val backgrounds: List<BubbleDetection> = emptyList(),
    val tiles: List<DetectionTile> = emptyList(),
    // Request IDs remain text-block IDs; rendering groups them by their detected balloon.
    val targetParents: Map<Int, Int> = emptyMap()
)

internal fun PageRegionDetectionResult.vlLayout(): VlPageLayout {
    val parents = allTextDetections.map { text ->
        bubbleDetections.indices.filter { lineBelongsToRegion(text.rect, bubbleDetections[it].rect) }
            .sortedWith(compareByDescending<Int> {
                val intersection = RectF(text.rect)
                if (intersection.intersect(bubbleDetections[it].rect)) intersection.width() * intersection.height() else 0f
            }.thenBy { bubbleDetections[it].rect.width() * bubbleDetections[it].rect.height() }).firstOrNull()
    }
    val seeds = allTextDetections.mapIndexed { i, text ->
        Triple(text.rect, text.maskContour, parents[i])
    } + bubbleDetections.mapIndexedNotNull { i, bubble ->
        if (i in parents) null else Triple(bubble.rect, bubble.maskContour, i)
    }
    val sorted = seeds.sortedWith(compareBy({ it.first.top }, { it.first.left }))
    return VlPageLayout(width, height, sorted.mapIndexed { id, seed ->
        BubbleTranslation.pending(id, seed.first, "",
            if (seed.third == null) BubbleSource.TEXT_DETECTOR else BubbleSource.BUBBLE_DETECTOR, seed.second)
    }, bubbleDetections, tiles, sorted.mapIndexedNotNull { id, seed -> seed.third?.let { id to it } }.toMap())
}

/** Fold text-block responses into a single rendering region per balloon. */
internal fun VlPageLayout.renderTranslations(translated: Map<Int, String>): List<BubbleTranslation> {
    return targets.groupBy { target ->
        targetParents[target.id]?.let { true to it } ?: (false to target.id)
    }.mapNotNull { (key, members) ->
        val text = members.mapNotNull { translated[it.id]?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n")
        if (text.isEmpty()) return@mapNotNull null
        val target = members.first()
        val parent = if (key.first) backgrounds[key.second] else null
        val region = if (parent == null) target else target.copy(
            rect = RectF(parent.rect), maskContour = parent.maskContour, source = BubbleSource.BUBBLE_DETECTOR)
        region.withTranslationResult(text)
    }
}

internal data class VlPageChunk(val rect: RectF, val targets: List<BubbleTranslation>)

internal fun VlPageLayout.chunks(): List<VlPageChunk> {
    val bounds = tiles.map { it.toRectF() }.ifEmpty { listOf(RectF(0f, 0f, width.toFloat(), height.toFloat())) }
    return targets.groupBy { target ->
        bounds.indices.maxByOrNull { i ->
            val intersection = RectF(target.rect)
            if (intersection.intersect(bounds[i])) intersection.width() * intersection.height() else 0f
        } ?: 0
    }.toSortedMap().map { (index, targets) ->
        val rect = RectF(bounds[index])
        targets.forEach { rect.union(it.rect) }
        rect.intersect(0f, 0f, width.toFloat(), height.toFloat())
        VlPageChunk(rect, targets)
    }
}

/** All contour coordinates are normalized against the original page. */
internal object VlPageAnnotationRenderer {
    private val colors = intArrayOf(0xffffb3ba.toInt(), 0xffbaffc9.toInt(), 0xffbae1ff.toInt(),
        0xffffffba.toInt(), 0xffdfbaff.toInt(), 0xffffdfba.toInt())

    fun render(source: Bitmap, layout: VlPageLayout, chunk: VlPageChunk, fullVisual: Boolean = false): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (fullVisual) canvas.drawBitmap(source, 0f, 0f, null) else canvas.drawColor(Color.WHITE)
        val sx = source.width / chunk.rect.width()
        val sy = source.height / chunk.rect.height()
        fun mapped(rect: RectF) = RectF((rect.left - chunk.rect.left) * sx, (rect.top - chunk.rect.top) * sy,
            (rect.right - chunk.rect.left) * sx, (rect.bottom - chunk.rect.top) * sy)
        fun path(rect: RectF, contour: FloatArray?): Path = Path().apply {
            if (contour != null && contour.size >= 6 && contour.size % 2 == 0) {
                for (i in contour.indices step 2) {
                    val x = (contour[i] * layout.width - chunk.rect.left) * sx
                    val y = (contour[i + 1] * layout.height - chunk.rect.top) * sy
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            } else addRect(mapped(rect), Path.Direction.CW)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val targetPaths = chunk.targets.map { path(it.rect, it.maskContour) }
        val targetUnion = Path().apply { targetPaths.forEach { op(it, Path.Op.UNION) } }
        val chunkIds = chunk.targets.map { it.id }.toSet()
        val excludedTargets = Path().apply {
            layout.targets.filter { it.id !in chunkIds }.forEach { op(path(it.rect, it.maskContour), Path.Op.UNION) }
        }
        val coloredRects = mutableListOf<Pair<RectF, Int>>()
        fun chooseColor(rect: RectF): Int {
            val nearby = coloredRects.sortedBy { (previous, _) ->
                hypot(rect.centerX() - previous.centerX(), rect.centerY() - previous.centerY())
            }.take(colors.size - 1).map { it.second }.toSet()
            val index = colors.indices.first { it !in nearby }
            coloredRects.add(rect to index)
            return index
        }
        layout.backgrounds.filter { background -> chunk.targets.any { lineBelongsToRegion(it.rect, background.rect) } }
            .forEach { background ->
                val p = path(background.rect, background.maskContour)
                p.op(excludedTargets, Path.Op.DIFFERENCE)
                val color = colors[chooseColor(background.rect)]
                canvas.save(); canvas.clipPath(p); canvas.drawBitmap(source, 0f, 0f, null); canvas.restore()
                p.op(targetUnion, Path.Op.DIFFERENCE)
                paint.color = color; paint.alpha = 35; canvas.drawPath(p, paint); paint.alpha = 255
            }
        val assigned = mutableListOf<Int>()
        val occupied = mutableListOf<RectF>()
        val textRects = chunk.targets.map { mapped(it.rect) }
        chunk.targets.forEachIndexed { index, target ->
            val colorIndex = chooseColor(target.rect)
            assigned.add(colorIndex)
            val p = targetPaths[index]
            canvas.save(); canvas.clipPath(p); canvas.drawBitmap(source, 0f, 0f, null); canvas.restore()
            paint.style = Paint.Style.FILL; paint.color = colors[colorIndex]; paint.alpha = 42
            canvas.drawPath(p, paint)
            paint.alpha = 255; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            canvas.drawPath(p, paint); paint.style = Paint.Style.FILL
        }
        chunk.targets.forEachIndexed { index, target ->
            val rect = textRects[index]
            paint.textSize = (source.width * .018f).coerceIn(16f, 32f)
            paint.typeface = Typeface.DEFAULT_BOLD
            val label = target.id.toString()
            val w = (paint.measureText(label) + 10f).coerceAtMost(source.width.toFloat())
            val h = (paint.fontSpacing + 6f).coerceAtMost(source.height.toFloat())
            fun box(x: Float, y: Float): RectF {
                val left = x.coerceIn(0f, max(0f, source.width - w))
                val top = y.coerceIn(0f, max(0f, source.height - h))
                return RectF(left, top, left + w, top + h)
            }
            val nearby = listOf(box(rect.left, rect.top - h - 2), box(rect.left - w - 2, rect.top),
                box(rect.right + 2, rect.top), box(rect.left, rect.bottom + 2))
            val obstacles = textRects + occupied
            val nearbyFree = nearby.firstOrNull { candidate -> obstacles.none { RectF.intersects(candidate, it) } }
            val candidates = if (nearbyFree != null) listOf(nearbyFree) else nearby +
                (0..source.height step max(1, h.toInt())).flatMap { y ->
                    (0..source.width step max(1, w.toInt())).map { x -> box(x.toFloat(), y.toFloat()) }
                }.sortedBy { abs(it.left - rect.left) + abs(it.top - rect.top) }
            val labelRect = candidates.firstOrNull { candidate ->
                obstacles.none { RectF.intersects(candidate, it) }
            } ?: candidates.firstOrNull { candidate -> occupied.none { RectF.intersects(candidate, it) } } ?: candidates.first()
            occupied.add(labelRect)
            paint.color = colors[assigned[index]]; canvas.drawRect(labelRect, paint)
            paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            canvas.drawRect(labelRect, paint); paint.style = Paint.Style.FILL
            canvas.drawText(label, labelRect.left + 5f, labelRect.top + 3f - paint.fontMetrics.top, paint)
        }
        return output
    }
}

/** Apply only this request's targets, preserving completed entries and their geometry. */
internal fun mergeVlTargetResults(
    original: List<BubbleTranslation>,
    targets: List<BubbleTranslation>,
    translated: List<BubbleTranslation>
): List<BubbleTranslation> {
    val targetIds = targets.map { it.id }.toSet()
    val byId = translated.associateBy { it.id }
    return original.mapNotNull { bubble ->
        if (bubble.id !in targetIds) bubble else byId[bubble.id]?.let { bubble.withContentFrom(it) }
    }
}
