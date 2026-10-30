package com.anybacking.app.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * 背景播放（016）：離開 App、關螢幕繼續唱。播放器還是唱歌畫面的 [KaraokePlayer]（跟著畫面建立與釋放），
 * 這個服務只提供 MediaSession——播放時 Media3 自動把服務升成前景服務（mediaPlayback）、顯示通知列與鎖定畫面的播放控制。
 * 用法：唱歌畫面 [attach]、離開畫面 [detach]。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 從最近使用的 App 清單滑掉：停止播放、收掉通知。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let { it.pause(); it.stop() }
        stopSelf()
    }

    /** 唱歌畫面的連線：session 掛在這個服務上，controller 讓服務保持連線（播放時才會升成前景服務）。 */
    class Handle internal constructor(val session: MediaSession, private val controller: ListenableFuture<MediaController>) {
        fun detach(ctx: Context) {
            MediaController.releaseFuture(controller)
            if (Companion.session === session) Companion.session = null
            session.release()
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    companion object {
        @Volatile private var session: MediaSession? = null

        fun attach(ctx: Context, player: Player, id: String): Handle {
            val app = ctx.applicationContext
            // 點通知：像點 App 圖示一樣把原本的畫面叫回前面（不送新 intent，唱歌畫面不重建）
            val open = PendingIntent.getActivity(app, 0, app.packageManager.getLaunchIntentForPackage(app.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val s = MediaSession.Builder(app, player).setId("$id-${System.nanoTime()}").setSessionActivity(open).build()
            session?.release()
            session = s
            val controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            return Handle(s, controller)
        }
    }
}
