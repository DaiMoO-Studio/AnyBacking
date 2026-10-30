package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.anybacking.app.R
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anybacking.app.App
import com.anybacking.app.data.coverInitial
import com.anybacking.app.data.looksLikeLrc
import com.anybacking.app.data.looksLikeUrl
import com.anybacking.app.data.titleInput
import com.anybacking.app.net.Api
import com.anybacking.app.work.DoneWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    /** 來源：手機裡的檔案，或貼上的網址（009）；兩者只會有一個。 */
    var file by mutableStateOf<Picked?>(null); private set
    var url by mutableStateOf(""); private set
    var title by mutableStateOf("")
    /** 歌詞（013）：false＝交給 AI（先查歌詞庫、找不到再辨識）；true＝我有歌詞（LRC 或純文字，伺服器判斷）。 */
    var mine by mutableStateOf(false)
    var lyrics by mutableStateOf("")
    var progress by mutableFloatStateOf(-1f); private set   // -1＝還沒開始
    var error by mutableStateOf<String?>(null); private set
    var sourceMsg by mutableStateOf<String?>(null); private set
    var doneId by mutableStateOf<String?>(null); private set

    data class Picked(val uri: Uri, val name: String, val size: Long)

    val hasSource get() = file != null || url.isNotEmpty()
    val busy get() = progress >= 0

    fun reset() {
        file = null; url = ""; title = ""; lyrics = ""; mine = false; progress = -1f; error = null; sourceMsg = null; doneId = null
        preview = null; previewError = null
    }

    fun clearSource() { file = null; url = ""; error = null; sourceMsg = null; preview = null; previewError = null }

    fun pick(uri: Uri) {
        val r = app.contentResolver
        var name = "song"; var size = -1L
        r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        url = ""; sourceMsg = null
        file = Picked(uri, name, size)
        if (title.isBlank()) title = titleInput(name.substringBeforeLast('.'))
        error = if (size > Api.MAX_UPLOAD) app.getString(R.string.add_file_too_large) else null
    }

    /** 貼上的網址是哪支影片（013）：null＝還在讀或讀不到（看 [previewError]）。 */
    var preview by mutableStateOf<Api.UrlPreview?>(null); private set
    var previewError by mutableStateOf<String?>(null); private set

    /** 「貼上網址」（007 規則：按了才讀剪貼簿）：是網址就當來源並讀影片資訊，不是就提示。 */
    fun pasteUrl(clip: String) {
        if (looksLikeUrl(clip)) {
            file = null; url = clip; sourceMsg = null; error = null; preview = null; previewError = null
            viewModelScope.launch {
                try {
                    val p = withContext(Dispatchers.IO) { app.api.urlPreview(clip) }
                    if (url == clip) { preview = p; if (p.tooLong) previewError = app.getString(R.string.add_video_too_long) }
                } catch (e: Exception) {
                    if (url == clip) previewError = e.userText(app)
                }
            }
        }
        else sourceMsg = app.getString(if (clip.isBlank()) R.string.add_clip_empty else R.string.add_clip_no_url)
    }

    fun pasteLyrics(text: String) { lyrics = text; mine = true }

    fun readLrc(uri: Uri) {
        runCatching { app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            .onSuccess(::pasteLyrics).onFailure { error = app.getString(R.string.add_lrc_unreadable) }
    }

    val canSend get() = !busy && hasSource && (!mine || lyrics.isNotBlank()) &&
        (if (url.isNotEmpty()) preview?.tooLong == false else file!!.size in 1..Api.MAX_UPLOAD)

    fun send() {
        if (!canSend) return
        val f = file
        error = null; progress = 0f
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = lyrics.takeIf { mine }
                    if (f == null) app.api.uploadUrl(url, title, text).also { progress = 1f }
                    else app.api.upload(app.contentResolver, f.uri, f.size, f.name, title, text) { sent, total ->
                        progress = sent.toFloat() / total
                    }
                }
            }.onSuccess { id ->
                app.prefs.addPending(id)
                DoneWatcher.schedule(app)
                doneId = id
            }.onFailure { error = app.getString(R.string.add_upload_failed, it.userText(app)); progress = -1f }
        }
    }
}

/**
 * 加一首歌（013 B 版）：底部面板。來源二選一（手機裡的歌／貼上網址），歌詞二選一（交給 AI／我有歌詞）。
 * 只有按「貼上網址」或歌詞的「貼上」才讀剪貼簿（007）。上傳中不能滑掉面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(onClose: () -> Unit, onAdded: () -> Unit) {
    val ctx = LocalContext.current
    val vm: UploadViewModel = viewModel()
    val pickSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pick) }
    val pickLrc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::readLrc) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !vm.busy })

    // 上傳成功：關面板、回歌曲庫最上面（ViewModel 跟著 Activity，下次打開要是空白）
    LaunchedEffect(vm.doneId) { if (vm.doneId != null) { vm.reset(); onAdded() } }
    fun close() { if (!vm.busy) { vm.reset(); onClose() } }

    ModalBottomSheet(onDismissRequest = ::close, sheetState = state, containerColor = Palette.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.add_title), fontSize = 26.sp, fontWeight = FontWeight.Black)

            if (vm.hasSource) SourceCard(vm, onChange = { if (!vm.busy) vm.clearSource() })
            else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigChoice(AppIcons.Phone, stringResource(R.string.add_phone), stringResource(R.string.add_phone_sub), Modifier.weight(1f)) { pickSong.launch(arrayOf("audio/*", "video/*")) }
                BigChoice(AppIcons.Link, stringResource(R.string.add_url), stringResource(R.string.add_url_sub), Modifier.weight(1f)) { vm.pasteUrl(readClipboard(ctx)) }
            }
            vm.sourceMsg?.let { Text(it, color = Palette.accent2, fontSize = 13.sp) }

            if (vm.hasSource) {
                OutlinedTextField(value = vm.title, onValueChange = { vm.title = titleInput(it) },   // singleLine 擋不住貼上的換行
                    label = { Text(stringResource(R.string.lib_title_label)) }, placeholder = { if (vm.url.isNotEmpty()) Text(stringResource(R.string.add_title_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy,
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(Palette.accent))

                Text(stringResource(R.string.add_lyrics), fontSize = 13.sp, color = Palette.dim)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Palette.glass).padding(4.dp)) {
                    Segment(stringResource(R.string.add_lyrics_ai), !vm.mine, Modifier.weight(1f)) { vm.mine = false }
                    Segment(stringResource(R.string.add_lyrics_mine), vm.mine, Modifier.weight(1f)) { vm.mine = true }
                }
                if (!vm.mine) Text(stringResource(R.string.add_lyrics_ai_hint), fontSize = 12.sp, color = Palette.dim)
                else {
                    LyricsSourceButtons(vm.title, onPaste = vm::pasteLyrics, showHint = vm.lyrics.isBlank()) {
                        if (vm.lyrics.isNotBlank()) {
                            val lrc = looksLikeLrc(vm.lyrics)
                            Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(if (lrc) Palette.ok.copy(alpha = 0.12f) else Palette.glass)
                                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                Text(if (lrc) stringResource(R.string.lrc_badge) else stringResource(R.string.text_badge), fontSize = 13.sp, color = if (lrc) Color(0xFF7BF7DA) else Palette.dim)
                            }
                        }
                    }
                    OutlinedTextField(value = vm.lyrics, onValueChange = { vm.lyrics = it }, placeholder = { Text(stringResource(R.string.add_lyrics_placeholder)) },
                        // 限高、框內捲動（016）：不限高時長歌詞把面板撐出螢幕，在輸入框上滑動又捲不動外面的面板
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 300.dp), enabled = !vm.busy,
                        shape = RoundedCornerShape(18.dp), colors = fieldColors(Palette.accent), textStyle = TextStyle(fontSize = 15.sp))
                    if (vm.lyrics.isBlank()) Text(stringResource(R.string.add_lyrics_need), fontSize = 12.sp, color = Palette.dim)
                    else LyricsSummary(vm.lyrics)
                    GhostButton(stringResource(R.string.add_pick_lrc), onClick = { pickLrc.launch(arrayOf("*/*")) }, enabled = !vm.busy)
                }

                vm.error?.let { Text(it, color = Palette.accent2, fontSize = 14.sp) }
                if (vm.busy) {
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth(), color = Palette.accent, trackColor = Palette.glassHi)
                    Text(if (vm.progress < 1f) stringResource(R.string.add_uploading_pct, (vm.progress * 100).toInt()) else stringResource(R.string.add_uploaded), color = Palette.dim, fontSize = 13.sp)
                }
                NeonButton(if (vm.busy) stringResource(R.string.add_uploading) else stringResource(R.string.add_send), onClick = {
                    // 第一次上傳時要通知權限：處理完成時通知你（App 在背景也會）
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.send()
                }, modifier = Modifier.fillMaxWidth(), color = Palette.accent, glowColor = Palette.accent.copy(alpha = 0.5f), enabled = vm.canSend, height = 56.dp, fontSize = 17)
            }
        }
    }
}

/** 選好的來源：剛貼上的網址或手機檔案＋「換一個」。 */
@Composable
private fun SourceCard(vm: UploadViewModel, onChange: () -> Unit) {
    val isUrl = vm.url.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Palette.accent.copy(alpha = 0.18f), Color(0x296B3BFF))))
            .border(1.dp, Palette.accent, RoundedCornerShape(22.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (isUrl) stringResource(R.string.add_url_card) else stringResource(R.string.add_phone), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Palette.accent)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Palette.accent, Color(0xFF6B3BFF)))),
                contentAlignment = Alignment.Center) {
                if (isUrl) Icon(AppIcons.Link, null, tint = Color.White, modifier = Modifier.size(24.dp))
                else Text(coverInitial(vm.title.ifBlank { vm.file!!.name }), fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f)) {
                val p = vm.preview
                Text(if (!isUrl) vm.file!!.name else p?.title?.ifBlank { null } ?: vm.url, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !isUrl -> stringResource(R.string.size_mb, (vm.file!!.size / 1_048_576).toInt())
                        vm.previewError != null -> vm.previewError!!
                        p == null -> stringResource(R.string.add_reading_video)
                        else -> listOf(p.channel, formatDuration(p.duration)).filter { it.isNotBlank() }.joinToString(stringResource(R.string.common_dot_sep))
                    },
                    fontSize = 12.sp, color = if (isUrl && vm.previewError != null) Palette.accent2 else Palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            GhostButton(stringResource(R.string.add_change), onClick = onChange, enabled = !vm.busy)
        }
    }
}

@Composable
private fun BigChoice(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Palette.glass).border(1.dp, Palette.line, RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Palette.accent, modifier = Modifier.size(26.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(sub, fontSize = 12.sp, color = Palette.dim)
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(if (selected) Color.White else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (selected) Palette.bg else Palette.text) }
}
