package com.anybacking.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.anybacking.app.data.DeviceKey
import com.anybacking.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeDebugKey(intent)
        setContent { AppRoot(openSongId = takeSong(intent), diag = BuildConfig.DEBUG && intent.getBooleanExtra("diag", false)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeDebugKey(intent)
        setIntent(intent)
        // 只有帶歌（完成通知）或診斷才重建畫面；單純回到 App、點播放通知時重建會把唱歌畫面和播放一起拆掉（016 踩過）
        val song = takeSong(intent)
        val diag = BuildConfig.DEBUG && intent.getBooleanExtra("diag", false)
        if (song != null || diag) setContent { AppRoot(openSongId = song, diag = diag) }
    }

    /** 通知帶來的歌只用一次：切換語言時 Activity 會重建（014），不能又跳回那首歌。 */
    private fun takeSong(intent: Intent?): String? = intent?.getStringExtra(EXTRA_SONG)?.also { intent.removeExtra(EXTRA_SONG) }

    /** debug 版：adb shell am start … --es device_key ab_…（手機預設注音鍵盤，adb 打不進英數字串）。 */
    private fun takeDebugKey(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        // --es server http://192.168.x.y:8000（018 自架測試；debug 版才有）
        intent?.getStringExtra("server")?.let { s ->
            com.anybacking.app.net.ServerConfig.normalize(s)?.let { (application as App).prefs.server = it }
            intent.removeExtra("server")
        }
        val k = intent?.getStringExtra("device_key")?.trim() ?: return
        if (DeviceKey.looksValid(k)) (application as App).key.set(k)
        intent.removeExtra("device_key")
    }

    companion object {
        const val EXTRA_SONG = "song_id"
    }
}
