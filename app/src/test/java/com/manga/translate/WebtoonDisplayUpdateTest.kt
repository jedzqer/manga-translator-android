package com.manga.translate

import android.util.Size
import com.manga.translate.reader.ReadingBitmapCache
import com.manga.translate.reader.WebtoonReadingAdapter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebtoonDisplayUpdateTest {
    @Test fun `measuring a middle page preserves neighboring segments and page coordinates`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val adapter = WebtoonReadingAdapter(scope, { null }, ReadingBitmapCache())
        try {
            val images = (0..99).map { File("/pages/$it.jpg") }
            fun field(name: String) = adapter.javaClass.getDeclaredField(name).apply { isAccessible = true }
            field("items").set(adapter, images)
            val original = images.mapIndexed { index, image ->
                WebtoonReadingAdapter.WebtoonDisplayItem(image, index)
            }.toMutableList()
            field("displayItems").set(adapter, original)
            field("viewportWidth").setInt(adapter, 1000)
            @Suppress("UNCHECKED_CAST")
            val sizes = field("sourceSizeCache").get(adapter) as MutableMap<String, Size>
            sizes[images[50].absolutePath] = Size(1000, 6000)
            val before = original[49]
            val after = original[51]
            adapter.javaClass.getDeclaredMethod("rebuildDisplayRange", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(adapter, 50, 51)
            assertEquals(102, adapter.itemCount)
            assertEquals(50..52, adapter.adapterPositionRangeForImageIndex(50))
            assertSame(before, original[49])
            assertSame(after, original[53])
            assertEquals(51, adapter.imageIndexForAdapterPosition(53))
            val (position, offset) = adapter.positionForPageOffset(50, -3000)
            assertEquals(-3000, adapter.pageOffsetForPosition(position, offset))
        } finally { scope.cancel() }
    }
}
