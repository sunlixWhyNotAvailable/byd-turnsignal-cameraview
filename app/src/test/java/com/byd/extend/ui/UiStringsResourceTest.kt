package com.byd.extend.ui

import androidx.compose.ui.text.LinkAnnotation
import com.byd.extend.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiStringsResourceTest {
    @Test fun releaseNotesLinkToTheExactApkFromBareAndLabelledAddresses() {
        val apk = "https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/download/v1.11.2/byd-extend-v1.11.2.apk"
        val notes = updateMarkdownInline(
            "$apk. [Завантажити APK]($apk) `$apk` [Local](file:///test)",
            palette(UiTheme.Dark),
        )
        val links = notes.getLinkAnnotations(0, notes.length)
        assertEquals(listOf(apk, apk), links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals(listOf(apk, "Завантажити APK"), links.map { notes.text.substring(it.start, it.end) })
    }

    @Test fun recordingQualityAndArchiveCountsSupportDeferredAndDirectFormatting() {
        val context = RuntimeEnvironment.getApplication()
        val archiveLabels = mutableSetOf<String>()
        for (language in UiLanguage.entries) {
            val strings = UiStrings(language, context)
            val quality = strings.resource(R.string.motion_quality_hint).format(1920, 4)
            assertTrue(quality.contains("1920"))
            assertEquals(quality, strings.resource(R.string.motion_quality_hint, 1920, 4))
            for (count in listOf(0, 3)) {
                for (id in listOf(R.string.motion_archive_count, R.string.motion_archive_selected)) {
                    val label = strings.resource(id).format(count)
                    assertTrue(label.contains(count.toString()))
                    assertEquals(label, strings.resource(id, count))
                    if (id == R.string.motion_archive_count && count == 0) archiveLabels.add(label)
                }
            }
            assertTrue(strings.resource(R.string.asset_download_progress).format(50).contains("50%"))
        }
        assertEquals(UiLanguage.entries.size, archiveLabels.size)
    }
}
