// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.DriverRepository

@Composable
fun DriverScreen(
    repository: DriverRepository,
    drivers: List<DriverPack>,
    config: PluginConfig,
    onRefresh: () -> Unit,
    onActivate: (DriverPack) -> Unit,
    onClearActive: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DriverPack?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val outcome = repository.install(uri)
                importing = false
                message = outcome.fold(
                    onSuccess = { "已导入 " + it.name + "（" + it.version + "）" },
                    onFailure = { "导入失败：" + (it.message ?: it.toString()) },
                )
                onRefresh()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("驱动包") {
            Text(
                "驱动包是带 driver.json 的 zip（arm64 .so + 可选 ICD 模板）。" +
                    "当前阶段用于安装、校验与 ICD 生成；MobileGL 仍使用系统 Vulkan loader，" +
                    "激活驱动不会改变游戏内渲染，直到 PanVK 阶段接入自带 loader。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !importing,
                    onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                ) { Text(if (importing) "导入中…" else "导入驱动包") }
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
            if (importing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (drivers.isEmpty()) {
            SectionCard("尚未安装驱动") {
                Text(
                    "driver.json 示例：",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "{\n  \"id\": \"panvk-g925-abcd1234\",\n  \"name\": \"Mesa PanVK (Mali-G925)\",\n" +
                        "  \"version\": \"26.3.0-devel\",\n  \"abi\": \"arm64-v8a\",\n" +
                        "  \"kind\": \"vulkan-icd\",\n  \"library\": \"lib/libvulkan_panfrost.so\",\n" +
                        "  \"icd\": \"panvk_icd.json\"\n}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        drivers.forEach { pack ->
            DriverCard(
                pack = pack,
                active = pack.id == config.activeDriverId,
                repository = repository,
                onActivate = { onActivate(pack) },
                onClearActive = onClearActive,
                onDelete = { pendingDelete = pack },
                onMessage = { message = it },
                onRefresh = onRefresh,
            )
        }
    }

    pendingDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除驱动") },
            text = { Text("确认删除 " + pack.name + "？该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = repository.delete(pack)
                    message = if (deleted) "已删除 " + pack.name else "删除失败"
                    pendingDelete = null
                    onRefresh()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DriverCard(
    pack: DriverPack,
    active: Boolean,
    repository: DriverRepository,
    onActivate: () -> Unit,
    onClearActive: () -> Unit,
    onDelete: () -> Unit,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    SectionCard(pack.name) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pack.version,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (active) {
                StatusPill("已激活", verdictColor("PASS"))
            } else {
                StatusPill("未激活", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        InfoRow("ABI", pack.abi)
        InfoRow("类型", pack.kind)
        if (pack.mesaVersion.isNotBlank()) InfoRow("Mesa", pack.mesaVersion)
        InfoRow("路径", pack.dir.absolutePath)
        if (pack.icdTemplate != null) InfoRow("ICD 模板", pack.icdTemplate)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active) {
                OutlinedButton(onClick = onClearActive) { Text("取消激活") }
            } else {
                Button(onClick = onActivate) { Text("激活") }
            }
            if (pack.isVulkanIcd()) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching { repository.generateIcd(pack) }.getOrNull()
                        }
                        onMessage(
                            if (file != null) "ICD 已生成：" + file.absolutePath
                            else "生成 ICD 失败（缺少模板或库）"
                        )
                    }
                }) { Text("生成 ICD") }
            }
            OutlinedButton(onClick = onDelete) { Text("删除") }
        }
        if (pack.isVulkanIcd()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "PanVK 阶段：MobileGL 需链接自带 Khronos loader 后，VK_DRIVER_FILES 才会生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
