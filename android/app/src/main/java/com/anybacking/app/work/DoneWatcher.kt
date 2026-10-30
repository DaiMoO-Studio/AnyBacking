package com.anybacking.app.work

import com.anybacking.app.ui.errorCodeText
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anybacking.app.App
import com.anybacking.app.MainActivity
import com.anybacking.app.R
import com.anybacking.app.net.ApiError
import java.util.concurrent.TimeUnit

/**
 * 背景檢查上傳的歌是否處理完（不接推播服務，spec Assumptions）：每 30 秒查一次，完成或失敗就發通知，
 * 直到待通知清單清空。App 在前景時不發通知（歌曲庫畫面自己會更新）。
 */
class DoneWatcher(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val pending = app.prefs.pending
        if (pending.isEmpty() || !app.key.isSet) return Result.success()
        val songs = try {
            app.api.listSongs()
        } catch (e: ApiError.Unauthorized) {
            return Result.success()   // 金鑰失效：停止（App 打開時會導回設定）
        } catch (e: ApiError) {
            schedule(app, delaySec = 60); return Result.success()
        }
        val byId = songs.associateBy { it.id }
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        for (id in pending) {
            val s = byId[id]
            when {
                s == null -> app.prefs.removePending(id)
                s.isPending -> {}
                else -> {
                    if (!foreground) notify(app, id, s.title, if (s.status == "done") app.getString(R.string.notif_done)
                        else app.getString(R.string.notif_failed, errorCodeText(app, s.errorCode, s.errorParams) ?: s.error.orEmpty()))
                    app.prefs.removePending(id)
                }
            }
        }
        if (app.prefs.pending.isNotEmpty()) schedule(app)
        return Result.success()
    }

    companion object {
        private const val NAME = "done-watch"
        private const val CHANNEL = "done"

        fun schedule(ctx: Context, delaySec: Long = 30) {
            val req = OneTimeWorkRequestBuilder<DoneWatcher>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // 執行中的這一次結束後才接下一次（REPLACE 會把自己取消掉）
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)

        fun createChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun notify(ctx: Context, id: String, title: String, text: String) {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(
                ctx, id.hashCode(),
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SONG, id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
        }
    }
}
