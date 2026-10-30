package com.anybacking.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 裝置金鑰（伺服器核發的 ab_…）：用 Android Keystore 的 AES-GCM 金鑰（不可匯出）加密後存在 App 私有的
 * SharedPreferences；Manifest 已關閉備份，換手機不會帶走。
 */
class DeviceKey(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("secure", Context.MODE_PRIVATE)

    @Volatile private var cached: String? = null

    fun get(): String {
        cached?.let { return it }
        val blob = prefs.getString(PREF, null) ?: return ""
        return runCatching {
            val raw = Base64.getDecoder().decode(blob)
            val c = Cipher.getInstance(AES)
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrDefault("").also { cached = it }
    }

    fun set(key: String) {
        val c = Cipher.getInstance(AES)
        c.init(Cipher.ENCRYPT_MODE, secret())
        val out = c.iv + c.doFinal(key.toByteArray())
        prefs.edit().putString(PREF, Base64.getEncoder().encodeToString(out)).apply()
        cached = key
    }

    fun clear() {
        prefs.edit().remove(PREF).apply()
        cached = ""
    }

    val isSet get() = get().isNotEmpty()

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "anybacking-device-key"
        private const val PREF = "device_key"
        private const val AES = "AES/GCM/NoPadding"

        /** 伺服器金鑰格式：ab_ ＋ 43 個 base64url 字元。 */
        fun looksValid(k: String) = Regex("^ab_[A-Za-z0-9_-]{43}$").matches(k.trim())
    }
}
