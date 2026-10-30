package com.anybacking.app.net

import com.anybacking.app.BuildConfig
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/**
 * 自簽憑證的 SPKI 釘選。018 起值不寫在程式裡：建置時從 android/server.properties 帶入
 * （BuildConfig.PINS，逗號分隔；開發者的值＝deploy/private/edge/pins.txt），而且只用在預設伺服器（BuildConfig.DEFAULT_SERVER）。
 * 自架的人沒有設定時不釘選，https 走系統信任的憑證。
 * 自簽憑證不走公有 CA：只看葉憑證的 SPKI 是否在集合內；主機名稱仍由 OkHttp 驗證。
 */
object Pins {
    val ALL: Set<String> = BuildConfig.PINS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 這個位址要不要釘選：有釘選值、而且是建置時指定的預設伺服器。 */
    fun appliesTo(base: String): Boolean =
        ALL.isNotEmpty() && ServerConfig.host(base) == ServerConfig.host(BuildConfig.DEFAULT_SERVER)

    /** debug 版「測試錯誤釘選」：開啟時任何憑證都不信任（驗證「連線不安全」的處理）。 */
    @Volatile var breakForTest = false

    fun spki(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    fun accepts(spki: String): Boolean = !breakForTest && spki in ALL

    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("client auth not supported")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("no cert")
            leaf.checkValidity()
            if (!accepts(spki(leaf))) throw CertificateException(PIN_MISMATCH)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    const val PIN_MISMATCH = "spki pin mismatch"
}
