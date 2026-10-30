package com.anybacking.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.anybacking.app.R
import com.anybacking.app.data.LastPlayed
import com.anybacking.app.data.Song
import com.anybacking.app.net.ApiError
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 錯誤與時間的顯示文字（014 多國語系）。伺服器的錯誤代碼對到 err_<代碼> 字串（specs/backend/contracts/errors.md）；
 * 參數依代碼固定順序帶入。不認得的代碼回傳 null，呼叫端改顯示伺服器附的中文。
 */
private val ERR_PARAMS = mapOf(
    "file_too_large" to listOf("max"),
    "too_many_lines" to listOf("max"),
    "line_empty" to listOf("n"),
    "line_too_long" to listOf("n", "max"),
    "line_time_invalid" to listOf("n"),
    "line_time_over" to listOf("n"),
    "line_time_order" to listOf("n"),
    "line_src_invalid" to listOf("n"),
    "lyrics_too_long" to listOf("max"),
    "audio_too_short" to listOf("seconds"),
    "video_too_long" to listOf("minutes"),
)

/** 錯誤代碼 → 目前語言的文字；不認得的代碼回傳 null。 */
fun errorCodeText(ctx: Context, code: String?, params: Map<String, Int>): String? {
    if (code == null) return null
    val id = ctx.resources.getIdentifier("err_$code", "string", ctx.packageName)
    if (id == 0) return null
    val args = ERR_PARAMS[code].orEmpty().map { params[it] ?: 0 }.toTypedArray<Any>()
    return ctx.getString(id, *args)
}

/** 例外 → 給使用者看的文字（ApiError 依語言；其他用 message）。 */
fun Throwable.userText(ctx: Context): String = when (this) {
    is ApiError.Unauthorized -> ctx.getString(R.string.api_unauthorized)
    is ApiError.RateLimited -> ctx.getString(R.string.api_rate_limited, retryAfterS)
    is ApiError.NotFound -> ctx.getString(R.string.api_not_found)
    is ApiError.Insecure -> ctx.getString(R.string.api_insecure)
    is ApiError.Network -> ctx.getString(R.string.api_network)
    is ApiError.FileUnreadable -> ctx.getString(R.string.api_file_unreadable)
    is ApiError.Http -> errorCodeText(ctx, errCode, params) ?: message ?: ctx.getString(R.string.api_server_error, code)
    else -> message.orEmpty()
}

/** 歌曲失敗原因：有代碼依語言，舊紀錄顯示當時的中文。 */
@Composable
fun songErrorText(s: Song): String? = errorCodeText(LocalContext.current, s.errorCode, s.errorParams) ?: s.error

/** 「最後唱過」依語言顯示（英文 Oct 12／Dec 31, 2025；中文 10/12／2025/12/31）。 */
@Composable
fun lastPlayedText(lp: LastPlayed): String = when (lp) {
    LastPlayed.Today -> stringResource(R.string.last_today)
    LastPlayed.Yesterday -> stringResource(R.string.last_yesterday)
    is LastPlayed.DaysAgo -> pluralStringResource(R.plurals.days_ago, lp.days, lp.days)
    LastPlayed.LastWeek -> stringResource(R.string.last_week)
    is LastPlayed.On -> {
        val loc = LocalConfiguration.current.locales[0]
        val zh = loc.language == "zh"
        val pattern = when {
            zh && lp.thisYear -> "M/d"
            zh -> "yyyy/M/d"
            lp.thisYear -> "MMM d"
            else -> "MMM d, yyyy"
        }
        lp.date.format(DateTimeFormatter.ofPattern(pattern, if (zh) loc else Locale.ENGLISH))
    }
}
