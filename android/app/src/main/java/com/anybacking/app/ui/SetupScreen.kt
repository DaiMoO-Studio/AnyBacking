package com.anybacking.app.ui

import androidx.compose.ui.res.stringResource
import com.anybacking.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.anybacking.app.App
import com.anybacking.app.BuildConfig
import com.anybacking.app.data.DeviceKey
import com.anybacking.app.net.ApiError
import com.anybacking.app.net.Pins
import com.anybacking.app.net.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 設定：伺服器位址（018 自架）、裝置金鑰（第一次使用或金鑰失效時）、debug 工具。 */
@Composable
fun SetupScreen(modifier: Modifier, notice: String?, firstRun: Boolean, onDone: () -> Unit, onDiag: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var serverInput by remember { mutableStateOf(app.prefs.server) }
    val serverChanged = ServerConfig.normalize(serverInput) != app.prefs.server
    var msg by remember { mutableStateOf(notice) }
    var busy by remember { mutableStateOf(false) }
    var breakPins by remember { mutableStateOf(Pins.breakForTest) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        TopBar(stringResource(if (firstRun) R.string.setup_welcome else R.string.set_title), onBack = if (firstRun) null else onDone)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (app.key.isSet) R.string.setup_has_key else R.string.setup_need_key),
                color = Palette.dim,
            )
            msg?.let { Text(it, color = Palette.accent2) }
            OutlinedTextField(
                value = serverInput, onValueChange = { serverInput = it.trim() }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_server)) }, singleLine = true,
                placeholder = { Text(stringResource(R.string.setup_server_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = input, onValueChange = { input = it.trim() }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_key)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                enabled = !busy && (input.isNotEmpty() || (serverChanged && app.key.isSet)), modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val srv = ServerConfig.normalize(serverInput)
                    if (srv == null) { msg = app.getString(R.string.setup_server_bad); return@Button }
                    if (!ServerConfig.allowed(srv)) { msg = app.getString(R.string.setup_server_http); return@Button }
                    if (input.isNotEmpty() && !DeviceKey.looksValid(input)) { msg = app.getString(R.string.setup_bad_format); return@Button }
                    val k = input.ifEmpty { app.key.get() }
                    busy = true; msg = app.getString(R.string.setup_checking)
                    scope.launch {
                        val r = runCatching { withContext(Dispatchers.IO) { app.api.verify(k, srv) } }
                        busy = false
                        r.onSuccess { app.prefs.server = srv; serverInput = srv; app.key.set(k); input = ""; onDone() }
                            .onFailure { msg = if (it is ApiError.Unauthorized) app.getString(R.string.setup_unknown_key) else it.userText(app) }
                    }
                },
            ) { Text(stringResource(R.string.setup_save)) }

            if (app.key.isSet && !firstRun) {
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_keep)) }
            }

            if (BuildConfig.DEBUG) {
                Text("開發者工具（debug 版）", color = Palette.dim, modifier = Modifier.padding(top = 24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("測試錯誤釘選（不信任任何憑證）", Modifier.weight(1f))
                    Switch(checked = breakPins, onCheckedChange = {
                        breakPins = it; Pins.breakForTest = it
                        // 已建立的連線不會重新驗證憑證：關掉重連（關 TLS 連線會寫網路，不能在主執行緒）
                        scope.launch(Dispatchers.IO) { app.api.http.connectionPool.evictAll() }
                    })
                }
                OutlinedButton(onClick = onDiag, modifier = Modifier.fillMaxWidth()) { Text("播放器診斷") }
            }
        }
    }
}
