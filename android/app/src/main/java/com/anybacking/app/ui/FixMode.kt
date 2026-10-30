package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.anybacking.app.R
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import com.anybacking.app.data.looksLikeLrc
import com.anybacking.app.data.titleInput
import com.anybacking.app.player.CorrectionDraft
import com.anybacking.app.player.DraftLine
import com.anybacking.app.player.PlayerController
import kotlin.math.abs

/**
 * 修正歌詞（013 B 版外觀；行為同 003／004）：全部句子（含沒唱的）；點一句在下面展開編輯卡片，邊聽邊點，音樂不會停。
 * 列表只標 AI 猜的、還沒處理的句子（黃色時間＋標籤）；改過的句子不加符號，卡片裡才說明改了什麼。
 */
@OptIn(UnstableApi::class)
@Composable
fun FixMode(pc: PlayerController, color: SongColor, pos: Double) {
    val draft = pc.draft ?: return
    var confirmLeave by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun leave() { if (draft.dirty && pc.pendingCorrection == null) confirmLeave = true else pc.exitCorrection() }
    BackHandler { leave() }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(color.main.copy(alpha = 0.27f), Color.Transparent))))
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(stringResource(R.string.sing_fix), onBack = ::leave, subtitle = stringResource(R.string.fix_subtitle)) {
                GhostButton(stringResource(R.string.fix_replace), onClick = { if (draft.dirty) confirmDiscard = true else replacing = true },
                    enabled = pc.pendingCorrection == null)
            }
            val m = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            when {
                pc.pendingCorrection != null -> NoticeBar(stringResource(R.string.sing_realigning), m, color = Palette.dim)
                pc.correctionMsg != null -> NoticeBar(pc.correctionMsg!!, m, color = Palette.accent2)
                draft.unsureCount > 0 -> NoticeBar(pluralStringResource(R.plurals.fix_unsure, draft.unsureCount, draft.unsureCount), m, dot = Palette.warn,
                    action = stringResource(R.string.fix_all_right), onAction = pc::confirmAllUnsure)
                else -> NoticeBar(stringResource(R.string.fix_hint), m, color = Palette.dim)
            }
            FixLines(pc, draft, pos, color.main, Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(if (pc.isPlaying) AppIcons.Pause else AppIcons.Play, if (pc.isPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play), pc::togglePlay,
                    size = 56.dp, iconSize = 20.dp, fill = Palette.glassHi, border = null)
                if (draft.dirty) NeonButton(if (pc.pendingCorrection != null) stringResource(R.string.common_sending) else pluralStringResource(R.plurals.fix_done_n, draft.changes, draft.changes), pc::submitCorrection,
                    Modifier.weight(1f), color = color.main, glowColor = color.main.copy(alpha = 0.5f), enabled = pc.pendingCorrection == null, height = 56.dp)
                else NeonButton(stringResource(R.string.fix_back_to_sing), pc::exitCorrection, Modifier.weight(1f), height = 56.dp)
            }
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.fix_unsaved_title)) },
        text = { Text(stringResource(R.string.fix_unsaved_body)) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; pc.submitCorrection() }) { Text(stringResource(R.string.common_send)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmLeave = false; pc.exitCorrection() }) { Text(stringResource(R.string.fix_discard), color = Palette.danger) }
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.fix_keep_editing)) }
            }
        },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.fix_unsaved_title)) },
        text = { Text(stringResource(R.string.fix_replace_discard)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; replacing = true }) { Text(stringResource(R.string.common_continue)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (replacing) ReplaceLyricsDialog(pc, color.main, onClose = { replacing = false })
}

@OptIn(UnstableApi::class)
@Composable
private fun FixLines(pc: PlayerController, draft: CorrectionDraft, pos: Double, color: Color, modifier: Modifier) {
    val cur = draft.currentIndex(pos)
    val state = rememberLazyListState()
    var open by remember { mutableStateOf<Int?>(null) }   // 展開的句子（src）
    var deleting by remember { mutableStateOf<Int?>(null) }
    // 展開編輯區時不自動捲動，免得正在改的句子被捲走
    LaunchedEffect(cur, open) { if (cur >= 0 && open == null) state.animateScrollToItem(maxOf(0, cur - 2)) }
    // 展開的句子捲到最上面，整個編輯卡片才不會被下面的按鈕擋住
    LaunchedEffect(open) {
        val idx = draft.lines.indexOfFirst { it.src == open }
        if (idx >= 0) state.animateScrollToItem(idx)
    }

    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(draft.lines, key = { _, l -> l.src }) { i, l ->
            val now = i == cur
            val expanded = open == l.src
            val shape = RoundedCornerShape(22.dp)
            Column(
                if (expanded) Modifier.fillMaxWidth().glow(color.copy(alpha = 0.3f), 18.dp, shape).clip(shape).background(Color(0xFF161320))
                    .border(1.5.dp, color, shape)
                else Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (now) color.copy(alpha = 0.14f) else Color.Transparent),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) stringResource(R.string.fix_collapse) else stringResource(R.string.fix_edit_line)) { open = if (expanded) null else l.src }
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (!l.sung) stringResource(R.string.fix_unsung) else if (expanded) formatPrecise(l.start) else formatShort(l.start),
                        Modifier.width(if (expanded) 64.dp else 44.dp), fontSize = 13.sp,
                        fontWeight = if (expanded) FontWeight.ExtraBold else FontWeight.Normal,
                        color = when { expanded -> color; l.unsure -> Palette.warn; else -> Palette.faint })
                    if (expanded && l.unsure) Text(stringResource(R.string.fix_ai_guess), Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.warn).padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 12.sp, color = Color(0xFF1A1306))
                    if (!expanded) Text(l.text, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = Palette.text.copy(alpha = if (!l.sung) 0.35f else if (now) 1f else 0.6f))
                }
                if (expanded) LineEditor(pc, i, l, color, onDelete = { deleting = i }, onClose = { open = null })
            }
        }
    }

    deleting?.let { i ->
        val l = draft.lines.getOrNull(i)
        if (l == null) { deleting = null; return@let }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.fix_delete_title)) },
            text = { Text(l.origText) },
            confirmButton = { TextButton(onClick = { deleting = null; open = null; pc.deleteLine(i) }) { Text(stringResource(R.string.fix_delete), color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** 一句的編輯卡片：文字直接改；時間用「聽到這句開始時按這裡」或加減；AI 猜的可以按「時間沒錯」；按「完成」套用文字並收起。 */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@OptIn(UnstableApi::class)
@Composable
private fun LineEditor(pc: PlayerController, i: Int, l: DraftLine, color: Color, onDelete: () -> Unit, onClose: () -> Unit) {
    var text by remember(l.src) { mutableStateOf(l.text) }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fix_line_label)) },
            textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            shape = RoundedCornerShape(14.dp), colors = fieldColors(color))
        NeonButton(stringResource(R.string.fix_set_now), onClick = { pc.setNow(i) }, modifier = Modifier.fillMaxWidth(), glowColor = color.copy(alpha = 0.4f), height = 56.dp)
        if (l.sung) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in listOf(-0.5, -0.1, 0.1, 0.5)) {
                GhostButton((if (d > 0) "+" else "−") + "%.1f".format(abs(d)), onClick = { pc.nudge(i, d) }, modifier = Modifier.weight(1f),
                    fill = Palette.glassHi, borderColor = Color.Transparent)
            }
        }
        // 改了什麼只在這裡說明（列表不加符號，免得誤會）
        val notes = listOfNotNull(
            if (l.pinned) stringResource(R.string.fix_note_time) else null,
            if (l.edited) stringResource(R.string.fix_note_text) else null,
            if (l.confirmed) stringResource(R.string.fix_note_confirmed) else null,
        )
        if (notes.isNotEmpty()) Text(notes.joinToString(stringResource(R.string.fix_note_sep)), color = Palette.dim, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (l.sung) GhostButton(stringResource(R.string.fix_preview), onClick = { pc.preview(i) })
            if (l.unsure) GhostButton(stringResource(R.string.fix_time_ok), onClick = { pc.confirmLine(i) })
            if (l.changed) GhostButton(stringResource(R.string.fix_revert), onClick = { text = l.origText; pc.revertLine(i) })
            GhostButton(stringResource(R.string.fix_delete_line), onClick = onDelete, color = Palette.danger, borderColor = Palette.danger.copy(alpha = 0.5f))
            GhostButton(stringResource(R.string.common_done), fill = Palette.glassHi, borderColor = Color.Transparent, onClick = {
                when {
                    text.isBlank() -> onDelete()
                    text.trim() != l.text -> { pc.editLine(i, text); onClose() }
                    else -> onClose()
                }
            })
        }
    }
}

/** 換整份歌詞（004、012；013 B 版外觀）：讓 AI 去找（LRCLIB）、上網找、貼上；整份取代目前的歌詞與修改。 */
@OptIn(UnstableApi::class)
@Composable
fun ReplaceLyricsDialog(pc: PlayerController, color: Color, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf(pc.detail?.song?.title.orEmpty()) }
    LaunchedEffect(Unit) { pc.lyricsSearchMsg = null }
    val lrc = looksLikeLrc(text)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 按鈕放在頂端：放底部會被鍵盤或系統列擋住（實機發現）
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pc.replaceError = null; onClose() }, Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.common_cancel), color = Palette.text, fontSize = 15.sp) }
                Text(stringResource(R.string.replace_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                NeonButton(if (pc.replaceBusy) stringResource(R.string.common_sending) else stringResource(R.string.common_send), { pc.replaceLyrics(text, onSent = onClose) },
                    enabled = text.isNotBlank() && !pc.replaceBusy, height = 44.dp, fontSize = 15, glowColor = color.copy(alpha = 0.4f))
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(color.copy(alpha = 0.18f), Color(0x296B3BFF))))
                    .border(1.dp, color, RoundedCornerShape(22.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.replace_ai), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = titleInput(it) }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.replace_query)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50), colors = fieldColors(color))
                    NeonButton(if (pc.lyricsSearchBusy) stringResource(R.string.replace_searching) else stringResource(R.string.replace_auto), { pc.searchLyrics(query) { text = it } },
                        color = color, enabled = query.isNotBlank() && !pc.lyricsSearchBusy, height = 48.dp, fontSize = 15)
                }
                Text(pc.lyricsSearchMsg ?: stringResource(R.string.replace_ai_hint), fontSize = 12.sp, color = Palette.dim)
            }
            LyricsSourceButtons(query, onPaste = { text = it }, showHint = false) {
                if (text.isNotBlank()) Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50))
                    .background(if (lrc) Palette.ok.copy(alpha = 0.12f) else Palette.glass).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(if (lrc) stringResource(R.string.lrc_badge) else stringResource(R.string.text_badge), fontSize = 13.sp, color = if (lrc) Color(0xFF7BF7DA) else Palette.dim)
                }
            }
            pc.replaceError?.let { Text(it, color = Palette.accent2, fontSize = 14.sp) }
            Text(stringResource(R.string.replace_note), color = Palette.dim, fontSize = 13.sp)
            // 限高：weight(1f) 在 Dialog 裡會撐到螢幕外，下面的行數提示被擠成 0（016 實機）
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 140.dp, max = 360.dp),
                placeholder = { Text(stringResource(R.string.replace_placeholder)) }, shape = RoundedCornerShape(22.dp), colors = fieldColors(color),
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 26.sp))
            LyricsSummary(text)
        }
    }
}

/** 輸入框：深色底、聚焦時歌曲色外框。 */
@Composable
fun fieldColors(color: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = color, unfocusedBorderColor = Palette.line, cursorColor = color,
    focusedContainerColor = Color(0x59000000), unfocusedContainerColor = Color(0x0DFFFFFF),
    focusedLabelColor = color, unfocusedLabelColor = Palette.dim, focusedPlaceholderColor = Palette.faint, unfocusedPlaceholderColor = Palette.faint,
)

/** m:ss.s（展開時；先取到 0.1 秒再拆分，59.98 秒顯示 1:00.0）。 */
private fun formatPrecise(sec: Double): String {
    val tenths = Math.round(maxOf(0.0, sec) * 10)
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

/** m:ss（列表用）。 */
private fun formatShort(sec: Double): String = formatDuration(maxOf(0.0, sec))
