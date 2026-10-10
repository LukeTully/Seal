package com.junkfood.seal

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.viewmodel.ext.android.getViewModel

@RunWith(AndroidJUnit4::class)
class QuickDownloadActivityTest {
    private val firstUrl = "https://example.com/first-video"
    private val secondUrl = "https://example.com/second-video"

    private fun shareIntent(url: String): Intent =
        Intent(
                InstrumentationRegistry.getInstrumentation().targetContext,
                QuickDownloadActivity::class.java,
            )
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)

    private fun assertConfigured(activity: QuickDownloadActivity, url: String) {
        assertFalse(activity.isFinishing)
        val viewModel = activity.getViewModel<DownloadDialogViewModel>()
        assertEquals(DownloadDialogViewModel.SheetValue.Expanded, viewModel.sheetValueFlow.value)
        assertEquals(
            DownloadDialogViewModel.SheetState.Configure(listOf(url)),
            viewModel.sheetStateFlow.value,
        )
    }

    @Test
    fun shareOpensConfiguration() {
        ActivityScenario.launch<QuickDownloadActivity>(shareIntent(firstUrl)).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertConfigured(it, firstUrl) }
        }
    }

    @Test
    fun recreationKeepsSharedUrl() {
        ActivityScenario.launch<QuickDownloadActivity>(shareIntent(firstUrl)).use { scenario ->
            scenario.recreate()
            scenario.onActivity { assertConfigured(it, firstUrl) }
        }
    }

    @Test
    fun existingShareActivityAcceptsNextIntent() {
        ActivityScenario.launch<QuickDownloadActivity>(shareIntent(firstUrl)).use { scenario ->
            scenario.onActivity {
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(it, shareIntent(secondUrl))
                assertConfigured(it, secondUrl)
            }
        }
    }
}
