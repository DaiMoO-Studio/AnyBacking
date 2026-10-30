package com.anybacking.app.data

import android.content.Context
import com.anybacking.app.BuildConfig
import org.json.JSONObject

/** 每首歌的播放偏好（Key、速度、原唱、循環）。 */
data class PlayPrefs(
    val semitones: Int = 0,
    val speed: Float = 1f,
    val vocalOn: Boolean = false,
    val vocalGain: Float = 0.7f,
    val loopStart: Double? = null,
    val loopEnd: Double? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("semitones", semitones); put("speed", speed.toDouble())
        put("vocalOn", vocalOn); put("vocalGain", vocalGain.toDouble())
        loopStart?.let { put("loopStart", it) }; loopEnd?.let { put("loopEnd", it) }
    }.toString()

    companion object {
        fun parse(s: String?): PlayPrefs = runCatching {
            val o = JSONObject(s!!)
            PlayPrefs(
                semitones = o.optInt("semitones", 0).coerceIn(-6, 6),
                speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 1.5f),
                vocalOn = o.optBoolean("vocalOn", false),
                vocalGain = o.optDouble("vocalGain", 0.7).toFloat().coerceIn(0f, 1f),
                loopStart = if (o.has("loopStart")) o.getDouble("loopStart") else null,
                loopEnd = if (o.has("loopEnd")) o.getDouble("loopEnd") else null,
            )
        }.getOrDefault(PlayPrefs())
    }
}

/** 本機的小資料：播放偏好、等著發完成通知的歌、播放紀錄、最近搜尋、顯示設定。 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** 伺服器位址（018 自架：設定頁填；沒填用建置時的預設，公開版預設是空的）。 */
    var server: String
        get() = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = sp.edit().putString("server", v).apply()

    fun play(id: String) = PlayPrefs.parse(sp.getString("play_$id", null))
    fun savePlay(id: String, p: PlayPrefs) = sp.edit().putString("play_$id", p.toJson()).apply()

    val pending: Set<String> get() = sp.getStringSet("pending", emptySet())!!.toSet()
    fun addPending(id: String) = sp.edit().putStringSet("pending", pending + id).apply()
    fun removePending(id: String) = sp.edit().putStringSet("pending", pending - id).apply()

    /** 播放器的「有幾句時間是推估的」提示，使用者按 ✕ 後這首不再顯示。 */
    fun unsureHintOff(id: String) = sp.getBoolean("hint_off_$id", false)
    fun setUnsureHintOff(id: String) = sp.edit().putBoolean("hint_off_$id", true).apply()

    /** 播放紀錄（013）：繼續唱卡片、最近唱過、最後唱過文字。 */
    fun record(id: String) = PlayRecord.parse(sp.getString("last_$id", null))
    fun saveRecord(id: String, r: PlayRecord) = sp.edit().putString("last_$id", r.toJson()).apply()
    fun allRecords(): Map<String, PlayRecord> = sp.all.entries
        .filter { it.key.startsWith("last_") }
        .mapNotNull { e -> PlayRecord.parse(e.value as? String)?.let { e.key.removePrefix("last_") to it } }
        .toMap()

    /** 最近搜尋（013）。 */
    val recentSearches: List<String> get() = RecentSearches.parse(sp.getString("recent_search", null))
    fun addRecentSearch(q: String) = sp.edit().putString("recent_search", RecentSearches.toJson(RecentSearches.add(recentSearches, q))).apply()
    fun clearRecentSearches() = sp.edit().remove("recent_search").apply()

    /** 介面語言（013 只儲存，014 套用）：system／zh-TW／en。 */
    var lang: String
        get() = sp.getString("lang", "system")!!
        set(v) = sp.edit().putString("lang", v).apply()

    /** 播放鍵外圈跟著音樂跳動（013）。 */
    var pulse: Boolean
        get() = sp.getBoolean("pulse", true)
        set(v) = sp.edit().putBoolean("pulse", v).apply()

    /** 歌被刪除：清掉它的所有本機資料（residue_hint_off_ 是 005 的舊鍵，013 起不再使用）。 */
    fun forget(id: String) {
        sp.edit().remove("play_$id").remove("hint_off_$id").remove("residue_hint_off_$id").remove("last_$id")
            .putStringSet("pending", pending - id).apply()
    }
}
