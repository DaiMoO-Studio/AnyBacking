package com.anybacking.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.zip.CRC32

/** 一首歌的顏色：主色（霓虹）＋漸層伴色（深）。 */
data class SongColor(val main: Color, val deep: Color)

/** 8 色霓虹調色盤（research R2）。 */
private val SONG_COLORS = listOf(
    0xFFFF4FD8 to 0xFF6B3BFF,
    0xFF4FB4FF to 0xFF2C3DD6,
    0xFF2FD08A to 0xFF0E6B5C,
    0xFFFF7A3D to 0xFFC2185B,
    0xFFB07CFF to 0xFF4B2BC8,
    0xFFFFD84D to 0xFFE0603D,
    0xFF3DF5C8 to 0xFF1F6FA6,
    0xFFFF5B7A to 0xFF7A1F6B,
).map { (a, b) -> SongColor(Color(a), Color(b)) }

/** 依歌曲 id 固定挑一個顏色（同一首在封面、唱歌畫面都一樣）。 */
fun songColor(id: String): SongColor = SONG_COLORS[(CRC32().apply { update(id.toByteArray()) }.value % SONG_COLORS.size).toInt()]

/** 底色上的文字顏色：白字和深色字挑對比高的那個（FR-004；霓虹粉配白字只有 2.9:1）。 */
fun onColor(c: Color): Color {
    val l = c.luminance()
    val white = 1.05f / (l + 0.05f)
    val ink = (l + 0.05f) / (Palette.ink.luminance() + 0.05f)
    return if (ink >= white) Palette.ink else Color.White
}
