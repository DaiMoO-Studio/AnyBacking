package com.anybacking.app.player

import com.anybacking.app.data.Line

/** 歌詞時間軸的純函式（單元測試覆蓋）。時間都是原曲秒數；ExoPlayer 的位置本來就是媒體時間，變速不用換算。 */
object LyricsTimeline {
    /** 目前句：最後一個 start <= t 的句子；第一句之前回傳 -1。 */
    fun currentIndex(lines: List<Line>, t: Double): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].start <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** 循環區段：從 a 句到 b 句（順序不拘），回傳 (起點, 終點) 秒；終點＝較後那句的 end。 */
    fun loopRange(lines: List<Line>, a: Int, b: Int): Pair<Double, Double> {
        val i = minOf(a, b); val j = maxOf(a, b)
        return lines[i].start to maxOf(lines[j].end, lines[i].start + 1.0)
    }

    /** 跳到循環區段外（容許 0.25 秒誤差）就關閉循環。 */
    fun outsideLoop(t: Double, loop: Pair<Double, Double>): Boolean = t < loop.first - 0.25 || t > loop.second + 0.25
}
