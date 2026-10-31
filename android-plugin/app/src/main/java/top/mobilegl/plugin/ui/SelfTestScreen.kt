// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.selftest.BackendReport
import top.mobilegl.plugin.selftest.CheckItem
import top.mobilegl.plugin.selftest.SelfTestRepository
import top.mobilegl.plugin.selftest.SelfTestResult

@Composable
fun SelfTestScreen(
    repository: SelfTestRepository,
    config: PluginConfig,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(repository.cached()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "调用 libMobileGL.so 内置的 Driver POST，探测 GLES 与 Vulkan 驱动能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!running) {
                            scope.launch {
                                running = true
                                result = repository.run(config)
                                running = false
                            }
                        }
                    },
                ) { Text(if (running) "探测中…" else "运行自检") }
                OutlinedButton(
                    onClick = {
                        val text = result?.rawJson.orEmpty()
                        if (text.isNotBlank()) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享自检报告"))
                        }
                    },
                ) { Text("分享报告") }
            }
        }
        if (running) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        result?.let { report ->
            if (!report.ok) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("自检失败", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(report.error ?: "未知错误", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(report.backends, key = { it.backend }) { backend ->
                BackendCard(
                    backend = backend,
                    expanded = expanded,
                    onToggle = { key -> expanded = if (key in expanded) expanded - key else expanded + key },
                )
            }
            item {
                Text(
                    "报告已保存到应用私有目录；可在日志中查看完整 JSON。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BackendCard(
    backend: BackendReport,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(backend.backend, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                StatusPill(backend.verdict, verdictColor(backend.verdict))
            }
            if (backend.renderer.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    backend.renderer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!backend.available) {
                Spacer(Modifier.height(6.dp))
                Text("驱动不存在或无法探测", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(8.dp))
                backend.checks.forEach { check: CheckItem ->
                val key = backend.backend + "/" + check.name
                val open = key in expanded
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(key) }
                        .padding(vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(check.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        StatusPill(check.status, verdictColor(check.status))
                    }
                    if (open && check.detail.isNotBlank()) {
                        Text(
                            check.detail,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                }
            }
        }
    }
}
