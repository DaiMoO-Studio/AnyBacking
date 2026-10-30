package com.anybacking.app

import com.anybacking.app.data.EditLine
import com.anybacking.app.data.SongDetail
import com.anybacking.app.data.looksLikeLrc
import com.anybacking.app.player.CorrectionDraft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CorrectionDraftTest {
    // 第 2 句（索引 2）是沒唱的句子
    private val edit = listOf(
        EditLine(0, "第一句", 10.0, 12.0, true),
        EditLine(1, "第二句", 13.0, 15.0, false),
        EditLine(2, "沒唱", -1.0, -1.0, false),
        EditLine(3, "第四句", 20.0, 22.0, true),
        EditLine(4, "第五句", 25.0, 27.0, true),
    )
    private val d0 = CorrectionDraft(edit)

    private fun rejects(msg: String, f: () -> Unit) {
        try { f(); fail("應該拒絕：$msg") } catch (e: IllegalArgumentException) { assertEquals(msg, e.message) }
    }

    private fun assertIncreasing(d: CorrectionDraft) {
        val s = d.lines.filter { it.sung }.map { it.start }
        assertEquals(s.sorted(), s)
    }

    @Test fun startsClean() {
        assertFalse(d0.dirty)
        assertEquals(0, d0.changes)
        assertEquals(4, d0.displayLines().size)
        assertEquals(-1, d0.currentIndex(5.0))
        assertEquals(1, d0.currentIndex(16.0))
        assertEquals(3, d0.currentIndex(20.0))
    }

    @Test fun pinKeepsLengthAndMarks() {
        val d = d0.pin(1, 14.0)
        assertTrue(d.lines[1].pinned)
        assertEquals(14.0, d.lines[1].start, 1e-9)
        assertEquals(16.0, d.lines[1].end, 1e-9)
        assertEquals(1, d.changes)
        assertTrue(d.dirty)
        val p = d.payload()
        assertEquals(5, p.size)
        assertTrue(p[1].pinned)
        assertEquals(-1.0, p[2].start, 0.0)
        assertEquals(10.0, p[0].start, 0.0)   // 沒動的句子原樣送出
    }

    @Test fun pinUnsungLine() {
        val d = d0.pin(2, 17.0)
        assertTrue(d.lines[2].sung)
        assertEquals(17.0 + CorrectionDraft.estimate("沒唱"), d.lines[2].end, 1e-9)
        assertEquals(5, d.displayLines().size)
    }

    @Test fun orderAgainstOtherPins() {
        val d = d0.pin(3, 20.5)
        rejects("時間要比上一句晚") { d.pin(4, 20.55) }
        rejects("時間要比下一句早") { d.pin(0, 20.45) }
        d.pin(4, 21.0)   // 隔 0.5 秒可以
    }

    @Test fun reflowMovesConflictingLines() {
        // 第五句設到 14 秒：第二、四句（原本 13、20）中第四句比它晚 → 重估到前後句之間
        val d = d0.pin(4, 14.0)
        assertFalse(d.lines[1].moved)              // 13 < 14 不衝突
        assertTrue(d.lines[3].moved)
        assertTrue(d.lines[3].start > 13.0 && d.lines[3].start < 14.0)
        assertIncreasing(d)
        // 清除後恢復原時間
        val c = d.revert(4)
        assertFalse(c.lines.any { it.moved || it.pinned })
        assertEquals(20.0, c.lines[3].start, 0.0)
        assertFalse(c.dirty)
    }

    @Test fun reflowEarlierLines() {
        // 第一句設到 21 秒：後面的第二、四句都比它早 → 重估到第一句和第五句之間
        val d = d0.pin(0, 21.0)
        assertTrue(d.lines[1].moved && d.lines[3].moved)
        assertIncreasing(d)
        assertTrue(d.lines[3].start < 25.0)
        assertTrue(d.payload().filter { it.start >= 0 }.zipWithNext().all { (a, b) -> a.start <= b.start })
    }

    @Test fun slightRegressionInOriginalIsNotAConflict() {
        // 自動對齊結果本身第 2、3 句倒退 0.02 秒（實際資料：129.01、128.99）
        val d = CorrectionDraft(listOf(
            EditLine(0, "a", 10.0, 12.0, true), EditLine(1, "b", 129.01, 131.0, true),
            EditLine(2, "c", 128.99, 133.0, true), EditLine(3, "d", 140.0, 142.0, true),
        )).pin(0, 9.0)
        assertFalse(d.lines.any { it.moved })
        assertEquals(128.99, d.payload()[2].start, 0.0)
    }

    @Test fun confirmAndNudge() {
        assertEquals(1, d0.unsureCount)                  // 第二句是推估的
        val c = d0.confirm(1)
        assertEquals(0, c.unsureCount)
        assertTrue(c.dirty)                              // 只確認也能送出
        assertTrue(c.payload()[1].confirmed)
        assertEquals(0, d0.confirmAllUnsure().unsureCount)
        val n = d0.nudge(1, -0.5)
        assertEquals(12.5, n.lines[1].start, 1e-9)
        assertTrue(n.lines[1].pinned && n.lines[1].exact)
        assertFalse(n.lines[1].unsure)                  // 使用者調過的不再算推估
        assertTrue(n.payload()[1].exact)
        assertTrue(n.displayLines()[1].confident)
        rejects("這句還沒有時間，請先按「設為現在」") { d0.nudge(2, 0.1) }
        rejects("時間要比上一句晚") { d0.pin(3, 20.5).nudge(4, -4.45) }
        val r = n.edit(1, "改").revert(1)
        assertFalse(r.dirty)
        assertEquals("第二句", r.lines[1].text)
    }

    @Test fun editAndDelete() {
        val d = d0.edit(1, " 第二劇 ")
        assertEquals("第二劇", d.lines[1].text)
        assertTrue(d.lines[1].edited)
        assertTrue(d.payload()[1].edited)
        assertFalse(d.edit(1, "第二句").lines[1].edited)   // 改回原文不算修改
        rejects("歌詞不能是空的") { d0.edit(0, "  ") }
        val x = d0.delete(2)
        assertEquals(4, x.lines.size)
        assertEquals(1, x.changes)
        assertEquals(listOf(0, 1, 3, 4), x.payload().map { it.src })
    }

    @Test fun lrcDetectionAndLyricsFields() {
        assertTrue(looksLikeLrc("[00:12.34]第一句"))
        assertTrue(looksLikeLrc("intro\n[1:02]x"))
        assertFalse(looksLikeLrc("第一句\n第二句 [副歌]"))
        val d = SongDetail.parse(JSONObject("""{"id":"x","status":"done","correction":{"id":3,"kind":"lyrics","status":"queued","error":null}}"""))
        assertEquals("lyrics", d.correction!!.kind)
    }

    @Test fun parseEditLines() {
        val o = JSONObject("""{"id":"x","status":"done","lines":[],
            "edit_lines":[{"src":0,"text":"a","start":1.5,"end":2,"confident":true},{"src":1,"text":"b","start":-1,"end":-1,"confident":false}],
            "correction":{"id":7,"status":"failed","error":"壞了","created_at":"t","finished_at":null}}""")
        val d = SongDetail.parse(o)
        assertEquals(2, d.editLines.size)
        assertEquals(-1.0, d.editLines[1].start, 0.0)
        assertEquals(7, d.correction!!.id)
        assertEquals("壞了", d.correction!!.error)
        assertNull(SongDetail.parse(JSONObject("""{"id":"x","status":"done","correction":null}""")).correction)
    }
}
