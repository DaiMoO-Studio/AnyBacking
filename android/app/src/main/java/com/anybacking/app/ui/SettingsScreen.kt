package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import com.anybacking.app.R
import com.anybacking.app.data.Lang
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anybacking.app.App
import com.anybacking.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 設定（013）：語言（014：Android per-app language，和系統「應用程式語言」同一個值）、播放鍵跟著音樂跳動、版本、伺服器狀態、授權與致謝。 */
@Composable
fun SettingsScreen(modifier: Modifier, onBack: () -> Unit, onLicenses: () -> Unit, onServer: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    var lang by remember { mutableStateOf(Lang.get(ctx)) }
    var pulse by remember { mutableStateOf(app.prefs.pulse) }
    var server by remember { mutableStateOf<Boolean?>(null) }   // null＝檢查中
    LaunchedEffect(Unit) { server = withContext(Dispatchers.IO) { app.api.health() } }

    Column(modifier.verticalScroll(rememberScrollState())) {
        TopBar("", onBack = onBack)
        Text(stringResource(R.string.set_title), Modifier.padding(start = 24.dp, top = 4.dp, bottom = 18.dp), fontSize = 36.sp, fontWeight = FontWeight.Black)
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.set_language))
                GlassCard(Modifier.selectableGroup()) {
                    // 語言名稱用該語言本身書寫；「目前是…」看畫面實際用的語言
                    val phone = if (Lang.showingChinese(ctx)) "繁體中文" else "English"
                    listOf(
                        Triple(Lang.SYSTEM, stringResource(R.string.set_follow_phone), stringResource(R.string.set_current, phone)),
                        Triple(Lang.ZH, "繁體中文", null),
                        Triple(Lang.EN, "English", null),
                    ).forEachIndexed { i, (v, name, sub) ->
                        if (i > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .selectable(selected = lang == v, role = Role.RadioButton) { if (lang != v) { lang = v; Lang.set(ctx, v) } }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                if (sub != null) Text(sub, fontSize = 12.sp, color = Palette.dim)
                            }
                            Radio(lang == v)
                        }
                    }
                }
                Text(stringResource(R.string.set_lang_note), Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, color = Palette.faint)
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.set_sing))
                GlassCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp)
                            .toggleable(value = pulse, role = Role.Switch) { pulse = it; app.prefs.pulse = it }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.set_pulse), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.set_pulse_sub), fontSize = 12.sp, color = Palette.dim)
                        }
                        Switch(checked = pulse, onCheckedChange = null, colors = SwitchDefaults.colors(
                            checkedTrackColor = Palette.accent, checkedThumbColor = Color.White,
                            uncheckedTrackColor = Palette.glassHi, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.set_about))
                GlassCard {
                    InfoRow(stringResource(R.string.set_version)) { Text(BuildConfig.VERSION_NAME, fontSize = 14.sp, color = Palette.dim) }
                    Divider()
                    // 018 自架：點這列改伺服器位址或金鑰（設定頁）
                    InfoRow(stringResource(R.string.set_server), onClick = onServer) {
                        val (c, t) = when (server) { null -> Palette.dim to stringResource(R.string.set_checking); true -> Palette.ok to stringResource(R.string.set_connected); false -> Palette.accent2 to stringResource(R.string.set_offline) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).glow(c, 6.dp).clip(CircleShape).background(c))
                            Text(t, fontSize = 13.sp, color = c)
                            Icon(AppIcons.Chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp))
                        }
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onLicenses).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.set_licenses), Modifier.weight(1f), fontSize = 15.sp)
                        Icon(AppIcons.Chevron, null, tint = Palette.faint, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, onClick: (() -> Unit)? = null, value: @Composable () -> Unit) {
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).then(click).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        value()
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))

@Composable
private fun Radio(on: Boolean) {
    Box(Modifier.size(24.dp).then(if (on) Modifier.glow(Palette.accent, 8.dp) else Modifier).clip(CircleShape)
        .background(if (on) Palette.accent else Palette.faint).padding(2.dp).clip(CircleShape).background(Palette.surface),
        contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.accent))
    }
}

/** 授權與致謝（research R8）。 */
private val CREDITS = listOf(
    R.string.lic_fonts to listOf(R.string.lic_font_jakarta, R.string.lic_font_noto),
    R.string.lic_libs to listOf(R.string.lic_compose, R.string.lic_media3, R.string.lic_okhttp),
    R.string.lic_server to listOf(R.string.lic_sep, R.string.lic_sep_tool, R.string.lic_asr, R.string.lic_lrclib, R.string.lic_ffmpeg),
)

@Composable
fun LicensesScreen(modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        TopBar(stringResource(R.string.set_licenses), onBack = onBack)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.lic_intro), fontSize = 14.sp, color = Palette.dim, lineHeight = 21.sp)
            CREDITS.forEach { (title, items) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(title))
                    items.forEach { Text(stringResource(it), fontSize = 14.sp, lineHeight = 20.sp) }
                }
            }
        }
    }
}
