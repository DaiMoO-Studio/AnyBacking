package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.anybacking.app.R
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anybacking.app.App
import com.anybacking.app.data.PlayRecord
import com.anybacking.app.data.Song
import com.anybacking.app.data.cleanTitle
import com.anybacking.app.data.coverInitial
import com.anybacking.app.data.isReplay
import com.anybacking.app.data.lastPlayed
import com.anybacking.app.data.pickContinue
import com.anybacking.app.data.splitArtist
import com.anybacking.app.data.titleInput
import com.anybacking.app.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    var songs by mutableStateOf<List<Song>?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    /** 本機播放紀錄（013）：回到歌曲庫時重讀。 */
    var records by mutableStateOf<Map<String, PlayRecord>>(emptyMap()); private set
    /** 「全部」或「最近唱過」；跟著 Activity（這次執行期間保留）。 */
    var recentOnly by mutableStateOf(false)
    private var poll: Job? = null

    /** 畫面可見時輪詢：有排隊／處理中的歌每 3 秒一次，否則只拿一次（contracts/client-behavior.md）。 */
    fun start() {
        records = app.prefs.allRecords()
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                val wait = try {
                    val list = withContext(Dispatchers.IO) { app.api.listSongs() }
                    songs = list; error = null
                    // 已經在畫面上看到完成了，就不用再發通知
                    list.filter { !it.isPending }.forEach { app.prefs.removePending(it.id) }
                    if (list.any { it.isPending }) 3_000L else return@launch
                } catch (e: ApiError.RateLimited) {
                    error = e.userText(app); e.retryAfterS * 1000L
                } catch (e: ApiError.Unauthorized) {
                    return@launch
                } catch (e: ApiError) {
                    error = e.userText(app); 10_000L
                }
                delay(wait)
            }
        }
    }

    fun stop() { poll?.cancel() }

    fun delete(s: Song) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { app.api.deleteSong(s.id) } }
            .onFailure { if (it !is ApiError.NotFound) { error = it.userText(app); return@launch } }
        app.prefs.forget(s.id)
        songs = songs?.filter { it.id != s.id }
        records = records - s.id
    }

    /** 改歌名（006）：成功就換掉列表上的名字並呼叫 onDone；失敗回傳原因（對話框顯示、保留輸入）。 */
    var renameBusy by mutableStateOf(false); private set
    var renameError by mutableStateOf<String?>(null); private set

    fun rename(s: Song, title: String, onDone: () -> Unit) {
        if (renameBusy) return
        renameBusy = true; renameError = null
        viewModelScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { app.api.renameSong(s.id, title) }
                songs = songs?.map { if (it.id == s.id) it.copy(title = t) else it }
                onDone()
            } catch (e: ApiError.NotFound) {
                // 別台裝置剛刪掉：照刪除處理
                app.prefs.forget(s.id); songs = songs?.filter { it.id != s.id }; onDone()
            } catch (e: ApiError) {
                renameError = e.userText(app)
            } finally {
                renameBusy = false
            }
        }
    }

    fun clearRenameError() { renameError = null }

    /** 加歌面板送出成功（013）：重新輪詢，新歌才會出現在列表最上面。 */
    fun added() = start()
}

/** 歌曲庫（013 B 版）：繼續唱卡片、全部／最近唱過、彩色封面、⋯ 選單（改名、刪除）、右下「＋」加歌面板。 */
@Composable
fun LibraryScreen(modifier: Modifier, onPlay: (String, Double?) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val vm: LibraryViewModel = viewModel()
    // 只在 App 前景時輪詢：到背景就交給 DoneWatcher（否則這裡先把「完成」消化掉，背景就不會發通知）
    LifecycleStartEffect(Unit) { vm.start(); onStopOrDispose { vm.stop() } }
    var confirm by remember { mutableStateOf<Song?>(null) }
    var renaming by remember { mutableStateOf<Song?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addedTick by remember { mutableIntStateOf(0) }
    val now = remember(vm.records) { System.currentTimeMillis() }

    val addDesc = stringResource(R.string.lib_add)
    Box(modifier) {
        // 右上角的光暈
        Box(Modifier.offset(x = 200.dp, y = (-170).dp).size(420.dp).background(Brush.radialGradient(listOf(Palette.accent.copy(alpha = 0.40f), Color.Transparent))))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 20.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("ANYBACKING", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = Palette.accent)
                    Text(stringResource(R.string.lib_title), fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 42.sp)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIconButton(AppIcons.Search, stringResource(R.string.lib_search), onSearch)
                    RoundIconButton(AppIcons.Settings, stringResource(R.string.lib_settings), onSettings)
                }
            }
            vm.error?.let { NoticeBar(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = Palette.accent2) }

            val all = vm.songs
            val records = vm.records
            val cont = all?.let { pickContinue(it, records) }
            val list = when {
                all == null -> null
                vm.recentOnly -> all.filter { records.containsKey(it.id) }.sortedByDescending { records.getValue(it.id).at }
                else -> all
            }
            // 剛加的歌排在第一列：第一首換了就捲回最上面（012）。LazyColumn 會跟著原本第一列的 key，新歌插在上面會看不到，像沒反應
            val listState = rememberLazyListState()
            val firstId = list?.firstOrNull()?.id
            LaunchedEffect(firstId, addedTick) { if (firstId != null) listState.scrollToItem(0) }

            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (cont != null) item(key = "continue") {
                    ContinueCard(cont, records.getValue(cont.id), vm, onPlay)
                }
                if (all != null && all.isNotEmpty()) item(key = "filters") {
                    Row(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(pluralStringResource(R.plurals.all_count, all.size, all.size), !vm.recentOnly) { vm.recentOnly = false }
                        FilterPill(stringResource(R.string.lib_recent), vm.recentOnly) { vm.recentOnly = true }
                    }
                }
                when {
                    list == null -> item { Text(stringResource(R.string.common_loading), Modifier.padding(24.dp), color = Palette.dim) }
                    all!!.isEmpty() -> item { Text(stringResource(R.string.lib_empty), Modifier.padding(24.dp), color = Palette.dim) }
                    list.isEmpty() -> item { Text(stringResource(R.string.lib_recent_empty), Modifier.padding(24.dp), color = Palette.dim) }
                    else -> items(list, key = { it.id }) { s ->
                        SongRow(s, records[s.id]?.let { lastPlayedText(lastPlayed(it.at, now)) },
                            onPlay = { onPlay(s.id, null) }, onRename = { vm.clearRenameError(); renaming = s }, onDelete = { confirm = s })
                    }
                }
            }
        }
        // 底部漸層＋「＋」
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Palette.bg))))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 28.dp).size(68.dp).glow(Palette.accent.copy(alpha = 0.6f), 24.dp)
                .clip(CircleShape).background(Palette.bg).background(Brush.radialGradient(listOf(Color.Transparent, Palette.accent.copy(alpha = 0.25f))))
                .border(2.dp, Palette.accent, CircleShape)
                .clickable(role = Role.Button) { adding = true }.semantics { contentDescription = addDesc },
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Plus, null, tint = Palette.accent, modifier = Modifier.size(28.dp)) }
    }

    if (adding) AddSheet(onClose = { adding = false }, onAdded = { adding = false; vm.added(); addedTick++ })

    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.lib_delete_title, s.title)) },
            text = { Text(stringResource(R.string.lib_delete_body)) },
            confirmButton = { TextButton(onClick = { vm.delete(s); confirm = null }) { Text(stringResource(R.string.common_delete), color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    renaming?.let { s -> RenameDialog(vm, s, onClose = { renaming = null }) }
}

/** 繼續唱大卡片（FR-011）：上次唱的歌、停的位置、Key／速度；播完或剩不到 10 秒改成「再唱一次」從頭播。 */
@Composable
private fun ContinueCard(s: Song, r: PlayRecord, vm: LibraryViewModel, onPlay: (String, Double?) -> Unit) {
    val app = vm.getApplication<App>()
    val p = remember(s.id, r) { app.prefs.play(s.id) }
    val c = songColor(s.id)
    val replay = isReplay(r.pos, s.duration)
    val (artist, name) = splitArtist(s.title)
    val shape = RoundedCornerShape(26.dp)
    val replayLabel = stringResource(if (replay) R.string.lib_replay else R.string.lib_continue)
    val meta = listOfNotNull(
        artist.ifEmpty { null },
        if (p.semitones != 0) "Key ${if (p.semitones > 0) "+" else ""}${p.semitones}" else null,
        if (p.speed != 1f) "%.2f×".format(p.speed).replace(Regex("0×$"), "×") else null,
    ).joinToString(stringResource(R.string.common_dot_sep))
    Box(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp).fillMaxWidth().height(168.dp)
            .glow(c.main.copy(alpha = 0.45f), 40.dp, shape).clip(shape)
            .background(Brush.linearGradient(listOf(c.main, c.deep)))
            .clickable(enabled = s.isPlayable, role = Role.Button, onClickLabel = replayLabel) {
                onPlay(s.id, if (replay) null else r.pos)
            },
    ) {
        Text(coverInitial(s.title), Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 30.dp), fontSize = 150.sp, lineHeight = 150.sp,
            fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.14f), maxLines = 1)
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(if (replay) replayLabel else stringResource(R.string.lib_continue_at, formatDuration(r.pos)),
                Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.bg.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Play, null, tint = c.deep, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** 改歌名對話框（006）：預填目前名字；貼上多行只留一行；空白不能儲存；失敗保留輸入。 */
@Composable
private fun RenameDialog(vm: LibraryViewModel, s: Song, onClose: () -> Unit) {
    var text by remember(s.id) { mutableStateOf(titleInput(s.title)) }
    val clean = cleanTitle(text)
    AlertDialog(
        onDismissRequest = { if (!vm.renameBusy) onClose() },
        containerColor = Palette.surface,
        title = { Text(stringResource(R.string.lib_rename_title), fontWeight = FontWeight.Black) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = titleInput(it) }, singleLine = true,
                    label = { Text(stringResource(R.string.lib_title_label)) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(Palette.accent), shape = RoundedCornerShape(14.dp))
                vm.renameError?.let { Text(it, Modifier.padding(top = 8.dp), color = Palette.accent2) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.rename(s, clean, onDone = onClose) }, enabled = clean.isNotEmpty() && !vm.renameBusy) {
                Text(if (vm.renameBusy) stringResource(R.string.common_saving) else stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !vm.renameBusy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SongRow(s: Song, lastPlayed: String?, onPlay: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val status = when {
        s.status == "queued" -> s.queuePosition?.let { stringResource(R.string.lib_queued_pos, it) } ?: stringResource(R.string.lib_queued)
        s.status == "processing" -> stringResource(R.string.lib_processing)
        s.status == "failed" -> stringResource(R.string.lib_failed, songErrorText(s) ?: stringResource(R.string.lib_failed_unknown))
        !s.playbackReady -> stringResource(R.string.lib_preparing)
        s.error != null -> songErrorText(s).orEmpty()
        else -> listOfNotNull(formatDuration(s.duration).ifEmpty { null }, lastPlayed).joinToString(stringResource(R.string.common_dot_sep))
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x17FFFFFF)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(18.dp))
            .clickable(enabled = s.isPlayable, onClick = onPlay).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SongCover(s.id, s.title, dim = !s.isPlayable)
        Column(Modifier.weight(1f)) {
            Text(s.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (s.isPlayable) Palette.text else Palette.dim)
            Text(status, fontSize = 13.sp, color = if (s.status == "failed") Palette.accent2 else Palette.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // 對這首歌品質的說明（005），不是使用者處理過的標記
            if (s.residueHigh) Text(stringResource(R.string.lib_residue), fontSize = 13.sp, color = Palette.warn)
        }
        Box {
            RoundIconButton(AppIcons.More, stringResource(R.string.lib_more, s.title), { menu = true }, size = 44.dp, iconSize = 20.dp, fill = Color.Transparent, border = null, tint = Palette.dim)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.surface) {
                DropdownMenuItem(text = { Text(stringResource(R.string.lib_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(if (s.status == "failed") stringResource(R.string.lib_delete_retry) else stringResource(R.string.common_delete), color = Palette.danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
