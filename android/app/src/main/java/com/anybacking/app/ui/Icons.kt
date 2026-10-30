package com.anybacking.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 設計稿用的線條圖示（24×24、圓角線條）；material-icons-core 沒有暫停、麥克風、重複等。
 * 顏色用 Icon 的 tint 決定（這裡的黑色只是佔位）。
 */
object AppIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    private fun fill(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()

    val Back = stroke("back", "M15 6l-6 6 6 6", width = 2.2f)
    val Down = stroke("down", "M6 9l6 6 6-6", width = 2.2f)
    val Search = stroke("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4")
    val Settings = stroke(
        "settings", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
    )
    val Close = stroke("close", "M6 6l12 12M18 6L6 18", width = 2.4f)
    val Plus = stroke("plus", "M12 5v14M5 12h14", width = 2.4f)
    val More = fill("more", "M5 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", "M12 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", "M19 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z")
    val Edit = stroke("edit", "M4 20h4L19 9l-4-4L4 16z")
    val Mic = stroke("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0M12 18v3")
    val Repeat = stroke("repeat", "M17 2l4 4-4 4", "M3 11V9a3 3 0 0 1 3-3h15", "M7 22l-4-4 4-4", "M21 13v2a3 3 0 0 1-3 3H3")
    val Play = fill("play", "M8 5v14l11-7z")
    val Pause = fill("pause", "M7 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M15 5h3a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z")
    val Phone = stroke("phone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2")
    val Link = stroke("link", "M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.5 1.5", "M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.5-1.5")
    val Chevron = stroke("chevron", "M9 6l6 6-6 6")
}
