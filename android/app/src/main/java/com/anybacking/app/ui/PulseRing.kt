package com.anybacking.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val BARS = 64
private const val TOP_BAND = 19   // 只用前 20 段頻帶（最高頻幾乎沒有聲音）

/** 每根柱子固定對應一段頻帶，但位置打散（不對稱，像設計稿那樣隨意起伏）；同一個種子，每次排列一樣。 */
private val BAND_OF = Random(2026).let { r -> IntArray(BARS) { it * (TOP_BAND + 1) / BARS }.apply { shuffle(r) } }
private val BAR_ALPHA = Random(1026).let { r -> FloatArray(BARS) { 0.55f + 0.4f * r.nextFloat() } }

/**
 * 播放鍵外圈的環狀頻譜＋後方光暈（013，research R3）。資料是 SpectrumTap 的 24 段頻帶（0～1，每幀讀取）；
 * 每根柱子對應一段頻帶、位置打散；只顯示比自己平常大聲的部分，才看得出節拍。上升快、下降慢。
 * [active]=false（暫停、關閉跳動、系統移除動畫）時縮回、停止重畫。
 * [buttonSize]＝播放鍵直徑；本元件畫在播放鍵後面，大小和播放鍵相同，圈和光暈畫在邊界外。
 */
@Composable
fun PulseRing(bands: () -> FloatArray, active: Boolean, color: Color, buttonSize: Dp, modifier: Modifier = Modifier) {
    val shown = remember { FloatArray(BARS) }
    val avg = remember { FloatArray(TOP_BAND + 1) { -1f } }   // 每段頻帶的慢速平均（頻帶值本身多半一直很高）
    val lift = remember { FloatArray(TOP_BAND + 1) }
    val glow = remember { FloatArray(1) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(active) {
        while (true) {
            var moving = false
            withFrameNanos { now ->
                val b = if (active) bands() else null
                for (k in 0..TOP_BAND) {
                    lift[k] = if (b == null || b.size <= k) 0f else {
                        val v = b[k].coerceIn(0f, 1f)
                        if (avg[k] < 0) avg[k] = v
                        avg[k] += (v - avg[k]) * 0.03f
                        (0.15f + (v - avg[k]) * 5f).coerceIn(0f, 1f) * (0.4f + 0.6f * v)
                    }
                }
                for (j in 0 until BARS) {
                    val target = lift[BAND_OF[j]]
                    val cur = shown[j]
                    shown[j] = if (target > cur) cur + (target - cur) * 0.5f else cur - (cur - target) * 0.12f
                    if (shown[j] > 0.005f) moving = true
                }
                val low = (lift[0] + lift[1] + lift[2]) / 3f
                glow[0] += (low - glow[0]) * 0.15f
                frame = now
            }
            if (!active && !moving) break   // 縮回後停止每幀重畫
        }
    }

    Canvas(modifier) {
        frame
        val c = Offset(size.width / 2, size.height / 2)
        val inner = min(size.width, size.height) / 2 + 8.dp.toPx()
        // 光暈：播放鍵後面一大團，隨低頻縮放
        val gr = 150.dp.toPx() * (0.86f + 0.22f * glow[0])
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.26f), Color.Transparent), c, gr), gr, c)
        val w = 3.dp.toPx(); val minLen = 4.dp.toPx(); val span = 16.dp.toPx()
        for (j in 0 until BARS) {
            val a = (j * 2 * PI / BARS - PI / 2).toFloat()
            val dx = cos(a); val dy = sin(a)
            val len = minLen + span * shown[j]
            drawLine(color.copy(alpha = BAR_ALPHA[j]), Offset(c.x + dx * inner, c.y + dy * inner),
                Offset(c.x + dx * (inner + len), c.y + dy * (inner + len)), w, StrokeCap.Round)
        }
    }
}
