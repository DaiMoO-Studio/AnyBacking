package com.anybacking.app.net

import android.content.ContentResolver
import android.net.Uri
import com.anybacking.app.data.DeviceKey
import com.anybacking.app.data.Song
import com.anybacking.app.data.SongDetail
import com.anybacking.app.data.intParams
import com.anybacking.app.data.optStringOrNull
import com.anybacking.app.player.CorrectionLine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/** App 會遇到的錯誤（處理方式見 specs/android/contracts/client-behavior.md）。 */
sealed class ApiError(msg: String) : Exception(msg) {
    object Unauthorized : ApiError("這支手機的金鑰已失效，請重新設定")
    class RateLimited(val retryAfterS: Int) : ApiError("嘗試次數過多，請 $retryAfterS 秒後再試")
    object NotFound : ApiError("找不到這首歌")
    object Insecure : ApiError("連線不安全：伺服器憑證不符，已停止連線")
    /** 伺服器的 4xx／5xx：[errCode]＋[params] 是錯誤代碼（014，specs/backend/contracts/errors.md），畫面依語言顯示；[message] 是伺服器附的中文。 */
    class Http(val code: Int, detail: String, val errCode: String? = null, val params: Map<String, Int> = emptyMap()) : ApiError(detail)
    class Network(cause: Throwable) : ApiError("連不上伺服器，請檢查網路")
    object FileUnreadable : ApiError("讀不到檔案")
}

/**
 * 後端 API。伺服器位址由 [server] 提供（018：設定頁可填；預設是建置時的 BuildConfig.DEFAULT_SERVER）。
 * 所有連線（含播放的簽名連結）走同一個 [http]；預設伺服器有釘選值時才釘選（[Pins.appliesTo]），其他用系統憑證。
 * 同步方法，請在 IO 執行緒呼叫。
 */
class Api(private val key: DeviceKey, private val server: () -> String) {
    companion object {
        const val MAX_UPLOAD = 300L shl 20
    }

    /** 收到 401 時呼叫（App 導回設定畫面）。 */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    /** 目前的伺服器位址（scheme://host[:port]）。 */
    val base: String get() = server()

    @Volatile private var cached: Pair<String, OkHttpClient>? = null

    /** 目前伺服器的連線；換了伺服器就換一個（釘選與否依位址而定）。 */
    val http: OkHttpClient get() {
        val b = base
        cached?.let { if (it.first == b) return it.second }
        return clientFor(b).also { cached = b to it }
    }

    private fun clientFor(b: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        if (!Pins.appliesTo(b)) return builder.build()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(Pins.trustManager), SecureRandom()) }
        return builder
            .sslSocketFactory(ssl.socketFactory, Pins.trustManager)
            // TLS session 恢復時不會再呼叫 TrustManager：每個請求都再比對一次伺服器憑證的 SPKI（播放連結也走這裡）
            .addNetworkInterceptor { chain ->
                // 直接讀 SSLSession：OkHttp 的 handshake().peerCertificates 會先做憑證鏈清理，
                // 搭配自訂 TrustManager 時清理失敗會回傳空清單
                val session = (chain.connection()?.socket() as? SSLSocket)?.session
                val leaf = runCatching { session?.peerCertificates?.firstOrNull() as? X509Certificate }.getOrNull()
                if (leaf == null || !Pins.accepts(Pins.spki(leaf))) throw SSLPeerUnverifiedException(Pins.PIN_MISMATCH)
                chain.proceed(chain.request())
            }
            .build()
    }

    private val uploadHttp get() = http.newBuilder().writeTimeout(10, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()

    private fun req(path: String, k: String = key.get(), b: String = base) =
        Request.Builder().url(b + path).header("Authorization", "Bearer $k")

    private fun <T> call(client: OkHttpClient, r: Request, notifyUnauthorized: Boolean = true, body: (String) -> T): T {
        val resp: Response = try {
            client.newCall(r).execute()
        } catch (e: SSLException) {
            throw if (isPinFailure(e)) ApiError.Insecure else ApiError.Network(e)
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            when (it.code) {
                in 200..299 -> return body(text)
                401 -> { if (notifyUnauthorized) onUnauthorized?.invoke(); throw ApiError.Unauthorized }
                404 -> throw ApiError.NotFound
                429 -> throw ApiError.RateLimited(it.header("Retry-After")?.toIntOrNull() ?: 60)
                else -> throw ApiError.Http(it.code, detail(text) ?: "伺服器錯誤（${it.code}）",
                    runCatching { JSONObject(text).optStringOrNull("code") }.getOrNull(),
                    runCatching { JSONObject(text).intParams("params") }.getOrDefault(emptyMap()))
            }
        }
    }

    private fun isPinFailure(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c.message == Pins.PIN_MISMATCH) return true
            c = c.cause
        }
        return false
    }

    private fun detail(text: String) = runCatching { JSONObject(text).optString("detail").ifEmpty { null } }.getOrNull()

    /** 用指定的金鑰與伺服器試連一次（設定畫面用；401 不觸發全域導回）。 */
    fun verify(k: String, b: String = base) { call(clientFor(b), req("/api/songs", k, b).get().build(), notifyUnauthorized = false) { } }

    /** 設定頁的伺服器狀態（013）：健康檢查不需要金鑰；連得上（含釘選通過）就是 true。 */
    fun health(): Boolean = runCatching {
        call(http, Request.Builder().url("$base/api/health").get().build(), notifyUnauthorized = false) { }
    }.isSuccess

    fun listSongs(): List<Song> = call(http, req("/api/songs").get().build()) { t ->
        val a = JSONArray(t); (0 until a.length()).map { Song.parse(a.getJSONObject(it)) }
    }

    fun getSong(id: String): SongDetail = call(http, req("/api/songs/$id").get().build()) { SongDetail.parse(JSONObject(it)) }

    fun deleteSong(id: String) { call(http, req("/api/songs/$id").delete().build()) { } }

    /** 自動找歌詞（012）：伺服器用歌名查 LRCLIB，回傳正體 LRC；找不到是 [ApiError.NotFound]。 */
    fun searchLyrics(id: String, q: String): String {
        val url = "/api/songs/$id/lyrics/search?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")
        return call(http, req(url).get().build()) { JSONObject(it).getString("lrc") }
    }

    /** 換一份歌詞（004）：純文字或 LRC；回傳工作 id（之後和校正一樣看 correction）。409／422 的原因在 [ApiError.Http] 的訊息裡。 */
    fun replaceLyrics(id: String, lyrics: String): Int {
        val body = JSONObject().put("lyrics", lyrics).toString().toRequestBody("application/json".toMediaType())
        return call(http, req("/api/songs/$id/lyrics").post(body).build()) { JSONObject(it).getInt("id") }
    }

    /** 改歌名（006）；回傳伺服器整理後的名字。422 的原因在 [ApiError.Http] 的訊息裡。 */
    fun renameSong(id: String, title: String): String {
        val body = JSONObject().put("title", title).toString().toRequestBody("application/json".toMediaType())
        return call(http, req("/api/songs/$id").patch(body).build()) { JSONObject(it).getString("title") }
    }

    /** 送出歌詞校正（003）；回傳校正 id。409／422 的原因在 [ApiError.Http] 的訊息裡。 */
    fun submitCorrection(id: String, lines: List<CorrectionLine>): Int {
        val arr = JSONArray()
        lines.forEach {
            arr.put(JSONObject().put("src", it.src).put("text", it.text).put("start", it.start)
                .put("pinned", it.pinned).put("edited", it.edited).put("moved", it.moved)
                .put("exact", it.exact).put("confirmed", it.confirmed))
        }
        val body = JSONObject().put("lines", arr).toString().toRequestBody("application/json".toMediaType())
        return call(http, req("/api/songs/$id/corrections").post(body).build()) { JSONObject(it).getInt("id") }
    }

    /** 播放連結的完整網址（簽名連結本身就是授權，不帶金鑰）。 */
    fun mediaUrl(path: String) = base + path

    /** 上傳；回傳歌曲 id。onProgress(已送出, 總大小)。 */
    /** 網址上傳（009，個人使用）：伺服器下載音訊；歌名可留空（用影片標題）。回傳歌曲 id。 */
    /** 貼上網址後先看是哪支影片（013）：標題、頻道、長度（秒）、是否超過 15 分鐘。 */
    data class UrlPreview(val title: String, val channel: String, val duration: Double, val tooLong: Boolean)

    fun urlPreview(url: String): UrlPreview {
        val path = "/api/url-preview?url=" + java.net.URLEncoder.encode(url.trim(), "UTF-8")
        return call(http, req(path).get().build()) {
            val o = JSONObject(it)
            UrlPreview(o.optString("title"), o.optString("channel"), o.optDouble("duration", 0.0), o.optBoolean("too_long", false))
        }
    }

    fun uploadUrl(url: String, title: String?, lyrics: String?): String {
        val o = JSONObject().put("url", url.trim())
        if (!title.isNullOrBlank()) o.put("title", title.trim())
        if (!lyrics.isNullOrBlank()) o.put("lyrics", lyrics.trim())
        return call(http, req("/api/songs/url").post(o.toString().toRequestBody("application/json".toMediaType())).build()) {
            JSONObject(it).getString("id")
        }
    }

    fun upload(
        resolver: ContentResolver, uri: Uri, size: Long, fileName: String,
        title: String?, lyrics: String?, onProgress: (Long, Long) -> Unit,
    ): String {
        val type = resolver.getType(uri)?.toMediaTypeOrNull()
        val file = object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val src = resolver.openInputStream(uri) ?: throw ApiError.FileUnreadable
                src.source().use { s ->
                    var sent = 0L
                    while (true) {
                        val n = s.read(sink.buffer, 64 * 1024)
                        if (n < 0) break
                        sink.emit()
                        sent += n
                        onProgress(sent, size)
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, file)
            .apply {
                if (!title.isNullOrBlank()) addFormDataPart("title", title.trim())
                if (!lyrics.isNullOrBlank()) addFormDataPart("lyrics", lyrics.trim())
            }.build()
        return call(uploadHttp, req("/api/songs").post(body).build()) { JSONObject(it).getString("id") }
    }
}
