package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.anybacking.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anybacking.app.data.coverInitial

/*
 * B 版共用元件（013）：所有操作都是看得見的按鈕、觸控目標至少 44 dp（FR-003）。
 */

/** 霓虹光暈（Android 的 shadow 可以帶顏色）。 */
fun Modifier.glow(color: Color, radius: Dp = 16.dp, shape: androidx.compose.ui.graphics.Shape = CircleShape) =
    shadow(radius, shape, clip = false, ambientColor = color, spotColor = color)

/** 圓形圖示鈕：玻璃底（或實心）＋細外框。 */
@Composable
fun RoundIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: Dp = 48.dp, iconSize: Dp = 22.dp, fill: Color = Palette.glass, tint: Color = Palette.text,
    border: Color? = Palette.line, enabled: Boolean = true,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(iconSize)) }
}

/** 圓形大鈕，下面有小字（唱歌畫面的「原唱」「重複」）。 */
@Composable
fun LabeledRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, activeColor: Color = Palette.accent) {
    Column(
        Modifier.size(64.dp).clip(CircleShape)
            .background(if (active) activeColor.copy(alpha = 0.22f) else Palette.glass)
            .border(1.dp, if (active) activeColor else Palette.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) activeColor else Palette.text, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 11.sp, color = if (active) activeColor else Palette.text)
    }
}

/** 實心膠囊按鈕（主要動作）：底色預設白，字色自動挑對比高的。 */
@Composable
fun NeonButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.White,
    glowColor: Color? = null, enabled: Boolean = true, height: Dp = 52.dp, fontSize: Int = 16,
) {
    Box(
        modifier.heightIn(min = height)
            .then(if (glowColor != null && enabled) Modifier.glow(glowColor, 14.dp, RoundedCornerShape(50)) else Modifier)
            .clip(RoundedCornerShape(50)).background(if (enabled) color else Palette.glassHi)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) onColor(color) else Palette.faint, fontSize = fontSize.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** 外框膠囊按鈕（次要動作）。 */
@Composable
fun GhostButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.text,
    borderColor: Color = Palette.line, enabled: Boolean = true, height: Dp = 44.dp, fill: Color = Color.Transparent,
    icon: ImageVector? = null,
) {
    Row(
        modifier.heightIn(min = height).clip(RoundedCornerShape(50)).background(fill)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp).alpha(if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Box(Modifier.width(6.dp)) }
        Text(text, color = color, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Key／速度的膠囊加減鈕：− 值（小標籤）+。 */
@Composable
fun PillStepper(value: String, label: String, onMinus: () -> Unit, onPlus: () -> Unit, minusDesc: String, plusDesc: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(50)).background(Palette.glass).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperKey("−", minusDesc, onMinus)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 18.sp)
            Text(label, fontSize = 11.sp, color = Palette.dim, lineHeight = 13.sp)
        }
        StepperKey("+", plusDesc, onPlus)
    }
}

@Composable
private fun StepperKey(text: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 22.sp) }
}

/** 歌曲封面：歌曲色漸層＋歌名第一個字。 */
@Composable
fun SongCover(id: String, title: String, size: Dp = 60.dp, glowOn: Boolean = false, dim: Boolean = false) {
    val c = songColor(id)
    Box(
        Modifier.size(size)
            .then(if (glowOn && !dim) Modifier.glow(c.main.copy(alpha = 0.5f), 12.dp, RoundedCornerShape(14.dp)) else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .background(if (dim) Brush.linearGradient(listOf(Color(0xFF3A3A48), Color(0xFF5A5A6E))) else Brush.linearGradient(listOf(c.main, c.deep))),
        contentAlignment = Alignment.Center,
    ) { Text(coverInitial(title), fontSize = (size.value * 0.43f).sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1) }
}

/** 篩選膠囊（全部、最近唱過）。 */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clickable(role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(36.dp).clip(RoundedCornerShape(50))
                .background(if (selected) Color.White else Color.Transparent)
                .border(1.dp, if (selected) Color.White else Palette.line, RoundedCornerShape(50))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (selected) Palette.bg else Palette.text) }
    }
}

/** 小提示條：圓點＋文字＋（可選）按鈕或 ✕；整條可點。 */
@Composable
fun NoticeBar(
    text: String, modifier: Modifier = Modifier, color: Color = Palette.text, dot: Color? = null,
    action: String? = null, onAction: (() -> Unit)? = null, onClick: (() -> Unit)? = null, onClose: (() -> Unit)? = null,
) {
    val tint = dot ?: color
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (dot != null) dot.copy(alpha = 0.10f) else Palette.glass)
            .border(1.dp, if (dot != null) dot.copy(alpha = 0.45f) else Palette.line, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (dot != null) Box(Modifier.size(10.dp).glow(tint, 6.dp).clip(CircleShape).background(tint))
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), color = color, fontSize = 14.sp, lineHeight = 19.sp)
        if (action != null && onAction != null) Box(
            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.22f))
                .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(action, color = if (dot != null) Color(0xFFFFE9A6) else Palette.text, fontSize = 13.sp) }
        if (onClose != null) RoundIconButton(AppIcons.Close, stringResource(R.string.common_close_hint), onClose, size = 44.dp, iconSize = 14.dp, fill = Color.Transparent, border = null, tint = Palette.dim)
    }
}

/** 區段標題（設定頁）。 */
@Composable
fun SectionLabel(text: String, color: Color = Palette.accent) {
    Text(text, Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
}

/** 玻璃感面板。 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, border: Color = Color(0x1AFFFFFF), corner: Dp = 22.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(corner)).background(Color(0x0FFFFFFF))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(corner)),
        content = content,
    )
}

/** 每頁頂端：返回圓鈕＋標題（可選副標）＋右邊的動作。 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) RoundIconButton(AppIcons.Back, stringResource(R.string.common_back), onBack, size = 44.dp, iconSize = 20.dp, fill = Palette.glassHi, border = null)
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Palette.dim, maxLines = 1)
        }
        actions()
    }
}

/** 底部面板頂端的小把手。 */
@Composable
fun BoxScope.SheetHandle() {
    Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)))
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)
