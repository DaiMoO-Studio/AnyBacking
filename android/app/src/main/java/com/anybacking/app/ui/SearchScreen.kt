package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.anybacking.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anybacking.app.App
import com.anybacking.app.data.highlightRange
import com.anybacking.app.data.matchesQuery

/**
 * 搜尋歌曲（013）：邊打邊篩選（規則同 010：只比對歌名、不分大小寫、忽略空白），符合的字用強調色；
 * 沒輸入時列出最近搜尋（按鍵盤搜尋鍵或點了結果才記錄）。歌曲清單和歌曲庫共用同一個 ViewModel。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(modifier: Modifier, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val vm: LibraryViewModel = viewModel()
    LifecycleStartEffect(Unit) { vm.start(); onStopOrDispose { vm.stop() } }
    var q by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf(app.prefs.recentSearches) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldDesc = stringResource(R.string.search_field)
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun record(text: String) { app.prefs.addRecentSearch(text); recent = app.prefs.recentSearches }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.weight(1f).heightIn(min = 52.dp).glow(Palette.accent.copy(alpha = 0.35f), 18.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50)).background(Color(0xFF16131E)).border(1.5.dp, Palette.accent, RoundedCornerShape(50))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AppIcons.Search, null, tint = Palette.text, modifier = Modifier.size(20.dp))
                BasicTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 17.sp, fontFamily = AppFont),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); record(q) }),
                    decorationBox = { inner -> if (q.isEmpty()) Text(stringResource(R.string.search_placeholder), color = Palette.faint, fontSize = 17.sp); inner() },
                    modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = fieldDesc },
                )
                if (q.isNotEmpty()) RoundIconButton(AppIcons.Close, stringResource(R.string.common_clear), { q = "" }, size = 44.dp, iconSize = 14.dp, fill = Color.Transparent, border = null)
            }
            TextButton(onClick = { keyboard?.hide(); onBack() }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel), color = Palette.text, fontSize = 15.sp) }
        }

        val songs = vm.songs.orEmpty()
        if (q.isBlank()) {
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_recent), Modifier.weight(1f), fontSize = 13.sp, color = Palette.dim)
                    TextButton(onClick = { app.prefs.clearRecentSearches(); recent = emptyList() }) { Text(stringResource(R.string.common_clear), color = Palette.dim, fontSize = 13.sp) }
                }
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { r -> GhostButton(r, onClick = { q = r }) }
                }
            } else Text(stringResource(R.string.search_hint), Modifier.padding(24.dp), color = Palette.dim, fontSize = 14.sp)
            return@Column
        }
        val list = songs.filter { matchesQuery(it.title, q) }
        Text(if (list.isEmpty()) stringResource(R.string.search_none, q.trim()) else pluralStringResource(R.plurals.songs_found, list.size, list.size), Modifier.padding(start = 24.dp, top = 22.dp, bottom = 10.dp),
            fontSize = 13.sp, color = Palette.dim)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { s ->
                val r = highlightRange(s.title, q)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x0AFFFFFF))
                        .clickable(enabled = s.isPlayable) { keyboard?.hide(); record(q); onPlay(s.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SongCover(s.id, s.title, dim = !s.isPlayable)
                    Column(Modifier.weight(1f)) {
                        Text(buildAnnotatedString {
                            if (r == null) append(s.title) else {
                                append(s.title.substring(0, r.first))
                                withStyle(SpanStyle(color = Palette.accent)) { append(s.title.substring(r.first, r.last + 1)) }
                                append(s.title.substring(r.last + 1))
                            }
                        }, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (s.isPlayable) Palette.text else Palette.dim)
                        Text(if (s.isPlayable) formatDuration(s.duration) else stringResource(if (s.status == "failed") R.string.search_not_ready_failed else R.string.search_not_ready_processing),
                            fontSize = 13.sp, color = Palette.dim)
                    }
                }
            }
        }
    }
}
