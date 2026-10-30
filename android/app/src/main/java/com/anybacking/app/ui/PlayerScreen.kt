package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.anybacking.app.R
import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import com.anybacking.app.App
import com.anybacking.app.data.splitArtist
import com.anybacking.app.player.LyricsTimeline
import com.anybacking.app.player.PlayerController
import kotlin.math.abs

/**
 * 唱歌畫面（013 B 版）：歌曲色漸層背景、大字滾動歌詞、波浪時間軸、原唱／播放／重複圓鈕（播放鍵外圈隨音樂跳動）、Key／速度。
 * 校正時整個畫面換成修正歌詞模式（FixMode.kt），共用同一個播放器，音樂不中斷。
 * 只在前景播放：App 離開畫面就暫停（不提供背景播放、鎖屏控制）。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(songId: String, startAt: Double?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val pc = remember(songId) { PlayerController(app, songId, scope) }
    DisposableEffect(pc) { pc.open(startAt); onDispose { pc.release() } }

    // 離開 App（Home、切 App、關螢幕）繼續播放（016 背景播放，PlaybackService）；只停掉前景才做的輪詢
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) pc.foreground = false
            if (e == Lifecycle.Event.ON_START) pc.foreground = true
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // 每一幀讀播放位置（媒體時間；變速時歌詞自然跟著縮放）
    var pos by remember { mutableDoubleStateOf(0.0) }
    var dur by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(pc) {
        while (true) withFrameNanos {
            pos = pc.kp.player.currentPosition / 1000.0
            dur = pc.kp.player.duration.takeIf { it > 0 }?.div(1000.0) ?: (pc.detail?.song?.duration ?: 0.0)
        }
    }

    val color = remember(songId) { songColor(songId) }
    if (pc.draft != null) FixMode(pc, color, pos) else SingMode(pc, color, pos, dur, pulseOn = app.prefs.pulse, onBack = onBack)
}

@OptIn(UnstableApi::class)
@Composable
private fun SingMode(pc: PlayerController, color: SongColor, pos: Double, dur: Double, pulseOn: Boolean, onBack: () -> Unit) {
    val d = pc.detail
    val lines = d?.lines.orEmpty()
    val cur = LyricsTimeline.currentIndex(lines, pos)
    var repeatSheet by remember { mutableStateOf(false) }
    BackHandler(enabled = pc.loopPick != null) { pc.loopPick = null }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to color.main.copy(alpha = 0.40f), 0.45f to Color(0xFF1B0B26), 1f to Palette.bg),
        ),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val (artist, name) = splitArtist(d?.song?.title ?: stringResource(R.string.sing_loading))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AppIcons.Down, stringResource(R.string.sing_back), onBack, size = 44.dp, iconSize = 20.dp, fill = Palette.glassHi, border = null)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artist.isNotEmpty()) Text(artist, fontSize = 12.sp, color = Palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIconButton(AppIcons.Edit, stringResource(R.string.sing_fix), pc::enterCorrection, size = 44.dp, iconSize = 18.dp, fill = Palette.glassHi, border = null,
                    enabled = d?.editLines?.isNotEmpty() == true)
            }
            Notices(pc)
            Lyrics(pc, cur, color.main, Modifier.weight(1f))
            WaveTimeline(pos, dur, color.main, onSeek = pc::seekTo, modifier = Modifier.padding(horizontal = 20.dp),
                heights = d?.waveform, loop = pc.kp.loop, mark = pc.loopMark)
            Controls(pc, color.main, pulseOn, onRepeat = { repeatSheet = true })
        }
    }
    if (repeatSheet) RepeatSheet(pc, color.main, pos, dur, onClose = { repeatSheet = false })
}

/** 歌詞上方的提示：錯誤、設定循環中、重新對齊中、已換上新時間、推估句提示（一般模式）。 */
@OptIn(UnstableApi::class)
@Composable
private fun Notices(pc: PlayerController) {
    val unsure = pc.detail?.lines?.count { !it.confident } ?: 0
    val m = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    pc.error?.let { NoticeBar(it, m, color = Palette.accent2) }
    when {
        pc.loopPick == -1 -> NoticeBar(stringResource(R.string.sing_pick_first), m, dot = Palette.accent, action = stringResource(R.string.common_cancel), onAction = { pc.loopPick = null })
        pc.loopPick != null -> NoticeBar(stringResource(R.string.sing_pick_last), m, dot = Palette.accent, action = stringResource(R.string.common_cancel), onAction = { pc.loopPick = null })
        pc.showThanks -> NoticeBar(stringResource(R.string.sing_new_timing), m, dot = Palette.ok)
        pc.pendingCorrection != null -> NoticeBar(stringResource(R.string.sing_realigning), m, color = Palette.dim)
        unsure > 0 && !pc.unsureHintOff ->
            NoticeBar(pluralStringResource(R.plurals.unsure_hint, unsure, unsure), m, dot = Palette.warn, onClick = pc::enterCorrection, onClose = pc::dismissUnsureHint)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Lyrics(pc: PlayerController, cur: Int, color: Color, modifier: Modifier) {
    val lines = pc.detail?.lines.orEmpty()
    val state = rememberLazyListState()
    // 目前句保持在上方約第三行的位置（index 0 是歌名）
    LaunchedEffect(cur) { if (lines.isNotEmpty()) state.animateScrollToItem(maxOf(0, cur + 1 - 2)) }
    val loop = pc.kp.loop

    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(top = 24.dp, bottom = 200.dp)) {
        item {
            Text(splitArtist(pc.detail?.song?.title.orEmpty()).second, Modifier.padding(horizontal = 26.dp, vertical = 10.dp),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text.copy(alpha = if (cur < 0) 0.8f else 0.32f))
        }
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            val dist = if (cur < 0) i + 1 else abs(i - cur)
            val alpha by animateFloatAsState(when (dist) { 0 -> 1f; 1 -> 0.55f; 2 -> 0.4f; else -> 0.28f }, label = "a")
            val size by animateFloatAsState(if (dist == 0) 32f else 22f, label = "s")
            val inLoop = loop != null && line.start >= loop.first - 0.01 && line.start < loop.second
            val picked = pc.loopPick != null && pc.loopPick == i
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.sing_from_line)) { pc.tapLine(i) }.padding(start = 12.dp, end = 20.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 循環區段（或剛選的起點句）左邊的標記
                Box(Modifier.width(4.dp).height(30.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (inLoop || picked) color else Color.Transparent))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (line.confident) line.text else line.text + " ≈",
                    fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight.Black,
                    color = Palette.text.copy(alpha = alpha),
                    style = if (dist == 0) TextStyle(shadow = Shadow(color, blurRadius = 28f)) else TextStyle.Default,
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Controls(pc: PlayerController, color: Color, pulseOn: Boolean, onRepeat: () -> Unit) {
    val p = pc.prefs
    val motion = remember { ValueAnimator.areAnimatorsEnabled() }
    val playDesc = stringResource(if (pc.isPlaying) R.string.common_pause else R.string.common_play)
    val volDesc = stringResource(R.string.sing_vocal_volume)
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            LabeledRoundButton(AppIcons.Mic, stringResource(R.string.sing_vocals), onClick = { pc.setVocalOn(!p.vocalOn) }, active = p.vocalOn, activeColor = color)
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                PulseRing({ pc.kp.spectrum.bands }, active = pc.isPlaying && pulseOn && motion, color = color, buttonSize = 84.dp, modifier = Modifier.size(84.dp))
                Box(
                    Modifier.size(84.dp).glow(color, 28.dp).clip(CircleShape).background(Color.White)
                        .clickable(role = Role.Button, onClick = pc::togglePlay)
                        .semantics { contentDescription = playDesc },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (pc.isPlaying) AppIcons.Pause else AppIcons.Play, null, tint = Palette.ink, modifier = Modifier.size(32.dp)) }
            }
            LabeledRoundButton(AppIcons.Repeat, stringResource(R.string.sing_repeat), onClick = onRepeat, active = pc.kp.loop != null, activeColor = color)
        }
        // 原唱音量：開原唱時就出現在按鈕下方（開發者找不到放在「重複」面板裡的滑桿）
        if (p.vocalOn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sing_vocal_volume), fontSize = 13.sp, color = Palette.dim)
            Slider(value = p.vocalGain, onValueChange = pc::setVocalGain, modifier = Modifier.weight(1f).semantics { contentDescription = volDesc },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = color, inactiveTrackColor = Color(0x2EFFFFFF)))
            Text("${(p.vocalGain * 100).toInt()}%", Modifier.width(40.dp), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillStepper(if (p.semitones > 0) "+${p.semitones}" else "${p.semitones}", "Key", { pc.stepKey(-1) }, { pc.stepKey(1) },
                stringResource(R.string.sing_key_down), stringResource(R.string.sing_key_up), Modifier.weight(1f))
            PillStepper("%.2f×".format(p.speed).replace(Regex("0×$"), "×"), stringResource(R.string.sing_speed), { pc.stepSpeed(-0.05f) }, { pc.stepSpeed(0.05f) },
                stringResource(R.string.sing_slower), stringResource(R.string.sing_faster), Modifier.weight(1f))
        }
    }
}

/** 「重複」面板：循環區段、Key／速度歸零（FR-045；原唱音量在唱歌畫面上）。 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
private fun RepeatSheet(pc: PlayerController, color: Color, pos: Double, dur: Double, onClose: () -> Unit) {
    val p = pc.prefs
    val loop = pc.kp.loop
    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surface, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.repeat_title), Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Black)
                if (loop != null) Text("${formatDuration(loop.first)} → ${formatDuration(loop.second)}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = color)
            }
            // 波浪選段（015）：邊聽邊按「從這裡開始／到這裡結束」；點波浪可以跳去找起點
            WaveTimeline(pos, dur, color, onSeek = pc::seekTo, heights = pc.detail?.waveform, loop = loop, mark = pc.loopMark, height = 64.dp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x0FFFFFFF)).padding(horizontal = 10.dp, vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(R.string.repeat_from_here), onClick = pc::markStart, modifier = Modifier.weight(1f), height = 48.dp,
                    borderColor = if (pc.loopMark != null) color else Palette.line)
                GhostButton(stringResource(R.string.repeat_to_here), onClick = pc::markEnd, modifier = Modifier.weight(1f), height = 48.dp)
                GhostButton(stringResource(R.string.repeat_off), onClick = pc::clearLoop, enabled = loop != null || pc.loopMark != null, modifier = Modifier.weight(1f), height = 48.dp,
                    fill = Palette.glassHi, borderColor = Color.Transparent)
            }
            Text(pc.loopMarkMsg ?: pc.loopMark?.let { stringResource(R.string.repeat_start_at, formatDuration(it)) } ?: stringResource(R.string.repeat_mark_hint),
                fontSize = 12.sp, color = if (pc.loopMarkMsg != null) Palette.accent2 else Palette.dim)
            GhostButton(stringResource(R.string.repeat_pick), onClick = { pc.loopPick = -1; onClose() },
                modifier = Modifier.fillMaxWidth(), height = 44.dp)
            if (p.semitones != 0 || p.speed != 1f) GhostButton(stringResource(R.string.repeat_reset), onClick = pc::resetKeySpeed, modifier = Modifier.fillMaxWidth())
            NeonButton(stringResource(R.string.common_ok), onClose, Modifier.fillMaxWidth(), glowColor = color.copy(alpha = 0.4f), height = 56.dp, fontSize = 17)
        }
    }
}
