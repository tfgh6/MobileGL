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
import top.mobilegl.plugin.selftest.CheckExplain
import top.mobilegl.plugin.selftest.CheckExplanations
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
            SectionCard("驱动自检") {
                Text(
                    "与官方 MobileGL Action APK 使用同一套 Driver POST（GLES + Vulkan），读取驱动真实能力。" +
                        "失败项会说明影响，替代项会说明 MobileGL 用什么方案绕过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val activeLib = config.selfTestLibraryPath ?: config.activeRendererLibPath
                if (!activeLib.isNullOrBlank()) {
                    Text(
                        "使用外部库自检：" + activeLib,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!running) {
                                scope.launch {
                                    running = true
                                    result = repository.run(config, config.selfTestLibraryPath ?: config.activeRendererLibPath)
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
                    ) { Text("分享原始报告") }
                }
                if (running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        val report = result
        if (report == null) {
            item {
                SectionCard("还没有自检结果") {
                    Text("点上面的“运行自检”。它会加载 libMobileGL.so 并枚举当前驱动。", style = MaterialTheme.typography.bodySmall)
                }
            }
            return@LazyColumn
        }
        if (!report.ok) {
            item {
                SectionCard("自检失败") {
                    Text(report.error ?: "未知错误", style = MaterialTheme.typography.bodySmall)
                }
            }
            return@LazyColumn
        }

        item { SummaryCard(report) }

        item {
            Text(
                "详细信息（向下滚动查看全部检查项）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(report.backends, key = { it.backend }) { backend ->
            BackendDetailCard(
                backend = backend,
                expanded = expanded,
                onToggle = { key -> expanded = if (key in expanded) expanded - key else expanded + key },
            )
        }
        item {
            Text(
                "报告同时保存在应用私有目录，可点上方“分享原始报告”导出。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(report: SelfTestResult) {
    val checks = report.backends.flatMap { backend -> backend.checks.map { backend to it } }
    val fails = checks.filter { it.second.status.equals("FAIL", true) }
    val warns = checks.filter { it.second.status.equals("WARN", true) }
    val passes = checks.count { it.second.status.equals("PASS", true) }

    SectionCard("结论") {
        report.backends.forEach { backend ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (backend.backend.contains("vulkan", true)) "Vulkan (Magma)" else "GLES (Espryt)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                StatusPill(backend.verdict, verdictColor(backend.verdict))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "通过 " + passes + " 项；替代 " + warns.size + " 项；失败 " + fails.size + " 项",
            style = MaterialTheme.typography.bodySmall,
        )
        if (fails.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("会影响游戏的功能", style = MaterialTheme.typography.titleSmall, color = verdictColor("FAIL"))
            fails.forEach { (_, check) -> ExplainRow(check, "FAIL") }
        }
        if (warns.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("已用替代方案", style = MaterialTheme.typography.titleSmall, color = verdictColor("WARN"))
            warns.forEach { (_, check) -> ExplainRow(check, "WARN") }
        }
    }
}

@Composable
private fun ExplainRow(check: CheckItem, status: String) {
    val explain: CheckExplain = CheckExplanations.of(check.name)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(explain.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            StatusPill(CheckExplanations.statusZh(check.status), verdictColor(check.status))
        }
        Text(
            if (status == "WARN" && explain.fallback != null) explain.fallback else explain.impact,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!check.name.equals(explain.title, true)) {
            Text("原始项：" + check.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackendDetailCard(
    backend: BackendReport,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (backend.backend.contains("vulkan", true)) "Vulkan (Magma)" else "GLES (Espryt)",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(backend.verdict, verdictColor(backend.verdict))
            }
            if (backend.renderer.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(backend.renderer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!backend.available) {
                Spacer(Modifier.height(6.dp))
                Text("驱动不存在或无法探测", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(8.dp))
                backend.checks.forEach { check ->
                    val key = backend.backend + "/" + check.name
                    val open = key in expanded
                    val explain = CheckExplanations.of(check.name)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(key) }
                            .padding(vertical = 5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(explain.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            StatusPill(CheckExplanations.statusZh(check.status), verdictColor(check.status))
                        }
                        Text(
                            explain.impact,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (open && check.detail.isNotBlank()) {
                            Text(check.detail, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                }
            }
        }
    }
}