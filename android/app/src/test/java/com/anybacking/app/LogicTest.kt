package com.anybacking.app

import com.anybacking.app.data.DeviceKey
import com.anybacking.app.data.Line
import com.anybacking.app.data.PlayPrefs
import com.anybacking.app.data.SongDetail
import com.anybacking.app.data.cleanTitle
import com.anybacking.app.data.lyricsSearchUrl
import com.anybacking.app.data.matchesQuery
import com.anybacking.app.data.looksLikeUrl
import com.anybacking.app.data.titleInput
import com.anybacking.app.net.Pins
import com.anybacking.app.player.LyricsTimeline
import com.anybacking.app.player.VocalMixProcessor
import com.anybacking.app.player.bandEdges
import com.anybacking.app.player.fft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class LogicTest {
    private fun line(s: Double, e: Double) = Line("x", s, e, emptyList(), true)
    private val lines = listOf(line(10.0, 12.0), line(13.0, 15.5), line(16.0, 18.0))

    @Test fun currentLine() {
        assertEquals(-1, LyricsTimeline.currentIndex(lines, 5.0))
        assertEquals(0, LyricsTimeline.currentIndex(lines, 10.0))
        assertEquals(0, LyricsTimeline.currentIndex(lines, 12.9))
        assertEquals(1, LyricsTimeline.currentIndex(lines, 13.0))
        assertEquals(2, LyricsTimeline.currentIndex(lines, 99.0))
        assertEquals(-1, LyricsTimeline.currentIndex(emptyList(), 1.0))
    }

    @Test fun loopRange() {
        assertEquals(13.0 to 18.0, LyricsTimeline.loopRange(lines, 2, 1))
        assertEquals(10.0 to 12.0, LyricsTimeline.loopRange(lines, 0, 0))
        assertTrue(LyricsTimeline.outsideLoop(9.0, 10.0 to 12.0))
        assertFalse(LyricsTimeline.outsideLoop(12.1, 10.0 to 12.0))   // 終點剛過一點點（跳回之前）不算
        assertTrue(LyricsTimeline.outsideLoop(13.0, 10.0 to 12.0))
    }

    @Test fun vocalMix() {
        assertEquals(100.toShort(), VocalMixProcessor.mix(100, 1000, 0f))
        assertEquals(600.toShort(), VocalMixProcessor.mix(100, 1000, 0.5f))
        assertEquals(Short.MAX_VALUE, VocalMixProcessor.mix(30000, 30000, 1f))   // 限幅
        assertEquals(Short.MIN_VALUE, VocalMixProcessor.mix(-30000, -30000, 1f))
    }

    @Test fun fftFindsPeak() {
        val n = 1024; val sr = 44100; val f = 1000.0
        val re = FloatArray(n) { sin(2 * PI * f * it / sr).toFloat() }
        val im = FloatArray(n)
        fft(re, im)
        val mags = (0 until n / 2).map { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val peak = mags.indices.maxBy { mags[it] }
        assertEquals((f * n / sr).toInt().toDouble(), peak.toDouble(), 1.0)
        val e = bandEdges(sr)
        assertEquals(25, e.size)
        assertTrue(e.toList().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun pins() {
        // 釘選值來自建置時的 server.properties（018）；公開版沒有值就不釘選
        Pins.ALL.forEach { assertTrue(Pins.accepts(it)) }
        assertFalse(Pins.accepts("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertEquals(Pins.ALL.isNotEmpty(), Pins.appliesTo(BuildConfig.DEFAULT_SERVER))
        assertFalse(Pins.appliesTo("http://192.168.1.20:8000"))
        Pins.breakForTest = true
        Pins.ALL.forEach { assertFalse(Pins.accepts(it)) }
        Pins.breakForTest = false
    }

    @Test fun keyFormat() {
        assertTrue(DeviceKey.looksValid("ab_" + "A".repeat(43)))
        assertFalse(DeviceKey.looksValid("ab_short"))
        assertFalse(DeviceKey.looksValid("xx_" + "A".repeat(43)))
    }

    @Test fun parseSongDetail() {
        val o = JSONObject("""{"id":"u1","title":"t","status":"done","error":null,"duration":215.76,"playback_ready":true,
            "lines":[{"text":"b","start":5.0,"end":6.0,"words":[{"text":" b","start":5.0,"end":6.0}]},
                     {"text":"a","start":1.0,"end":2.0,"words":[],"confident":false}],
            "media":{"playback":"/api/media/u1/playback.m4a?exp=1&sig=x"},
            "playback_channels":{"instrumental":[0,1],"vocals":[2,3]},"media_expires_in":600}""")
        val d = SongDetail.parse(o)
        assertTrue(d.song.isPlayable)
        assertEquals(listOf("a", "b"), d.lines.map { it.text })   // 依時間排序
        assertFalse(d.lines[0].confident)
        assertEquals(2, d.channels.vocL)
        assertEquals("/api/media/u1/playback.m4a?exp=1&sig=x", d.playbackPath)
    }

    @Test fun prefsRoundTrip() {
        val p = PlayPrefs(semitones = -3, speed = 0.75f, vocalOn = true, vocalGain = 0.4f, loopStart = 10.0, loopEnd = 20.0)
        assertEquals(p, PlayPrefs.parse(p.toJson()))
        assertEquals(PlayPrefs(), PlayPrefs.parse(null))
        assertEquals(6, PlayPrefs.parse("""{"semitones":99}""").semitones)
    }

    @Test fun residueHint() {
        val base = """{"id":"a","title":"t","status":"done","playback_ready":true"""
        val high = SongDetail.parse(JSONObject("$base,\"residue_high\":true}"))
        assertTrue(high.song.residueHigh)
        // 舊伺服器沒有這個欄位：當作不偏高
        val old = SongDetail.parse(JSONObject("$base}"))
        assertFalse(old.song.residueHigh)
    }

    @Test fun titleRules() {
        // 貼上整段歌詞（2026-10-25《狂想曲》）只留一行
        assertEquals("狂想曲 遲鈍的自己 我在天上飛", titleInput("狂想曲\n遲鈍的自己\r\n\t我在天上飛"))
        assertEquals("周杰倫 ", titleInput("周杰倫 "))   // 輸入中保留尾端空格，才打得出詞間空白
        assertEquals("周杰倫 狂想曲", cleanTitle("  周杰倫   狂想曲 \n"))
        assertEquals(200, titleInput("字".repeat(300)).length)
        val emoji = cleanTitle("😀".repeat(250))
        assertEquals(200, emoji.codePointCount(0, emoji.length))   // 不切半個 emoji
        assertEquals("", cleanTitle(" \n\t "))
    }

    @Test fun lyricsSearch() {
        assertEquals("https://www.google.com/search?q=%E5%91%8A%E7%99%BD%E6%B0%A3%E7%90%83+%E6%AD%8C%E8%A9%9E", lyricsSearchUrl("告白氣球 歌詞"))
        val u = lyricsSearchUrl(" A&B #1 😀 歌詞 ")
        assertTrue(u.endsWith("A%26B+%231+%F0%9F%98%80+%E6%AD%8C%E8%A9%9E"))   // 特殊字元與 emoji 都編碼、頭尾空白去掉
    }

    @Test fun urlCheck() {
        assertTrue(looksLikeUrl("https://www.youtube.com/watch?v=bu7nU9Mhpyo"))
        assertTrue(looksLikeUrl(" https://youtu.be/abc "))
        assertFalse(looksLikeUrl("youtube.com/watch?v=x"))   // 要有 http(s)://
        assertFalse(looksLikeUrl("https://"))
    }

    @Test fun librarySearch() {
        assertTrue(matchesQuery("周杰倫 告白氣球", ""))
        assertTrue(matchesQuery("周杰倫 告白氣球", "告白"))
        assertTrue(matchesQuery("Ed Sheeran - Shape of You", "shapeof"))   // 不分大小寫、忽略空白
        assertTrue(matchesQuery("周杰倫 告白氣球", " 周杰倫告白 "))
        assertFalse(matchesQuery("周杰倫 告白氣球", "晴天"))
    }
}
