package com.anybacking.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 後端歌曲（specs/backend/contracts/playback.md、specs/android/spec.md）。 */
data class Song(
    val id: String,
    val title: String,
    val status: String,          // queued / processing / done / failed
    val error: String?,
    val duration: Double?,
    val queuePosition: Int?,
    val playbackReady: Boolean,
    val residueHigh: Boolean = false,   // 伺服器判斷人聲殘留偏高（005；App 不自己比較數字）
    val errorCode: String? = null,        // 失敗原因的代碼（014）；舊紀錄沒有
    val errorParams: Map<String, Int> = emptyMap(),
) {
    val isPending get() = status == "queued" || status == "processing"
    val isPlayable get() = status == "done" && playbackReady

    companion object {
        fun parse(o: JSONObject) = Song(
            id = o.getString("id"),
            title = o.optString("title", ""),
            status = o.optString("status"),
            error = o.optStringOrNull("error"),
            duration = if (o.isNull("duration")) null else o.optDouble("duration"),
            queuePosition = if (o.has("queue_position")) o.optInt("queue_position") else null,
            playbackReady = o.optBoolean("playback_ready", false),
            residueHigh = o.optBoolean("residue_high", false),
            errorCode = o.optStringOrNull("error_code"),
            errorParams = o.intParams("error_params"),
        )
    }
}

data class Word(val text: String, val start: Double, val end: Double)

/** 一句歌詞；時間是原曲時間（秒）。 */
data class Line(val text: String, val start: Double, val end: Double, val words: List<Word>, val confident: Boolean)

/** 校正用的完整歌詞（edit_lines，含這版沒唱的句子：start = -1）。src＝送出校正時帶回的索引。 */
data class EditLine(val src: Int, val text: String, val start: Double, val end: Double, val confident: Boolean)

/** 這首最新一筆校正（specs/backend/contracts/corrections.md）。 */
data class CorrectionInfo(
    val id: Int, val status: String, val error: String?, val kind: String = "correction",
    val errorCode: String? = null, val errorParams: Map<String, Int> = emptyMap(),
)

/** 聲道配置（playback_channels）：預設伴奏 0、1，人聲 2、3。 */
data class Channels(val instL: Int = 0, val instR: Int = 1, val vocL: Int = 2, val vocR: Int = 3)

data class SongDetail(
    val song: Song,
    val lines: List<Line>,
    val playbackPath: String?,   // 相對路徑（含 exp、sig）
    val channels: Channels,
    val expiresIn: Int,
    val editLines: List<EditLine> = emptyList(),
    val correction: CorrectionInfo? = null,
    /** 整首音量起伏（015，240 點、0～1）；舊伺服器或還沒補算是 null。 */
    val waveform: FloatArray? = null,
) {
    companion object {
        fun parse(o: JSONObject): SongDetail {
            val lines = o.optJSONArray("lines")?.let(::parseLines).orEmpty()
            val edit = o.optJSONArray("edit_lines")?.let { a ->
                (0 until a.length()).map { i ->
                    val l = a.getJSONObject(i)
                    EditLine(l.optInt("src", i), l.optString("text"), l.optDouble("start", -1.0), l.optDouble("end", -1.0),
                        l.optBoolean("confident", true))
                }
            }.orEmpty()
            val corr = o.optJSONObject("correction")?.let {
                CorrectionInfo(it.getInt("id"), it.optString("status"), it.optStringOrNull("error"), it.optString("kind", "correction"),
                    it.optStringOrNull("error_code"), it.intParams("error_params"))
            }
            val ch = o.optJSONObject("playback_channels")?.let {
                val i = it.getJSONArray("instrumental"); val v = it.getJSONArray("vocals")
                Channels(i.getInt(0), i.getInt(1), v.getInt(0), v.getInt(1))
            } ?: Channels()
            return SongDetail(
                song = Song.parse(o),
                lines = lines,
                playbackPath = o.optJSONObject("media")?.optStringOrNull("playback"),
                channels = ch,
                expiresIn = o.optInt("media_expires_in", 600),
                editLines = edit,
                correction = corr,
                waveform = o.optJSONArray("waveform")?.let { a -> FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() } },
            )
        }

        fun parseLines(a: JSONArray): List<Line> = (0 until a.length()).map { i ->
            val l = a.getJSONObject(i)
            val w = l.optJSONArray("words") ?: JSONArray()
            Line(
                text = l.optString("text"),
                start = l.getDouble("start"),
                end = l.getDouble("end"),
                words = (0 until w.length()).map { j ->
                    val x = w.getJSONObject(j); Word(x.optString("text"), x.getDouble("start"), x.getDouble("end"))
                },
                confident = l.optBoolean("confident", true),
            )
        }.filter { it.start >= 0 }.sortedBy { it.start }
    }
}

/** 歌曲庫搜尋（010）：歌名包含搜尋字（不分大小寫、忽略空白）；空的搜尋全部符合。 */
fun matchesQuery(title: String, q: String): Boolean {
    val key = q.filterNot { it.isWhitespace() }.lowercase()
    return key.isEmpty() || title.filterNot { it.isWhitespace() }.lowercase().contains(key)
}

/** 網址上傳（009）：只檢查格式，能不能下載由伺服器判斷。 */
fun looksLikeUrl(s: String) = s.trim().let { (it.startsWith("http://") || it.startsWith("https://")) && it.length in 12..2000 }

/** 「上網找歌詞」（007）：在瀏覽器搜尋 [query]（「歌名 歌詞」，014 起依語言：「title lyrics」）；App 不碰搜尋結果，使用者自己複製回來貼上。 */
fun lyricsSearchUrl(query: String): String =
    "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")

const val TITLE_MAX = 200

/** 歌名輸入過濾（006）：貼上多行只留一行、連續空白合一、最多 200 字；不去頭尾（打字時詞間的空格不能被吃掉）。 */
fun titleInput(s: String): String = takeCodePoints(s.replace(Regex("\\s+"), " "), TITLE_MAX)

/** 儲存前的歌名（和伺服器 common.clean_title 同規則）。 */
fun cleanTitle(s: String): String = takeCodePoints(s.replace(Regex("\\s+"), " ").trim(), TITLE_MAX).trim()

/** 以字（code point）截斷，不會把 emoji 切一半。 */
private fun takeCodePoints(s: String, n: Int) =
    if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

/** 錯誤參數（014）：{"n": 3} → mapOf("n" to 3)；沒有或不是物件時是空的。 */
fun JSONObject.intParams(key: String): Map<String, Int> =
    optJSONObject(key)?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } } ?: emptyMap()

/** 貼上的歌詞是不是 LRC（有 [mm:ss] 時間標記）；只給畫面提示用，以伺服器判斷為準。 */
fun looksLikeLrc(text: String) = Regex("""\[\d+:\d+(\.\d+)?]""").containsMatchIn(text)

/** 貼上的歌詞有幾行、最後一行是什麼（016：讓使用者確認貼上的沒有被截斷）；拿掉 LRC 時間標記、不算空行。沒有歌詞回傳 null。 */
fun lyricsSummary(text: String): Pair<Int, String>? {
    val lines = text.lines().map { it.replace(Regex("""[\[<]\d+:\d+(\.\d+)?[]>]"""), "").trim() }.filter { it.isNotEmpty() }
    return if (lines.isEmpty()) null else lines.size to lines.last()
}
