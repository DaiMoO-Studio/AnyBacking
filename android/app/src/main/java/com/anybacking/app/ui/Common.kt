package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.anybacking.app.R
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anybacking.app.data.lyricsSearchUrl
import com.anybacking.app.data.lyricsSummary
import androidx.compose.ui.text.style.TextOverflow

/** 讀剪貼簿第一筆的文字（只在使用者按「貼上」類按鈕時呼叫，007）。 */
fun readClipboard(ctx: Context): String =
    ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()

/**
 * 歌詞欄旁的「上網找歌詞」「貼上」（007）：用歌名開瀏覽器搜尋，使用者複製後回來按貼上（只有按貼上才讀剪貼簿）。
 * [extra]：同一排右邊的其他元件（例如 LRC 判斷標籤）。
 */
@Composable
fun LyricsSourceButtons(title: String, onPaste: (String) -> Unit, showHint: Boolean = true, extra: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.lyrics_web), enabled = title.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                msg = null
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lyricsSearchUrl(ctx.getString(R.string.lyrics_search_query, title.trim())))))
                } catch (e: ActivityNotFoundException) {
                    msg = ctx.getString(R.string.lyrics_no_browser)
                }
            })
            GhostButton(stringResource(R.string.lyrics_paste), modifier = Modifier.weight(1f), onClick = {
                val text = readClipboard(ctx)
                if (text.isBlank()) msg = ctx.getString(R.string.lyrics_clip_empty) else { msg = null; onPaste(text) }
            })
            extra()
        }
        val hint = msg ?: if (!showHint) null else stringResource(if (title.isBlank()) R.string.lyrics_need_title else R.string.lyrics_hint)
        if (hint != null) Text(hint, color = if (msg != null) Palette.accent2 else Palette.dim, fontSize = 12.sp)
    }
}

fun formatDuration(sec: Double?): String {
    if (sec == null) return ""
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** 歌詞輸入框下方「共 N 行・最後一行：…」（016）：輸入框限高、框內捲動，用這行確認貼上的歌詞是完整的。 */
@Composable
fun LyricsSummary(text: String) {
    val s = lyricsSummary(text) ?: return
    Text(stringResource(R.string.lyrics_summary, s.first, s.second), color = Palette.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
