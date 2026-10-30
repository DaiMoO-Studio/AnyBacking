package com.anybacking.app.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.anybacking.app.App
import com.anybacking.app.data.Channels
import com.anybacking.app.player.KaraokePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * debug 版診斷（4 聲道解碼、Key／速度試聽、播放卡住時印 stack）：
 * - 播 assets 的 quad_test.m4a（聲道 0～3 各為 200／400／800／1600 Hz 正弦波），由過零次數推回每個聲道的頻率，
 *   確認解碼器真的輸出 4 聲道、順序正確；
 * - 播伺服器上第一首可播的歌，試原唱音量、升降 Key、變速的音質。
 */
@OptIn(UnstableApi::class)
@Composable
fun DiagScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val kp = remember { KaraokePlayer(ctx, app.api.http).also { it.mix.diagnostics = true } }
    DisposableEffect(Unit) { onDispose { kp.release() } }
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var gain by remember { mutableFloatStateOf(0f) }
    var semis by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        var lastFrames = 0L
        val lastZc = LongArray(4)
        var lastPos = -1L; var stuck = 0
        while (true) {
            delay(1000)
            // 播放中位置卡住 3 秒：把 ExoPlayer 播放執行緒的 stack 印到 logcat（沒有 root 讀不到 tombstone）
            val nowPos = kp.player.currentPosition
            stuck = if (kp.player.isPlaying && nowPos == lastPos) stuck + 1 else 0
            lastPos = nowPos
            if (stuck == 3) Thread.getAllStackTraces().filterKeys { it.name.startsWith("ExoPlayer:Playback") }.forEach { (t, st) ->
                android.util.Log.w("AnyBackingDiag", "stuck ${t.name}\n" + st.joinToString("\n") { "  at $it" })
            }
            val m = kp.mix
            val frames = m.framesSeen - lastFrames
            val freqs = (0 until 4).map { c ->
                val zc = m.zeroCrossings[c] - lastZc[c]
                if (frames > 0) zc * 44100.0 / frames / 2 else 0.0
            }
            lastFrames = m.framesSeen
            for (c in 0 until 4) lastZc[c] = m.zeroCrossings[c]
            info = "解碼輸出聲道數：${m.inputChannels}\n" +
                "推估頻率（聲道 0～3）：${freqs.joinToString(" / ") { "%.0f Hz".format(it) }}\n" +
                "位置 ${kp.player.currentPosition / 1000.0} 秒  playing=${kp.player.isPlaying}\n" +
                "頻譜 ${kp.spectrum.bands.joinToString("") { b -> " ▁▂▃▄▅▆▇█"[(b * 8).toInt().coerceIn(0, 8)].toString() }}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("診斷（debug）")
        Button(onClick = {
            kp.mix.resetDiagnostics()
            kp.load("asset:///quad_test.m4a", Channels())
            status = "播放測試檔：預期 200 / 400 / 800 / 1600 Hz"
        }) { Text("播 4 聲道測試檔") }
        Button(onClick = {
            scope.launch {
                status = runCatching {
                    val d = withContext(Dispatchers.IO) {
                        app.api.getSong(app.api.listSongs().first { it.isPlayable }.id)
                    }
                    kp.mix.resetDiagnostics()
                    kp.load(app.api.mediaUrl(d.playbackPath!!), d.channels)   // ExoPlayer 只能在主執行緒操作
                    "播放：${d.song.title}"
                }.getOrElse { "失敗：${it.message}" }
            }
        }) { Text("播伺服器第一首歌") }
        Text(status)
        Text(info)
        Text("原唱音量 ${"%.0f".format(gain * 100)}%")
        Slider(value = gain, onValueChange = { gain = it; kp.setVocal(it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { semis = (semis - 1).coerceAtLeast(-6); kp.setKeyAndSpeed(semis, speed) }) { Text("Key −") }
            Text("Key $semis", Modifier.padding(top = 12.dp))
            Button(onClick = { semis = (semis + 1).coerceAtMost(6); kp.setKeyAndSpeed(semis, speed) }) { Text("Key ＋") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f).forEach { s ->
                Button(onClick = { speed = s; kp.setKeyAndSpeed(semis, speed) }) { Text("${s}×") }
            }
        }
        Button(onClick = { if (kp.player.isPlaying) kp.player.pause() else kp.player.play() }) { Text("暫停／繼續") }
    }
}
