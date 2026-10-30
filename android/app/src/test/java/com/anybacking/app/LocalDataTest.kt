package com.anybacking.app

import com.anybacking.app.data.PlayRecord
import com.anybacking.app.data.RecentSearches
import com.anybacking.app.data.Song
import com.anybacking.app.data.coverInitial
import com.anybacking.app.data.highlightRange
import com.anybacking.app.data.isReplay
import com.anybacking.app.data.LastPlayed
import com.anybacking.app.data.lastPlayed
import com.anybacking.app.data.LoopMark
import com.anybacking.app.data.markLoopEnd
import com.anybacking.app.data.markLoopStart
import java.time.LocalDate
import com.anybacking.app.data.pickContinue
import com.anybacking.app.data.splitArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 013 本機資料與顯示文字。 */
class LocalDataTest {
    private val tz = ZoneId.of("Asia/Taipei")
    private fun t(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, tz).toInstant().toEpochMilli()

    @Test fun lastPlayedByCalendarDay() {
        val now = t(2026, 10, 26, 0, 30)
        assertEquals(LastPlayed.Today, lastPlayed(t(2026, 10, 26, 0, 5), now, tz))
        assertEquals(LastPlayed.Yesterday, lastPlayed(t(2026, 10, 25, 23, 50), now, tz))   // 40 分鐘前，但已是昨天
        assertEquals(LastPlayed.DaysAgo(2), lastPlayed(t(2026, 10, 24), now, tz))
        assertEquals(LastPlayed.DaysAgo(6), lastPlayed(t(2026, 10, 20), now, tz))
        assertEquals(LastPlayed.LastWeek, lastPlayed(t(2026, 10, 19), now, tz))
        assertEquals(LastPlayed.LastWeek, lastPlayed(t(2026, 10, 13), now, tz))
        assertEquals(LastPlayed.On(LocalDate.of(2026, 10, 12), true), lastPlayed(t(2026, 10, 12), now, tz))
        assertEquals(LastPlayed.On(LocalDate.of(2025, 12, 31), false), lastPlayed(t(2025, 12, 31), now, tz))
        assertEquals(LastPlayed.Today, lastPlayed(now + 60_000, now, tz))   // 時鐘稍微倒退也不出錯
    }

    @Test fun recordRoundTrip() {
        val r = PlayRecord(123L, 101.5, 42.0)
        assertEquals(r, PlayRecord.parse(r.toJson()))
        assertNull(PlayRecord.parse(null))
        assertNull(PlayRecord.parse("壞掉"))
    }

    @Test fun replayNearEnd() {
        assertTrue(isReplay(206.0, 215.0))
        assertTrue(isReplay(215.0, 215.0))
        assertFalse(isReplay(100.0, 215.0))
        assertFalse(isReplay(206.0, null))
    }

    private fun song(id: String, status: String = "done") = Song(id, id, status, null, 200.0, null, true)

    @Test fun continuePicksNewestAlive() {
        val recs = mapOf("a" to PlayRecord(1, 0.0, 20.0), "b" to PlayRecord(3, 0.0, 20.0), "c" to PlayRecord(5, 0.0, 20.0))
        assertEquals("b", pickContinue(listOf(song("a"), song("b"), song("c", "failed")), recs)?.id)   // c 失敗了
        assertEquals("a", pickContinue(listOf(song("a"), song("d")), recs)?.id)                          // b 被刪了
        assertEquals("b", pickContinue(listOf(song("a"), song("b", "processing")), recs)?.id)            // 重新處理中照樣顯示
        assertNull(pickContinue(listOf(song("d")), recs))
    }

    @Test fun recentSearches() {
        var l = emptyList<String>()
        l = RecentSearches.add(l, " 周杰倫 ")
        l = RecentSearches.add(l, "Bruno Mars")
        l = RecentSearches.add(l, "周杰倫")
        assertEquals(listOf("周杰倫", "Bruno Mars"), l)
        assertEquals(l, RecentSearches.add(l, "   "))
        (1..10).forEach { l = RecentSearches.add(l, "q$it") }
        assertEquals(8, l.size)
        assertEquals("q10", l.first())
        assertEquals(l, RecentSearches.parse(RecentSearches.toJson(l)))
        assertEquals(emptyList<String>(), RecentSearches.parse(null))
    }

    @Test fun artistAndInitial() {
        assertEquals("周杰倫" to "稻香", splitArtist("周杰倫 - 稻香"))
        assertEquals("Bruno Mars" to "Count on Me", splitArtist("Bruno Mars - Count on Me"))
        assertEquals("" to "狂想曲 蕭敬騰", splitArtist("狂想曲 蕭敬騰"))
        assertEquals("" to "A-ha", splitArtist("A-ha"))
        assertEquals("" to " - 稻香", splitArtist(" - 稻香"))
        assertEquals("稻", coverInitial("周杰倫 - 稻香"))
        assertEquals("C", coverInitial("Bruno Mars - count on me"))
        assertEquals("😀", coverInitial("😀 happy"))
        assertEquals("♪", coverInitial("  "))
    }

    @Test fun highlight() {
        assertEquals(0 until 3, highlightRange("周杰倫 - 稻香", "周杰倫"))
        assertEquals(6 until 10, highlightRange("Bruno Mars", "MARS"))
        assertEquals(3 until 8, highlightRange("Bruno Mars", "noma"))   // 跨越空白：範圍包含中間的空白
        assertNull(highlightRange("稻香", "周"))
        assertNull(highlightRange("稻香", " "))
    }

    @Test fun loopMarks() {
        assertEquals(LoopMark.Pending(42.0), markLoopStart(42.0, null))
        assertEquals(LoopMark.Set(50.0, 70.0), markLoopStart(50.0, 30.0 to 70.0))     // 已有循環：只換起點
        assertEquals(LoopMark.Pending(69.0), markLoopStart(69.0, 30.0 to 70.0))       // 太靠近終點：等新的終點
        assertEquals(LoopMark.Set(42.0, 70.0), markLoopEnd(70.0, 42.0, null))
        assertEquals(LoopMark.Set(30.0, 90.0), markLoopEnd(90.0, null, 30.0 to 70.0)) // 沒按開始：沿用目前起點
        assertEquals(LoopMark.NeedStart, markLoopEnd(90.0, null, null))
        assertEquals(LoopMark.TooShort, markLoopEnd(43.0, 42.0, null))
        assertEquals(LoopMark.TooShort, markLoopEnd(40.0, 42.0, null))
    }
}
