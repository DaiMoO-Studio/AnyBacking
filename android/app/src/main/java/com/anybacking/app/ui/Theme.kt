package com.anybacking.app.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.anybacking.app.R

/** B 版色票（013，Claude Design 畫布「跟著歌曲換色＋霓虹」）。 */
object Palette {
    val bg = Color(0xFF08070C)
    val surface = Color(0xFF12101A)
    val glass = Color(0x14FFFFFF)        // 白 8%：玻璃感面板
    val glassHi = Color(0x1FFFFFFF)      // 白 12%：圓鈕、膠囊
    val line = Color(0x2EFFFFFF)         // 白 18%：外框
    val text = Color(0xFFFFFFFF)
    val dim = Color(0x9EFFFFFF)          // 白 62%
    val faint = Color(0x52FFFFFF)        // 白 32%
    val accent = Color(0xFFFF4FD8)       // 霓虹粉（沒有歌曲色時的強調色）
    val accent2 = Color(0xFFFF9A8A)      // 錯誤／注意文字
    val warn = Color(0xFFFFD84D)         // AI 猜的
    val ok = Color(0xFF3DF5C8)
    val danger = Color(0xFFFF8A7A)
    val ink = Color(0xFF1B0B26)          // 亮色底上的深色字
}

/**
 * 英數用打包的 Plus Jakarta Sans（OFL）；中文沒有這個字型，系統自動後備到內建的 Noto Sans CJK（research R1）。
 * 可變字重：每個字重各建一個 Font，指定 wght。
 */
@OptIn(ExperimentalTextApi::class)
val AppFont = FontFamily(
    listOf(400, 500, 700, 800, 900).map { w ->
        Font(R.font.plus_jakarta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(minOf(w, 800))))
    },
)

private val base = TextStyle(fontFamily = AppFont)

@Composable
fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.ink, secondary = Palette.accent, background = Palette.bg,
            surface = Palette.surface, surfaceContainerHigh = Palette.surface, surfaceContainerLow = Palette.surface,
            onBackground = Palette.text, onSurface = Palette.text, onSurfaceVariant = Palette.dim, error = Palette.danger,
            outline = Palette.line,
        ),
        typography = Typography().let { t ->
            Typography(
                displayLarge = t.displayLarge.merge(base), displayMedium = t.displayMedium.merge(base), displaySmall = t.displaySmall.merge(base),
                headlineLarge = t.headlineLarge.merge(base), headlineMedium = t.headlineMedium.merge(base), headlineSmall = t.headlineSmall.merge(base),
                titleLarge = t.titleLarge.merge(base), titleMedium = t.titleMedium.merge(base), titleSmall = t.titleSmall.merge(base),
                bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
                labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base), labelSmall = t.labelSmall.merge(base),
            )
        },
        content = { CompositionLocalProvider(LocalContentColor provides Palette.text, content = content) },
    )
}
