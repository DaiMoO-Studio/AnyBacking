package com.anybacking.app.data

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * 介面語言（014，research R1）：Android 13 的 per-app language。和系統設定「應用程式語言」是同一個值，系統會保存。
 * 「跟著手機」＝空的 LocaleList；資源是英文預設（values/）＋中文（values-zh/），所以任何中文地區顯示中文、其他語言顯示英文。
 */
object Lang {
    const val SYSTEM = "system"
    const val ZH = "zh-TW"
    const val EN = "en"

    fun get(ctx: Context): String {
        val l = ctx.getSystemService(LocaleManager::class.java).applicationLocales
        if (l.isEmpty) return SYSTEM
        return if (l[0].language == "zh") ZH else EN
    }

    /** 設定語言；系統會重建 Activity 套用。 */
    fun set(ctx: Context, v: String) {
        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
            if (v == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(v)
    }

    /** 目前畫面實際用的是不是中文（設定頁「目前是…」）。 */
    fun showingChinese(ctx: Context): Boolean = ctx.resources.configuration.locales[0].language == "zh"

    /** 013 把選擇存在 Prefs.lang；系統還沒有設定時搬過去一次。 */
    fun migrate(ctx: Context, prefs: Prefs) {
        val old = prefs.lang
        if (old != SYSTEM && get(ctx) == SYSTEM) set(ctx, old)
        prefs.lang = SYSTEM
    }
}
