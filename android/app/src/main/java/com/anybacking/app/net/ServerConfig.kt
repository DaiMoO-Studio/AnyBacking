package com.anybacking.app.net

import java.net.URI

/**
 * 伺服器位址（018 自架）：設定頁填的位址整理與檢查（純邏輯，有單元測試）。
 * https 任何主機都可以；http 只允許私人網路（區網、Tailscale、本機）——自架的人常用區網位址，
 * 但不能讓金鑰在公開網路上用明文傳。
 */
object ServerConfig {
    /** 「192.168.1.20:8000」「https://example.com/」→ 正規化成「scheme://host[:port]」；格式不對回傳 null。 */
    fun normalize(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in s) s else {
            val host = s.substringBefore('/').substringBefore(':')
            (if (isPrivateHost(host)) "http://" else "https://") + s
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!uri.path.isNullOrEmpty() && uri.path != "/") return null   // 只要主機，API 路徑由 App 加
        return "$scheme://${host.lowercase()}" + (if (uri.port > 0) ":${uri.port}" else "")
    }

    /** 可以用的位址：https 都可以，http 只限私人網路。 */
    fun allowed(base: String): Boolean {
        val uri = runCatching { URI(base) }.getOrNull() ?: return false
        return when (uri.scheme) {
            "https" -> true
            "http" -> isPrivateHost(uri.host ?: "")
            else -> false
        }
    }

    fun host(base: String): String = runCatching { URI(base).host?.lowercase() }.getOrNull().orEmpty()

    /** 區網（10/8、172.16/12、192.168/16）、Tailscale（100.64/10、*.ts.net）、本機、.local／.lan／.home.arpa。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")) return true
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 100 && p[1] in 64..127)
    }
}
