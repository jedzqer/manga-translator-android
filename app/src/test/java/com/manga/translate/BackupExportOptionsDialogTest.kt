package com.manga.translate

import android.os.Looper
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.fragment.app.Fragment
import com.manga.translate.settings.ui.dialogs.BackupExportOptionsDialog
import com.manga.translate.settings.ui.dialogs.BackupExportOptionsHost
import com.manga.translate.storage.AppBackupManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BackupExportOptionsDialogTest {
    @Test
    @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun narrowLightDialog() = checkDialog()

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun narrowDarkLongLabels() = checkDialog()

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun portugueseLabels() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildActivity(BatchSettingsTestActivity::class.java).setup()
        try {
            val host = BackupTestHost()
            controller.get().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, host).commitNow()
            val fragment = BackupExportOptionsDialog()
            fragment.showNow(host.childFragmentManager, BackupExportOptionsDialog.TAG)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = fragment.requireDialog() as AlertDialog
            val manga = dialog.findViewById<AppCompatCheckBox>(R.id.backup_include_manga)!!
            val settings = dialog.findViewById<AppCompatCheckBox>(R.id.backup_include_settings)!!
            val scroll = manga.parent.parent as ScrollView
            assertFalse(scroll.clipToPadding)
            scroll.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 280, 200)
            assertTrue(scroll.getChildAt(0).measuredHeight > scroll.height)
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            assertTrue(manga.isChecked && settings.isChecked && positive.isEnabled)
            manga.performClick()
            settings.performClick()
            assertFalse(positive.isEnabled)
            settings.performClick()
            assertTrue(positive.isEnabled)
            val state = android.os.Bundle()
            fragment.onSaveInstanceState(state)
            assertFalse(state.getBoolean("include_manga"))
            assertTrue(state.getBoolean("include_settings"))
            positive.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AppBackupManager.ExportOptions(false, true), host.selected)
            assertFalse(dialog.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

class BackupTestHost : Fragment(), BackupExportOptionsHost {
    var selected: AppBackupManager.ExportOptions? = null
    override fun onBackupExportOptionsSelected(options: AppBackupManager.ExportOptions) {
        selected = options
    }
}
