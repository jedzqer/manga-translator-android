package com.manga.translate

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.reader.WebtoonRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebtoonRecyclerViewTest {
    @Test
    fun `zoom scales adjacent segments together without changing their view heights`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val list = WebtoonRecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 10
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(View(activity).apply {
                    layoutParams = RecyclerView.LayoutParams(200, 100)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.RED else Color.BLUE)
            }
        }
        activity.setContentView(list)
        fun layout() {
            list.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 200, 400)
        }
        layout()
        list.toggleZoomAtContentPoint(0f, 0f)
        layout()
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        list.draw(Canvas(bitmap))
        assertEquals(Color.RED, bitmap.getPixel(100, 199))
        assertEquals(Color.BLUE, bitmap.getPixel(100, 200))
        assertEquals(Color.BLUE, bitmap.getPixel(100, 399))
        assertEquals(100, list.getChildAt(0).height)
        list.scrollBy(0, 10000)
        list.draw(Canvas(bitmap))
        assertEquals(Color.BLUE, bitmap.getPixel(100, 399))
        assertTrue(!list.canScrollVertically(1))
        list.zoomEnabled = false
        assertEquals(0, list.paddingBottom)
    }
}
