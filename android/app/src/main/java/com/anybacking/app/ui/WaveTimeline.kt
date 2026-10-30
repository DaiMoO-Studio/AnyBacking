package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import com.anybacking.app.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 波浪樣式的時間軸（013，research R4）：一排柱子，已播放的部分是歌曲色、未播放淡色，白線是目前位置。
 * 點一下跳轉；拖動時跟著手指、放開才跳（避免連續跳轉卡頓）。[heights]＝整首音量起伏（015，伺服器 240 點；每根柱子取所涵蓋範圍的最大值），null＝一樣高。
 * [loop]＝循環範圍（淡框）、[mark]＝波浪選段已選的起點（細線）。
 */
@Composable
fun WaveTimeline(
    pos: Double, dur: Double, color: Color, onSeek: (Double) -> Unit, modifier: Modifier = Modifier, heights: FloatArray? = null,
    loop: Pair<Double, Double>? = null, mark: Double? = null, height: Dp = 56.dp,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val total by rememberUpdatedState(dur)
    val frac = drag ?: if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f
    val desc = stringResource(R.string.progress)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics {
                    contentDescription = desc
                    stateDescription = "${formatDuration(frac * dur)} / ${formatDuration(dur)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(frac, 0f..1f)
                    setProgress { v -> if (total > 0) { seek(v.coerceIn(0f, 1f) * total); true } else false }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o -> if (total > 0) seek((o.x / size.width).coerceIn(0f, 1f) * total) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { if (total > 0) seek(it * total) }; drag = null },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            val gap = 3.dp.toPx()
            val n = ((size.width + gap) / (3.dp.toPx() + gap)).toInt().coerceAtLeast(1)
            val bw = (size.width - gap * (n - 1)) / n
            val maxH = size.height - 4.dp.toPx()
            val played = (frac * n).toInt()
            for (i in 0 until n) {
                val rel = heights?.takeIf { it.isNotEmpty() }?.let { h ->
                    val a = (i.toLong() * h.size / n).toInt(); val b = maxOf(a + 1, ((i + 1).toLong() * h.size / n).toInt())
                    (a until minOf(b, h.size)).maxOf { h[it] }
                } ?: 0.4f
                val h = (maxH * rel.coerceIn(0.08f, 1f))
                val x = i * (bw + gap)
                val c = if (i < played) color else Color.White.copy(alpha = 0.30f)
                if (i < played) drawRoundRect(color.copy(alpha = 0.35f), Offset(x - 1.5f, (size.height - h) / 2 - 1.5f), Size(bw + 3f, h + 3f), CornerRadius(bw))
                drawRoundRect(c, Offset(x, (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2))
            }
            // 循環範圍：淡框＋兩側邊線
            if (loop != null && total > 0) {
                val x0 = (loop.first / total).toFloat().coerceIn(0f, 1f) * size.width
                val x1 = (loop.second / total).toFloat().coerceIn(0f, 1f) * size.width
                drawRect(color.copy(alpha = 0.16f), Offset(x0, 0f), Size(x1 - x0, size.height))
                drawLine(color, Offset(x0, 0f), Offset(x0, size.height), 2.dp.toPx())
                drawLine(color, Offset(x1, 0f), Offset(x1, size.height), 2.dp.toPx())
            }
            if (mark != null && total > 0) {
                val xm = (mark / total).toFloat().coerceIn(0f, 1f) * size.width
                // 起點線落在已播放（同色）的柱子上會看不見：先畫深色外框、上下加小三角
                drawLine(Color(0xCC08070C), Offset(xm, 0f), Offset(xm, size.height), 6.dp.toPx())
                drawLine(color, Offset(xm, 0f), Offset(xm, size.height), 2.dp.toPx())
                val t = 5.dp.toPx()
                drawPath(Path().apply { moveTo(xm - t, 0f); lineTo(xm + t, 0f); lineTo(xm, t * 1.4f); close() }, color)
                drawPath(Path().apply { moveTo(xm - t, size.height); lineTo(xm + t, size.height); lineTo(xm, size.height - t * 1.4f); close() }, color)
            }
            val px = frac * size.width
            drawLine(Color.White.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 6.dp.toPx())
            drawLine(Color.White, Offset(px, 2.dp.toPx()), Offset(px, size.height - 2.dp.toPx()), 2.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(frac * dur), fontSize = 12.sp, color = Palette.dim)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(dur), fontSize = 12.sp, color = Palette.dim)
        }
    }
}
