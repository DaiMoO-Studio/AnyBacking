package com.anybacking.app.player

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.anybacking.app.App
import com.anybacking.app.R
import com.anybacking.app.ui.errorCodeText
import com.anybacking.app.ui.userText
import com.anybacking.app.data.PlayPrefs
import com.anybacking.app.data.SongDetail
import com.anybacking.app.data.PlayRecord
import com.anybacking.app.data.LoopMark
import com.anybacking.app.data.markLoopEnd
import com.anybacking.app.data.markLoopStart
import com.anybacking.app.data.splitArtist
import com.anybacking.app.net.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放畫面的狀態（跟著畫面建立、離開畫面就釋放播放器）：播放資料、每首的偏好（Key／速度／原唱／循環）、
 * 連結過期或斷線時從原位置接續。ExoPlayer 只在主執行緒操作。
 */
@UnstableApi
class PlayerController(private val app: App, val songId: String, private val scope: CoroutineScope) {
    val kp = KaraokePlayer(app, app.api.http)

    var detail by mutableStateOf<SongDetail?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var prefs by mutableStateOf(app.prefs.play(songId)); private set
    var isPlaying by mutableStateOf(false); private set

    /** 設定循環中：null＝沒在選；-1＝等第一句；≥0＝已選起點句。 */
    var loopPick by mutableStateOf<Int?>(null)

    init {
        kp.player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; if (!playing) saveRecord() }
        })
        kp.refreshing = { pos -> scope.launch { delay(1500); load(pos, kp.player.playWhenReady) } }
        kp.onError = { error = it }
        kp.onLoopCleared = { saveLoop(null) }
    }

    /**
     * 斷網太久、重試用完時播放器會停在 IDLE（錯誤）；網路恢復時若使用者原本在播（playWhenReady 仍是 true），
     * 自動重拿連結、從原位置接續，不必手動按播放。
     */
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                val p = kp.player
                if (detail != null && p.playbackState == Player.STATE_IDLE && p.playWhenReady) {
                    error = app.getString(R.string.pc_net_back)
                    load(p.currentPosition, true)
                }
            }
        }
    }

    /** [startAt]：從這個位置開始（繼續唱卡片，013）；null＝從頭。 */
    /** 背景播放的 MediaSession（016，[PlaybackService]）。 */
    private var playback: PlaybackService.Handle? = null

    fun open(startAt: Double? = null) {
        playback = PlaybackService.attach(app, kp.player, songId)
        connectivity.registerDefaultNetworkCallback(netCallback)
        scope.launch { load(((startAt ?: 0.0) * 1000).toLong(), true) }
        scope.launch { while (true) { delay(500); track() } }
    }

    // ---------- 播放紀錄（013，specs/android/013-ui-redesign/data-model.md） ----------

    /** 累計實際播放的秒數（媒體時間；跳轉不算），到 10 秒才算唱過、開始寫入。 */
    private var played = app.prefs.record(songId)?.played ?: 0.0
    private var lastPos = -1.0
    private var sinceSave = 0.0

    private fun track() {
        val pos = kp.player.currentPosition / 1000.0
        if (isPlaying && lastPos >= 0) {
            val d = pos - lastPos
            if (d > 0 && d < 3) { played += d; sinceSave += d }
        }
        lastPos = pos
        if (sinceSave >= 5) saveRecord()
    }

    /** 播放中每 5 秒、暫停、離開畫面時寫入。 */
    private fun saveRecord() {
        sinceSave = 0.0
        if (played < PlayRecord.PLAYED_MIN || detail == null) return
        app.prefs.saveRecord(songId, PlayRecord(System.currentTimeMillis(), kp.player.currentPosition / 1000.0, played))
    }

    private suspend fun load(positionMs: Long, play: Boolean) {
        val d = try {
            withContext(Dispatchers.IO) { app.api.getSong(songId) }
        } catch (e: ApiError) {
            error = e.userText(app); return
        }
        val path = d.playbackPath
        if (path == null) { error = app.getString(R.string.pc_not_ready); return }
        detail = d
        error = null
        // 上次離開時還在重新對齊：回來就接著等結果
        d.correction?.takeIf { it.status == "queued" || it.status == "processing" }?.let { c ->
            if (pendingCorrection == null) { pendingCorrection = c.id; scope.launch { waitCorrection(c.id) } }
        }
        kp.setVocal(if (prefs.vocalOn) prefs.vocalGain else 0f)
        kp.setKeyAndSpeed(prefs.semitones, prefs.speed)
        val (artist, name) = splitArtist(d.song.title)
        // 沒有歌手時通知列會顯示 "null"（系統媒體控制），改放 App 名稱
        kp.load(app.api.mediaUrl(path), d.channels, positionMs, play, name, artist.ifEmpty { app.getString(R.string.app_name) })
        // 上次的循環區段：載入後才設定（設定時會跳到區段起點）
        val l = prefs.loopStart; val e = prefs.loopEnd
        if (l != null && e != null && kp.loop == null) kp.setLoop(l to e)
    }

    private var keySpeedJob: Job? = null

    private fun update(p: PlayPrefs) {
        val keySpeedChanged = p.semitones != prefs.semitones || p.speed != prefs.speed
        prefs = p
        app.prefs.savePlay(songId, p)
        kp.setVocal(if (p.vocalOn) p.vocalGain else 0f)   // 原唱立即生效
        if (keySpeedChanged) {
            // 每次改 Key／速度播放器都要重新處理音訊（會有一小段空白）：連按時等停手 300 ms 再一次套用
            keySpeedJob?.cancel()
            keySpeedJob = scope.launch { delay(300); kp.setKeyAndSpeed(prefs.semitones, prefs.speed) }
        }
    }

    fun setVocalOn(on: Boolean) = update(prefs.copy(vocalOn = on))
    fun setVocalGain(g: Float) = update(prefs.copy(vocalGain = g, vocalOn = true))
    fun stepKey(d: Int) = update(prefs.copy(semitones = (prefs.semitones + d).coerceIn(-6, 6)))
    /** 速度 0.05 一格（四捨五入到格子，避免浮點誤差累積）。 */
    fun stepSpeed(d: Float) = update(prefs.copy(speed = (Math.round((prefs.speed + d) * 20) / 20f).coerceIn(0.5f, 1.5f)))
    fun resetKeySpeed() = update(prefs.copy(semitones = 0, speed = 1f))

    fun togglePlay() {
        val p = kp.player
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) { p.pause(); return }
        error = null
        if (p.playbackState == Player.STATE_IDLE) p.prepare()   // 播放錯誤後播放器停在 IDLE，要重新 prepare 才能繼續
        p.play()
    }

    fun pause() = kp.player.pause()

    fun seekTo(sec: Double) = kp.seekTo(sec)

    /** 點一句歌詞：設定循環中就當作選起訖，否則跳到那一句。 */
    fun tapLine(i: Int) {
        val d = detail ?: return
        when (val pick = loopPick) {
            null -> kp.seekTo(d.lines[i].start)
            -1 -> loopPick = i
            else -> {
                val r = LyricsTimeline.loopRange(d.lines, pick, i)
                loopPick = null
                kp.setLoop(r)
                saveLoop(r)
            }
        }
    }

    // ---------- 歌詞校正（003，specs/android/contracts/client-behavior.md） ----------

    /** 校正模式的草稿；null＝一般模式。 */
    var draft by mutableStateOf<CorrectionDraft?>(null); private set
    /** 送出後等待伺服器重新對齊的校正 id。 */
    var pendingCorrection by mutableStateOf<Int?>(null); private set
    /** 校正模式裡的短訊息（順序不對、送出失敗等）。 */
    var correctionMsg by mutableStateOf<String?>(null)
    var showThanks by mutableStateOf(false); private set
    var unsureHintOff by mutableStateOf(app.prefs.unsureHintOff(songId)); private set
    /** App 在前景才輪詢（背景不輪詢，client-behavior.md）。 */
    @Volatile var foreground = true

    fun dismissUnsureHint() { unsureHintOff = true; app.prefs.setUnsureHintOff(songId) }

    fun enterCorrection() {
        val d = detail ?: return
        loopPick = null
        correctionMsg = null
        draft = CorrectionDraft(d.editLines)
    }

    /** 離開校正模式（有未送出的修改時由畫面先確認）。 */
    fun exitCorrection() { draft = null; correctionMsg = null }

    private fun editDraft(f: (CorrectionDraft) -> CorrectionDraft) {
        val d = draft ?: return
        if (pendingCorrection != null) { correctionMsg = app.getString(R.string.pc_realign_busy); return }
        try { draft = f(d); correctionMsg = null } catch (e: IllegalArgumentException) { correctionMsg = (e as? DraftError)?.let { app.getString(it.res) } ?: e.message }
    }

    /** 「設為現在」：把句首設在目前播放位置（原曲時間）。 */
    fun setNow(i: Int) = editDraft { it.pin(i, kp.player.currentPosition / 1000.0) }
    /** 從目前的句首加減 d 秒，並從新時間前 2 秒試聽。 */
    fun nudge(i: Int, d: Double) { editDraft { it.nudge(i, d) }; if (correctionMsg == null) preview(i) }
    fun confirmLine(i: Int) = editDraft { it.confirm(i) }
    fun confirmAllUnsure() = editDraft { it.confirmAllUnsure() }
    fun revertLine(i: Int) = editDraft { it.revert(i) }
    fun editLine(i: Int, text: String) = editDraft { it.edit(i, text) }
    fun deleteLine(i: Int) = editDraft { it.delete(i) }

    /** 從這句前 2 秒試聽。 */
    fun preview(i: Int) {
        val l = draft?.lines?.getOrNull(i) ?: return
        if (l.sung) { kp.seekTo(maxOf(0.0, l.start - 2.0)); kp.player.play() }
    }

    /** 換一份歌詞（004）送出中／失敗原因（給對話框顯示）。 */
    var replaceBusy by mutableStateOf(false); private set
    var replaceError by mutableStateOf<String?>(null)

    /** 自動找歌詞（012）：用歌名查 LRCLIB，找到就交給對話框填進文字框（不自動送出）。 */
    var lyricsSearchBusy by mutableStateOf(false); private set
    var lyricsSearchMsg by mutableStateOf<String?>(null)

    fun searchLyrics(q: String, onFound: (String) -> Unit) {
        if (lyricsSearchBusy || q.isBlank()) return
        lyricsSearchBusy = true
        lyricsSearchMsg = null
        scope.launch {
            try {
                val lrc = withContext(Dispatchers.IO) { app.api.searchLyrics(songId, q) }
                onFound(lrc)
                lyricsSearchMsg = app.getString(R.string.replace_found)
            } catch (e: ApiError.NotFound) {
                lyricsSearchMsg = app.getString(R.string.err_lyrics_not_found)
            } catch (e: ApiError) {
                lyricsSearchMsg = e.userText(app)
            } finally {
                lyricsSearchBusy = false
            }
        }
    }

    /** 換一份歌詞：送出成功就關對話框、離開校正模式，之後和校正一樣等結果、換上新歌詞＋感謝文字。 */
    fun replaceLyrics(text: String, onSent: () -> Unit) {
        if (pendingCorrection != null || replaceBusy) return
        replaceBusy = true
        replaceError = null
        scope.launch {
            val id = try {
                withContext(Dispatchers.IO) { app.api.replaceLyrics(songId, text) }
            } catch (e: ApiError) {
                replaceError = e.userText(app); replaceBusy = false; return@launch
            }
            replaceBusy = false
            draft = null
            correctionMsg = null
            onSent()
            pendingCorrection = id
            waitCorrection(id)
        }
    }

    fun submitCorrection() {
        val d = draft ?: return
        if (!d.dirty || pendingCorrection != null) return
        correctionMsg = null
        scope.launch {
            val id = try {
                withContext(Dispatchers.IO) { app.api.submitCorrection(songId, d.payload()) }
            } catch (e: ApiError) {
                correctionMsg = app.getString(R.string.pc_send_failed, e.userText(app)); return@launch
            }
            pendingCorrection = id
            waitCorrection(id)
        }
    }

    /** 每 2 秒查一次，直到這筆校正完成或失敗；完成就換上新歌詞（不重新載入音訊，播放位置不變）。 */
    private suspend fun waitCorrection(id: Int) {
        while (pendingCorrection == id) {
            delay(2000)
            if (!foreground) continue
            val d = try {
                withContext(Dispatchers.IO) { app.api.getSong(songId) }
            } catch (e: ApiError) {
                if (e is ApiError.NotFound || e is ApiError.Unauthorized) { pendingCorrection = null; correctionMsg = e.userText(app) }
                continue   // 網路錯誤：下次再查
            }
            val c = d.correction ?: continue
            if (c.id != id) continue
            when (c.status) {
                "done" -> {
                    detail = d
                    draft = null
                    pendingCorrection = null
                    showThanks = true
                    scope.launch { delay(4000); showThanks = false }
                }
                "failed" -> { pendingCorrection = null; correctionMsg = errorCodeText(app, c.errorCode, c.errorParams) ?: c.error ?: app.getString(R.string.pc_realign_failed) }
            }
        }
    }

    // ---------- 波浪選段（015） ----------

    /** 按了「從這裡開始」、還沒按結束的起點（秒）。 */
    var loopMark by mutableStateOf<Double?>(null); private set
    /** 選段的提示（太短、還沒有起點）。 */
    var loopMarkMsg by mutableStateOf<String?>(null); private set

    fun markStart() = applyMark(markLoopStart(kp.player.currentPosition / 1000.0, kp.loop))
    fun markEnd() = applyMark(markLoopEnd(kp.player.currentPosition / 1000.0, loopMark, kp.loop))

    private fun applyMark(m: LoopMark) {
        loopPick = null
        when (m) {
            is LoopMark.Set -> { loopMark = null; loopMarkMsg = null; kp.setLoop(m.start to m.end); saveLoop(m.start to m.end) }
            is LoopMark.Pending -> { loopMark = m.start; loopMarkMsg = null }
            LoopMark.NeedStart -> loopMarkMsg = app.getString(R.string.repeat_need_start)
            LoopMark.TooShort -> loopMarkMsg = app.getString(R.string.repeat_too_short)
        }
    }

    fun clearLoop() {
        loopPick = null
        loopMark = null
        loopMarkMsg = null
        kp.clearLoop()
        saveLoop(null)
    }

    private fun saveLoop(r: Pair<Double, Double>?) {
        prefs = prefs.copy(loopStart = r?.first, loopEnd = r?.second)
        app.prefs.savePlay(songId, prefs)
    }

    fun release() {
        track(); saveRecord()
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        playback?.detach(app); playback = null
        kp.release()
    }
}
