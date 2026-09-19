package cn.zgccrui.backscreen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/*
 * THESIS: A small native Android utility for the owner's back display.
 * OWN-WORLD: Google Material 3, dynamic color and system typography.
 * STORY: Connect Shizuku, inspect the real display state, then choose one action.
 * FIRST VIEWPORT: App bar, connection state, display state and two full-width buttons.
 * FORM: One scrolling screen. Native dialogs only for settings and error details.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                val model: DisplayViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        model.reconnect()
                        while (isActive) {
                            delay(3_000)
                            model.refresh()
                        }
                    }
                }
                BackScreen(state, model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackScreen(state: ScreenState, model: DisplayViewModel) {
    val context = LocalContext.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val operating = state.operation == Operation.ENABLE || state.operation == Operation.DISABLE
    val ready = state.connection == Connection.READY && state.snapshot != null && !operating

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("背屏开关") },
                actions = {
                    IconButton(onClick = model::reconnect, enabled = !operating) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新状态")
                    }
                    IconButton(onClick = { settingsOpen = true }, enabled = !operating) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置")
                    }
                },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                ConnectionPanel(state, onAuthorize = model::requestPermission, onOpen = {
                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    runCatching { context.startActivity(intent) }
                })
                Spacer(Modifier.height(24.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("背屏 · Display ${state.displayId}", style = MaterialTheme.typography.titleMedium)
                        Text(statusTitle(state), style = MaterialTheme.typography.headlineSmall)
                        Text(statusDescription(state), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = { model.setEnabled(true) }, enabled = ready,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                ) { Text(if (state.operation == Operation.ENABLE) "正在开启…" else "开启并唤醒") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { model.setEnabled(false) }, enabled = ready && state.snapshot?.enabled == true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                ) { Text(if (state.operation == Operation.DISABLE) "正在关闭…" else "关闭背屏") }
                if (operating) {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在操作并检查背屏状态", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.message.isNotBlank()) {
                    Text(state.message, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.bodyMedium,
                        color = if (state.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (state.detail.isNotBlank()) {
                        TextButton(onClick = { detailsOpen = true }) { Text("查看错误详情") }
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("手机重启后，需要先重新启动 Shizuku。\n背屏唤醒后会按手机设置自动熄屏。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (settingsOpen) DisplaySettings(state.displayId, onDismiss = { settingsOpen = false }) { id ->
        model.setDisplayId(id)
        settingsOpen = false
    }
    if (detailsOpen) AlertDialog(
        onDismissRequest = { detailsOpen = false }, title = { Text("错误详情") },
        text = { Text(state.detail, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("关闭") } },
    )
}

@Composable
private fun ConnectionPanel(state: ScreenState, onAuthorize: () -> Unit, onOpen: () -> Unit) {
    val title = when (state.connection) {
        Connection.NOT_RUNNING -> "先启动 Shizuku"
        Connection.NEED_PERMISSION -> "需要 Shizuku 授权"
        Connection.CONNECTING -> "正在连接 Shizuku…"
        Connection.READY -> "Shizuku 已连接"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        when (state.connection) {
            Connection.NOT_RUNNING -> {
                Text("打开 Shizuku，通过无线调试启动后返回这里。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpen) { Text("打开 Shizuku") }
            }
            Connection.NEED_PERMISSION -> {
                Text("允许本应用管理背屏，即可使用下面的开关。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = onAuthorize) { Text("授予权限") }
                    TextButton(onClick = onOpen) { Text("打开 Shizuku") }
                }
            }
            Connection.CONNECTING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Connection.READY -> Text("已获得背屏控制权限", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun statusTitle(state: ScreenState): String = when {
    state.connection != Connection.READY -> "等待连接"
    state.snapshot == null -> "等待读取状态"
    !state.snapshot.enabled -> "已关闭"
    state.snapshot.power == "ON" -> "已开启 · 亮屏"
    state.snapshot.power in listOf("DOZE", "DOZE_SUSPEND") -> "已开启 · 息屏显示"
    state.snapshot.power == "OFF" -> "已开启 · 休眠"
    else -> "已开启"
}

private fun statusDescription(state: ScreenState): String = when {
    state.snapshot == null -> "连接并授权后，这里会显示背屏的实际状态。"
    !state.snapshot.enabled -> "背屏已从可用显示器列表中移除。"
    state.snapshot.power == "OFF" -> "背屏可用，当前处于休眠；点击下方按钮可唤醒。"
    state.snapshot.power == "ON" -> "背屏当前已唤醒。"
    else -> "背屏可用，显示内容与熄屏时间由手机管理。"
}

@Composable
private fun DisplaySettings(currentId: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(currentId.toString()) }
    val id = input.toIntOrNull()
    val valid = id != null && id > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("副屏设置") },
        text = {
            OutlinedTextField(
                value = input, onValueChange = { if (it.length <= 10 && it.all(Char::isDigit)) input = it },
                label = { Text("副屏编号") }, singleLine = true, isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("本机背屏为 1。主屏 0 不可选择。") },
            )
        },
        confirmButton = { TextButton(onClick = { id?.let(onSave) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
