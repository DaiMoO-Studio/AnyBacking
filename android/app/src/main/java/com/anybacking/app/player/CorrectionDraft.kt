package com.anybacking.app.player

import com.anybacking.app.data.EditLine
import com.anybacking.app.data.Line
import com.anybacking.app.R

/** 草稿拒絕的修改（014）：[res]＝給使用者看的字串（依語言）；message 是中文（測試與記錄用）。 */
class DraftError(val res: Int, msg: String) : IllegalArgumentException(msg)

/** 校正草稿的一句。orig*＝伺服器的原值；start < 0 表示沒唱（沒有時間）。 */
data class DraftLine(
    val src: Int,
    val origText: String,
    val origStart: Double,
    val origEnd: Double,
    val text: String,
    val start: Double,
    val end: Double,
    val confident: Boolean,
    val pinned: Boolean = false,   // 使用者設的時間（「設為現在」或加減）
    val exact: Boolean = false,    // 用加減調的時間：就是句首，伺服器不做反應延遲修正
    val moved: Boolean = false,    // 和手動句順序顛倒，App 暫時重估的時間（伺服器會重新對齊）
    val confirmed: Boolean = false, // 使用者確認 AI 推估的時間是對的
) {
    val sung get() = start >= 0
    val edited get() = text != origText
    val changed get() = pinned || edited || confirmed
    /** 時間是 AI 推估、使用者還沒處理（要提示的句子）。 */
    val unsure get() = sung && !pinned && !confirmed && (!confident || moved)
}

/** 送出的一句（specs/backend/contracts/corrections.md）。 */
data class CorrectionLine(
    val src: Int, val text: String, val start: Double, val pinned: Boolean, val edited: Boolean, val moved: Boolean,
    val exact: Boolean = false, val confirmed: Boolean = false,
)

/**
 * 歌詞校正的草稿（純邏輯，不落地）。不可變：每個操作回傳新的草稿；不合規則丟 [IllegalArgumentException]，訊息直接給使用者看。
 * 時間都是原曲秒數（播放位置本來就是原曲時間，變速不用換算）。
 */
class CorrectionDraft private constructor(val lines: List<DraftLine>, val deleted: Int) {
    constructor(edit: List<EditLine>) : this(
        edit.map { DraftLine(it.src, it.text, it.start, it.end, it.text, it.start, it.end, it.confident) }, 0)

    val changes get() = lines.count { it.changed } + deleted
    val dirty get() = changes > 0
    /** 還要提示的推估句數。 */
    val unsureCount get() = lines.count { it.unsure }

    /**
     * 把第 i 句的句首設在 t。exact＝用加減調的時間（就是句首）；false＝播放中按「設為現在」（有反應延遲，伺服器會往前找起音）。
     * 只檢查和其他手動句的順序；中間順序顛倒的非手動句由 [reflow] 讓路。
     */
    fun pin(i: Int, t: Double, exact: Boolean = false): CorrectionDraft {
        if (t < 0) throw DraftError(R.string.draft_negative, "時間不能小於 0")
        val prev = (i - 1 downTo 0).firstOrNull { lines[it].pinned }?.let { lines[it].start }
        val next = (i + 1 until lines.size).firstOrNull { lines[it].pinned }?.let { lines[it].start }
        if (prev != null && t < prev + GAP) throw DraftError(R.string.draft_after_prev, "時間要比上一句晚")
        if (next != null && t > next - GAP) throw DraftError(R.string.draft_before_next, "時間要比下一句早")
        val l = lines[i]
        val len = if (l.origStart >= 0 && l.origEnd > l.origStart) l.origEnd - l.origStart else estimate(l.text)
        return copy(i, l.copy(start = t, end = t + len, pinned = true, exact = exact, moved = false, confirmed = false)).reflow()
    }

    /** 從目前的時間加減 d 秒（沒唱的句子要先設時間）。 */
    fun nudge(i: Int, d: Double): CorrectionDraft {
        val l = lines[i]
        if (!l.sung) throw DraftError(R.string.draft_no_time, "這句還沒有時間，請先按「設為現在」")
        return pin(i, Math.round((l.start + d) * 100) / 100.0, exact = true)
    }

    /** AI 推估的時間是對的。 */
    fun confirm(i: Int) = copy(i, lines[i].copy(confirmed = true))

    /** 剩下的推估句都是對的。 */
    fun confirmAllUnsure() = CorrectionDraft(lines.map { if (it.unsure) it.copy(confirmed = true) else it }, deleted)

    /** 這句的時間、文字、確認都恢復成伺服器的原狀。 */
    fun revert(i: Int): CorrectionDraft {
        val l = lines[i]
        return copy(i, l.copy(text = l.origText, start = l.origStart, end = l.origEnd, pinned = false, exact = false,
            moved = false, confirmed = false)).reflow()
    }

    fun edit(i: Int, text: String): CorrectionDraft {
        val t = text.trim()
        if (t.isEmpty()) throw DraftError(R.string.draft_empty, "歌詞不能是空的")
        if (t.length > 200) throw DraftError(R.string.draft_too_long, "太長了（上限 200 字）")
        return copy(i, lines[i].copy(text = t))
    }

    fun delete(i: Int) = CorrectionDraft(lines.filterIndexed { k, _ -> k != i }, deleted + 1).reflow()

    /**
     * 非手動句一律從原時間重新算：和手動句（或前面的句子）順序顛倒的，在前後可信的句子之間平均重估、標 moved。
     * 這樣顯示用的時間永遠遞增，送出也一定通過伺服器的順序檢查。
     */
    private fun reflow(): CorrectionDraft {
        val ls = lines.map { if (it.pinned) it else it.copy(start = it.origStart, end = it.origEnd, moved = false) }.toMutableList()
        val nextPin = DoubleArray(ls.size + 1) { Double.MAX_VALUE }
        for (k in ls.indices.reversed()) nextPin[k] = if (ls[k].pinned) ls[k].start else nextPin[k + 1]
        val bad = BooleanArray(ls.size)
        var last = -1.0
        var lastPinned = false
        for (k in ls.indices) {
            val l = ls[k]
            if (!l.sung) continue
            if (l.pinned) { last = l.start; lastPinned = true; continue }
            // 自動對齊本身容許相鄰句些微倒退（伺服器同樣容許 0.5 秒），沒動的句子不算衝突
            val tooEarly = if (lastPinned) l.start < last + GAP else l.start < last - REGRESSION
            if (tooEarly || l.start > nextPin[k + 1] - GAP) bad[k] = true
            else if (lastPinned || l.start > last) { last = l.start; lastPinned = false }
        }
        var k = 0
        while (k < ls.size) {
            if (!bad[k]) { k++; continue }
            var m = k
            while (m < ls.size && (bad[m] || !ls[m].sung)) m++   // 連續的壞句（中間夾的沒唱句不動）
            val lo = (k - 1 downTo 0).firstOrNull { ls[it].sung && !bad[it] }?.let { ls[it].start } ?: 0.0
            val idx = (k until m).filter { bad[it] }
            val hi = (m until ls.size).firstOrNull { ls[it].sung }?.let { ls[it].start } ?: (lo + idx.sumOf { estimate(ls[it].text) } + 1.0)
            idx.forEachIndexed { n, j ->
                val s = lo + (hi - lo) * (n + 1) / (idx.size + 1)
                val e = minOf(s + estimate(ls[j].text), lo + (hi - lo) * (n + 2) / (idx.size + 1))
                ls[j] = ls[j].copy(start = s, end = e, moved = true, confirmed = false)
            }
            k = m
        }
        return CorrectionDraft(ls, deleted)
    }

    private fun copy(i: Int, l: DraftLine) = CorrectionDraft(lines.toMutableList().also { it[i] = l }, deleted)

    /** 目前句：最後一個句首 ≤ t 的有時間的句子（草稿索引）；第一句之前回傳 -1。 */
    fun currentIndex(t: Double): Int = lines.indices.lastOrNull { lines[it].sung && lines[it].start <= t } ?: -1

    /** 給播放器的句子（有時間的），校正中預覽用。 */
    fun displayLines(): List<Line> = lines.filter { it.sung }.map { Line(it.text, it.start, it.end, emptyList(), !it.unsure) }

    fun payload(): List<CorrectionLine> = lines.map {
        CorrectionLine(it.src, it.text, if (it.sung) it.start else -1.0, it.pinned, it.edited, it.moved, it.exact, it.confirmed)
    }

    companion object {
        /** 手動句之間至少隔的秒數。 */
        const val GAP = 0.1

        /** 非手動句可以比前一句早多少秒（同伺服器的檢查）。 */
        const val REGRESSION = 0.5

        /** 沒有原時間的句子估計長度：每字 0.3 秒＋1 秒。 */
        fun estimate(text: String) = text.count { !it.isWhitespace() } * 0.3 + 1.0
    }
}
