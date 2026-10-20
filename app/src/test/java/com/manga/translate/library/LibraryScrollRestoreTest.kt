package com.manga.translate.library

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.di.appContainer
import androidx.lifecycle.SavedStateHandle
import com.manga.translate.R
import com.manga.translate.databinding.FragmentLibraryBinding
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LibraryScrollRestoreTest {
    @Test
    @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun collectionListRestoresAfterBeingClearedAndNewChapterStartsAtTop() = checkRestore()

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun collectionListRestoresInNarrowDarkLayout() = checkRestore()

    private fun checkRestore() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MangaTranslator)
        val binding = FragmentLibraryBinding.inflate(LayoutInflater.from(context))
        val fragment = LibraryFragment()
        fun field(name: String, value: Any?) {
            LibraryFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.set(fragment, value)
        }
        field("_binding", binding)
        val container = context.appContainer
        val vm = LibraryViewModel(SavedStateHandle(), container.libraryRepository,
            container.libraryPreferencesGateway, container.translationStore)
        field("pageViewModel\$delegate", lazyOf(vm))
        val folder = File("/collection")
        vm.navigate(folder)
        binding.folderChapterList.visibility = View.VISIBLE
        val list = binding.folderChapterList
        val manager = LinearLayoutManager(context)
        list.layoutManager = manager
        var count = 100
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = count
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(TextView(context).apply {
                    layoutParams = RecyclerView.LayoutParams(320, 80)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        list.adapter = adapter
        fun layout() {
            list.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 320, 480)
        }
        layout()
        manager.scrollToPositionWithOffset(42, -17)
        layout()
        LibraryFragment::class.java.getDeclaredMethod("persistCurrentFolderDetailScroll")
            .apply { isAccessible = true }.invoke(fragment)
        // Opening a child clears the shared adapter before the collection is loaded again.
        count = 0
        adapter.notifyDataSetChanged()
        layout()
        count = 100
        adapter.notifyDataSetChanged()
        fun restore(target: File) {
            vm.navigate(target)
            field("pendingFolderScrollRestore", target.absolutePath)
            LibraryFragment::class.java.getDeclaredMethod("restoreFolderScroll", File::class.java,
                Long::class.javaPrimitiveType, RecyclerView::class.java)
                .apply { isAccessible = true }.invoke(fragment, target, 0L, list)
            layout()
        }
        restore(folder)
        assertEquals(42, manager.findFirstVisibleItemPosition())
        assertEquals(-17, manager.findViewByPosition(42)!!.top)
        restore(File("/collection/new-chapter"))
        assertEquals(0, manager.findFirstVisibleItemPosition())
        assertEquals(0, manager.findViewByPosition(0)!!.top)
    }
}
