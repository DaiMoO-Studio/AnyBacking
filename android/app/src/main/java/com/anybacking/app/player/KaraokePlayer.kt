package com.anybacking.app.player

import com.anybacking.app.R
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.anybacking.app.data.Channels
import okhttp3.OkHttpClient
import kotlin.math.pow

/**
 * 卡拉 OK 播放器：ExoPlayer＋[VocalMixProcessor]（4→2 聲道、原唱音量）＋[SpectrumTap]（頻譜）＋內建 Sonic（升降 Key、變速）。
 *
 * - 播放連結走釘選過的 OkHttp（[http]）；只用記憶體緩衝、不寫快取檔。
 * - 簽名連結過期（403）時呼叫 [onLinkExpired] 取得新網址，從原位置接續。
 */
@UnstableApi
class KaraokePlayer(ctx: Context, http: OkHttpClient) {
    private val appCtx = ctx.applicationContext
    val mix = VocalMixProcessor()
    val spectrum = SpectrumTap()

    private val renderers = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(mix, spectrum))
                // 升降 Key／變速交給系統 AudioTrack（PlaybackParams），不用 Media3 的 Sonic：
                // Sonic 每次改參數都要 drain（queueEndOfStream），實測（1.5.1、1.6.1）連按 Key 會在 Sonic 裡無限迴圈、播放卡死
                .setEnableAudioTrackPlaybackParams(true)
                .build()
    }

    val player: ExoPlayer = ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(http))))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,   // 處理音訊焦點：來電、其他 App 播放時暫停
        )
        .setHandleAudioBecomingNoisy(true)   // 拔耳機暫停
        .setWakeMode(C.WAKE_MODE_NETWORK)    // 背景播放（016）：關螢幕時 CPU 與 Wi-Fi 保持喚醒，串流不斷
        .build()

    var onError: ((String) -> Unit)? = null
    /** 使用者拖到循環區段外、循環被自動關閉時呼叫。 */
    var onLoopCleared: (() -> Unit)? = null
    private var expiredRetries = 0
    private val main = Handler(Looper.getMainLooper())

    private var loopMsg: PlayerMessage? = null
    /** 目前的循環區段（秒）；Compose 狀態，畫面會跟著更新。 */
    private var loopState by mutableStateOf<Pair<Double, Double>?>(null)
    val loop: Pair<Double, Double>? get() = loopState

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val expired = cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403
                // 連結過期（403）或網路中斷：重拿連結、從原位置接續；成功播放後次數歸零
                val network = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                if ((expired || network) && expiredRetries < 5) {
                    expiredRetries++
                    refreshing?.invoke(player.currentPosition)
                } else {
                    onError?.invoke(if (network) appCtx.getString(R.string.kp_network) else appCtx.getString(R.string.kp_failed, error.errorCodeName))
                }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                val l = loop ?: return
                if (reason == Player.DISCONTINUITY_REASON_SEEK && LyricsTimeline.outsideLoop(new.positionMs / 1000.0, l)) {
                    clearLoop()
                    onLoopCleared?.invoke()
                } else scheduleLoop()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) expiredRetries = 0
            }
        })
    }

    /** 連結過期（403）時呼叫，參數是目前位置；由 ViewModel 重拿播放資料後呼叫 [load] 從原位置接續。 */
    var refreshing: ((Long) -> Unit)? = null

    /** [title]／[artist]：通知列與鎖定畫面顯示的歌名（016）。 */
    fun load(url: String, channels: Channels, positionMs: Long = 0, play: Boolean = true, title: String = "", artist: String = "") {
        mix.channels = intArrayOf(channels.instL, channels.instR, channels.vocL, channels.vocR)
        val meta = MediaMetadata.Builder().setTitle(title).setArtist(artist.ifEmpty { null }).build()
        player.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(meta).build(), positionMs)
        player.prepare()
        player.playWhenReady = play
        scheduleLoop()
    }

    fun setVocal(gain: Float) { mix.vocalGain = gain.coerceIn(0f, 1f) }

    /** 升降 Key（半音）與速度一起設定：變調不變速、變速不變調。 */
    fun setKeyAndSpeed(semitones: Int, speed: Float) {
        player.playbackParameters = PlaybackParameters(speed, 2.0.pow(semitones / 12.0).toFloat())
    }

    fun seekTo(seconds: Double) = player.seekTo((seconds * 1000).toLong().coerceAtLeast(0))

    fun setLoop(range: Pair<Double, Double>?) {
        loopState = range
        if (range != null && (player.currentPosition / 1000.0).let { it < range.first || it > range.second }) seekTo(range.first)
        scheduleLoop()
    }

    fun clearLoop() = setLoop(null)

    /** 在循環終點排一個訊息，到了就跳回起點（比輪詢位置準）。 */
    private fun scheduleLoop() {
        loopMsg?.cancel(); loopMsg = null
        val l = loop ?: return
        loopMsg = player.createMessage { _, _ -> main.post { if (loop == l) seekTo(l.first) } }
            .setLooper(Looper.getMainLooper())
            .setPosition((l.second * 1000).toLong())
            .setDeleteAfterDelivery(true)
            .send()
    }

    fun release() {
        loopMsg?.cancel()
        player.release()
    }
}
