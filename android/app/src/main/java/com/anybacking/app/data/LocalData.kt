package com.anybacking.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 本機播放紀錄（013，specs/android/013-ui-redesign/data-model.md）：最後唱的時間、停止位置、累計播放秒數。
 * 累計不到 [PLAYED_MIN] 秒不算唱過（不寫入）。
 */
data class PlayRecord(val at: Long, val pos: Double, val played: Double) {
    fun toJson(): String = JSONObject().put("at", at).put("pos", pos).put("played", played).toString()

    companion object {
        const val PLAYED_MIN = 10.0
        fun parse(s: String?): PlayRecord? = runCatching {
            val o = JSONObject(s!!)
            PlayRecord(o.getLong("at"), o.optDouble("pos", 0.0), o.optDouble("played", 0.0))
        }.getOrNull()
    }
}

/** 停在結尾前 10 秒內（或播完）：繼續唱卡片改成「再唱一次」、從頭播。 */
fun isReplay(pos: Double, duration: Double?): Boolean = duration != null && duration > 0 && pos >= duration - 10

/** 繼續唱卡片要顯示的歌：還在、不是失敗、最後唱的時間最新。 */
fun pickContinue(songs: List<Song>, records: Map<String, PlayRecord>): Song? =
    songs.filter { it.status != "failed" && records.containsKey(it.id) }.maxByOrNull { records.getValue(it.id).at }

/** 「最後唱過」：以手機時區的日曆日計算（不是 24 小時）；文字由畫面依語言決定（014）。 */
sealed interface LastPlayed {
    data object Today : LastPlayed
    data object Yesterday : LastPlayed
    data class DaysAgo(val days: Int) : LastPlayed
    data object LastWeek : LastPlayed
    /** 兩週以上：日期；[thisYear]＝和今天同一年（不用顯示年份）。 */
    data class On(val date: LocalDate, val thisYear: Boolean) : LastPlayed
}

fun lastPlayed(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): LastPlayed {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val d = ChronoUnit.DAYS.between(day, today)
    return when {
        d <= 0L -> LastPlayed.Today
        d == 1L -> LastPlayed.Yesterday
        d <= 6L -> LastPlayed.DaysAgo(d.toInt())
        d <= 13L -> LastPlayed.LastWeek
        else -> LastPlayed.On(day, day.year == today.year)
    }
}

/** 最近搜尋：最新在前、最多 8 個；重複的移到最前；空字串不記。 */
object RecentSearches {
    const val MAX = 8
    fun add(list: List<String>, q: String): List<String> {
        val t = q.trim()
        if (t.isEmpty()) return list
        return (listOf(t) + list.filter { it != t }).take(MAX)
    }
    fun toJson(list: List<String>): String = JSONArray(list).toString()
    fun parse(s: String?): List<String> = runCatching {
        val a = JSONArray(s!!); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
}

/** 「歌手 - 歌名」拆成 (歌手, 歌名)；不是這個格式時歌手是空字串。 */
fun splitArtist(title: String): Pair<String, String> {
    val i = title.indexOf(" - ")
    if (i <= 0) return "" to title
    val artist = title.substring(0, i).trim(); val name = title.substring(i + 3).trim()
    return if (artist.isEmpty() || name.isEmpty()) "" to title else artist to name
}

/** 封面上的大字：歌名第一個字（拉丁字母轉大寫）；沒有字用 ♪。 */
fun coverInitial(title: String): String {
    val t = splitArtist(title).second.trim()
    if (t.isEmpty()) return "♪"
    val cp = t.codePointAt(0)
    return String(Character.toChars(cp)).uppercase()
}

/** 搜尋字在歌名中第一次出現的範圍（忽略空白、不分大小寫，同 [matchesQuery]）；回傳原字串的 [start, end)。 */
fun highlightRange(title: String, q: String): IntRange? {
    val key = q.filterNot { it.isWhitespace() }.lowercase()
    if (key.isEmpty()) return null
    val idx = ArrayList<Int>()   // 去掉空白後每個字元在原字串的位置
    val sb = StringBuilder()
    title.forEachIndexed { i, c -> if (!c.isWhitespace()) { sb.append(c.lowercaseChar()); idx.add(i) } }
    val at = sb.indexOf(key)
    if (at < 0) return null
    return idx[at] until idx[at + key.length - 1] + 1
}

/** 波浪選段（015）：按「從這裡開始／到這裡結束」的結果。 */
sealed interface LoopMark {
    /** 設好循環。 */
    data class Set(val start: Double, val end: Double) : LoopMark
    /** 只有起點，等使用者按「到這裡結束」。 */
    data class Pending(val start: Double) : LoopMark
    /** 還沒有起點就按了結束。 */
    data object NeedStart : LoopMark
    /** 結束在起點之前或太近（< [MIN_LOOP] 秒）。 */
    data object TooShort : LoopMark
}

const val MIN_LOOP = 2.0

/** 「從這裡開始」：已有循環且位置在終點前 2 秒以上 → 只換起點；否則記下起點、等結束。 */
fun markLoopStart(pos: Double, loop: Pair<Double, Double>?): LoopMark =
    if (loop != null && pos <= loop.second - MIN_LOOP) LoopMark.Set(pos, loop.second) else LoopMark.Pending(pos)

/** 「到這裡結束」：起點用剛按的（[pending]），沒有就用目前循環的起點。 */
fun markLoopEnd(pos: Double, pending: Double?, loop: Pair<Double, Double>?): LoopMark {
    val start = pending ?: loop?.first ?: return LoopMark.NeedStart
    return if (pos - start < MIN_LOOP) LoopMark.TooShort else LoopMark.Set(start, pos)
}
