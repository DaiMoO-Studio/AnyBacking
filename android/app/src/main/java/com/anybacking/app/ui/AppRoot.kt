package com.anybacking.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.stringResource
import com.anybacking.app.R
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.anybacking.app.App
import com.anybacking.app.work.DoneWatcher

/** 畫面（只有一個 Activity，用簡單的堆疊導覽）。 */
sealed interface Screen {
    data object Setup : Screen
    data object Library : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Licenses : Screen
    /** [startAt]：繼續唱卡片的起始位置（秒）；null＝從頭。 */
    data class Player(val songId: String, val startAt: Double? = null) : Screen
    data object Diag : Screen
}

@Composable
fun AppRoot(openSongId: String?, diag: Boolean) {
    val app = LocalContext.current.applicationContext as App
    // 切換語言時 Activity 會重建（014）：堆疊要存起來，才會停在原本的畫面
    val stack = rememberSaveable(saver = ScreenStackSaver) { mutableStateListOf<Screen>() }
    var notice by rememberSaveable { mutableStateOf(false) }   // 設定畫面上方說明金鑰失效

    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun reset(s: Screen) { stack.clear(); stack.add(s) }

    LaunchedEffect(openSongId, diag) {
        when {
            !app.key.isSet || app.prefs.server.isBlank() -> reset(Screen.Setup)
            diag -> reset(Screen.Diag)
            openSongId != null -> { reset(Screen.Library); go(Screen.Player(openSongId)) }
            stack.isEmpty() -> reset(Screen.Library)
        }
    }

    // 401：金鑰失效 → 清掉、停止背景檢查、回設定（contracts/client-behavior.md）
    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        app.api.onUnauthorized = {
            main.post {
                app.key.clear()
                DoneWatcher.cancel(app)
                notice = true
                reset(Screen.Setup)
            }
        }
        onDispose { app.api.onUnauthorized = null }
    }

    BackHandler(enabled = stack.size > 1) { back() }

    Theme {
        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            val modifier = Modifier.fillMaxSize().safeDrawingPadding()
            when (val s = stack.lastOrNull()) {
                Screen.Setup -> SetupScreen(modifier, if (notice) stringResource(R.string.key_expired_notice) else null, firstRun = stack.size == 1,
                    onDone = { notice = false; reset(Screen.Library) }, onDiag = { go(Screen.Diag) })
                Screen.Library -> LibraryScreen(modifier,
                    onPlay = { id, at -> go(Screen.Player(id, at)) }, onSearch = { go(Screen.Search) }, onSettings = { go(Screen.Settings) },
                )
                Screen.Search -> SearchScreen(modifier, onPlay = { go(Screen.Player(it)) }, onBack = { back() })
                Screen.Settings -> SettingsScreen(modifier, onBack = { back() }, onLicenses = { go(Screen.Licenses) }, onServer = { go(Screen.Setup) })
                Screen.Licenses -> LicensesScreen(modifier, onBack = { back() })
                is Screen.Player -> PlayerScreen(s.songId, s.startAt, onBack = { back() })   // 背景全螢幕，自己處理安全區
                Screen.Diag -> Box(modifier) { DiagScreen() }
                null -> {}
            }
        }
    }
}

/** 導覽堆疊存成字串（rememberSaveable）。 */
private val ScreenStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { s -> s.map(::encodeScreen) },
    restore = { l -> mutableStateListOf<Screen>().apply { addAll(l.mapNotNull(::decodeScreen)) } },
)

private fun encodeScreen(s: Screen): String = when (s) {
    Screen.Setup -> "setup"
    Screen.Library -> "library"
    Screen.Search -> "search"
    Screen.Settings -> "settings"
    Screen.Licenses -> "licenses"
    Screen.Diag -> "diag"
    is Screen.Player -> "player|${s.songId}|${s.startAt ?: ""}"
}

private fun decodeScreen(v: String): Screen? = when (v) {
    "setup" -> Screen.Setup
    "library" -> Screen.Library
    "search" -> Screen.Search
    "settings" -> Screen.Settings
    "licenses" -> Screen.Licenses
    "diag" -> Screen.Diag
    else -> v.split("|").takeIf { it.size == 3 && it[0] == "player" }?.let { Screen.Player(it[1], it[2].toDoubleOrNull()) }
}
