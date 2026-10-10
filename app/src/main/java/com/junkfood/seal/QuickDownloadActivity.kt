package com.junkfood.seal

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.SettingsProvider
import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.matchUrlFromSharedText
import com.junkfood.seal.util.setLanguage
import kotlinx.coroutines.runBlocking
import org.koin.androidx.viewmodel.ext.android.viewModel

private const val TAG = "QuickDownloadActivity"
private const val SHARED_URL = "shared_url"

class QuickDownloadActivity : ComponentActivity() {
    private var sharedUrlCached: String = ""
    private val dialogViewModel: DownloadDialogViewModel by viewModel()

    private fun Intent.getSharedURL(): String? {
        val intent = this

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedContent ->
                    intent.removeExtra(Intent.EXTRA_TEXT)
                    matchUrlFromSharedText(sharedContent)
                }
            }

            else -> {
                null
            }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrlCached =
            savedInstanceState?.getString(SHARED_URL) ?: intent.getSharedURL().orEmpty()

        if (sharedUrlCached.isEmpty()) {
            finish()
            return
        }

        App.startService()

        enableEdgeToEdge()

        window.run {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }

        if (Build.VERSION.SDK_INT < 33) {
            runBlocking { setLanguage(PreferenceUtil.getLocaleFromPreference()) }
        }

        val viewModel = dialogViewModel
        if (
            savedInstanceState == null ||
                viewModel.sheetStateFlow.value == DownloadDialogViewModel.SheetState.InputUrl
        ) {
            viewModel.postAction(Action.ShowSheet(listOf(sharedUrlCached)))
        }

        setContent {
            SettingsProvider(calculateWindowSizeClass(this).widthSizeClass) {
                SealTheme(
                    darkTheme = LocalDarkTheme.current.isDarkTheme(),
                    isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled,
                ) {
                    var preferences by remember {
                        mutableStateOf(DownloadUtil.DownloadPreferences.createFromPreferences())
                    }

                    val sheetValue = viewModel.sheetValueFlow.collectAsStateWithLifecycle().value

                    val state = viewModel.sheetStateFlow.collectAsStateWithLifecycle().value

                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    val selectionState =
                        viewModel.selectionStateFlow.collectAsStateWithLifecycle().value

                    var showDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(sheetValue, selectionState) {
                        if (sheetValue == DownloadDialogViewModel.SheetValue.Expanded) {
                            showDialog = true
                        } else if (sheetValue == DownloadDialogViewModel.SheetValue.Hidden) {
                            sheetState.hide()
                            showDialog = false
                            if (selectionState == SelectionState.Idle) {
                                this@QuickDownloadActivity.finish()
                            }
                        }
                    }

                    if (showDialog) {
                        DownloadDialog(
                            state = state,
                            sheetState = sheetState,
                            config = Config(),
                            preferences = preferences,
                            onPreferencesUpdate = { preferences = it },
                            onActionPost = { viewModel.postAction(it) },
                        )
                    }

                    when (selectionState) {
                        is SelectionState.FormatSelection ->
                            FormatPage(
                                state = selectionState,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )

                        SelectionState.Idle -> {}
                        is SelectionState.PlaylistSelection -> {
                            PlaylistSelectionPage(
                                state = selectionState,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SHARED_URL, sharedUrlCached)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getSharedURL()?.let { url ->
            sharedUrlCached = url
            dialogViewModel.postAction(Action.Cancel)
            dialogViewModel.postAction(Action.Reset)
            dialogViewModel.postAction(Action.ShowSheet(listOf(url)))
        }
    }
}
