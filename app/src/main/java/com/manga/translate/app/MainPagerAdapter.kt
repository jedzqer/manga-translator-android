package com.manga.translate.app

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.manga.translate.R
import com.manga.translate.library.LibraryFragment
import com.manga.translate.reader.ReadingFragment
import com.manga.translate.settings.ui.SettingsFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            READING_INDEX -> ReadingFragment()
            LIBRARY_INDEX -> LibraryFragment()
            else -> SettingsFragment()
        }
    }

    @StringRes
    fun getTitleRes(position: Int): Int {
        return when (position) {
            READING_INDEX -> R.string.tab_reading
            LIBRARY_INDEX -> R.string.tab_library
            else -> R.string.tab_settings
        }
    }

    companion object {
        const val READING_INDEX = 0
        const val LIBRARY_INDEX = 1
        const val SETTINGS_INDEX = 2
    }
}
