// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.config.RuntimeConfigWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: PluginConfig,
    runtime: RuntimeConfigWriter.Status,
    onSave: (PluginConfig) -> Unit,
) {
    val context = LocalContext.current
    var backend by remember(config.backend) { mutableStateOf(config.backend) }
    var autoSelfTest by remember(config.autoSelfTest) { mutableStateOf(config.autoSelfTest) }
    val env = remember(config.env) {
        mutableStateMapOf<String, String>().apply { putAll(config.env) }
    }
    var dialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("渲染后端") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("DirectVulkan", "DirectGLES").forEach { candidate ->
                    FilterChip(
                        selected = backend == candidate,
                        onClick = { backend = candidate },
                        label = { Text(candidate) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "DirectVulkan 使用 Vulkan 驱动（Magma）；DirectGLES 走系统 GLES。修改后写入运行时配置，游戏下次启动生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("自检") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启动时运行驱动自检", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "仅影响本插件的界面进程，不会拖慢游戏启动。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoSelfTest, onCheckedChange = { autoSelfTest = it })
            }
        }

        SectionCard("环境变量") {
            Text(
                "与启动器 boatEnv/pojavEnv 使用同名变量；写入运行时配置文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            env.toSortedMap().forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(value, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { env.remove(key) }) { Text("删除") }
                }
            }
            OutlinedButton(onClick = { dialogVisible = true }) { Text("添加变量") }
        }

        SectionCard("运行时配置") {
            Text(
                if (runtime.sharedWritten) "已写入 " + runtime.sharedPath
                else "共享路径不可写：" + (runtime.reason ?: runtime.sharedPath),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "私有副本: " + runtime.privatePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (!hasAllFilesAccess()) {
                OutlinedButton(onClick = { openAllFilesSettings(context) }) {
                    Text("授予“所有文件访问权限”")
                }
            }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MobileGL config", config.toJson().toString(2)))
            }) { Text("复制配置 JSON") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(
                        config.copy(
                            backend = backend,
                            autoSelfTest = autoSelfTest,
                            env = env.toMap(),
                        )
                    )
                },
            ) { Text("保存配置") }
            OutlinedButton(
                onClick = {
                    backend = "DirectVulkan"
                    autoSelfTest = false
                    env.clear()
                    env.putAll(PluginConfig.defaultEnv("DirectVulkan"))
                },
            ) { Text("恢复默认") }
        }
    }

    if (dialogVisible) {
        AddVariableDialog(
            onDismiss = { dialogVisible = false },
            onAdd = { key, value ->
                env[key] = value
                dialogVisible = false
            },
        )
    }
}

@Composable
private fun AddVariableDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加环境变量") },
        text = {
            Column {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.uppercase() },
                    label = { Text("名称（如 MOBILEGL_DISABLE_TIMERQUERY）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("值") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.matches(Regex("[A-Z][A-Z0-9_]{1,63}")),
                onClick = { onAdd(key, value) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun openAllFilesSettings(context: Context) {
    val packageUri = Uri.parse("package:" + context.packageName)
    val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
    runCatching { context.startActivity(direct) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}
