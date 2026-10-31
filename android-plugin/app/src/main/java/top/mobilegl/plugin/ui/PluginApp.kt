// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.mobilegl.plugin.config.ConfigStore
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.config.RuntimeConfigWriter
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.DriverRepository
import top.mobilegl.plugin.selftest.SelfTestRepository
import top.mobilegl.plugin.selftest.SelfTestResult

private val ACCENT = Color(0xFF7CD4FD)
private val PASS = Color(0xFF6BD58A)
private val WARN = Color(0xFFFFB74D)
private val FAIL = Color(0xFFEF6B6B)

private data class Page(val label: String, val icon: ImageVector)

private val PAGES = listOf(
    Page("首页", Icons.Filled.Home),
    Page("设置", Icons.Filled.Settings),
    Page("驱动", Icons.Filled.Refresh),
    Page("信息", Icons.Filled.Info),
)

@Composable
fun MobileGlPluginApp() {
    MobileGlTheme {
        val context = LocalContext.current
        val store = remember { ConfigStore(context) }
        val driverRepository = remember { DriverRepository(context) }
        val selfTestRepository = remember { SelfTestRepository(context) }
        val runtimeWriter = remember { RuntimeConfigWriter(context) }

        var config by remember { mutableStateOf(store.load()) }
        var drivers by remember { mutableStateOf(driverRepository.list()) }
        var runtimeStatus by remember { mutableStateOf(runtimeWriter.write(config)) }
        var page by remember { mutableIntStateOf(0) }
        var showSelfTest by remember { mutableStateOf(false) }

        fun persist(updated: PluginConfig) {
            config = updated
            store.save(updated)
            top.mobilegl.plugin.NativeEnv.apply(updated)
            runtimeStatus = runtimeWriter.write(updated)
        }

        fun activateDriver(pack: DriverPack) {
            val icd = runCatching { driverRepository.generateIcd(pack) }.getOrNull()
            persist(config.copy(activeDriverId = pack.id, activeIcdPath = icd?.absolutePath))
        }

        fun clearDriver() {
            persist(config.copy(activeDriverId = null, activeIcdPath = null))
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                MobileGlTopBar(
                    title = if (showSelfTest) "驱动自检" else PAGES[page].label,
                    showBack = showSelfTest,
                    onBack = { showSelfTest = false },
                )
            },
            bottomBar = {
                if (!showSelfTest) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        PAGES.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = page == index,
                                onClick = { page = index },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (showSelfTest) {
                    SelfTestScreen(
                        repository = selfTestRepository,
                        config = config,
                    )
                } else {
                    when (page) {
                        0 -> HomeScreen(
                            config = config,
                            drivers = drivers,
                            runtime = runtimeStatus,
                            lastReport = selfTestRepository.cached(),
                            onOpenSelfTest = { showSelfTest = true },
                            onOpenDrivers = { page = 2 },
                        )
                        1 -> SettingsScreen(
                            config = config,
                            runtime = runtimeStatus,
                            onSave = { persist(it) },
                        )
                        2 -> DriverScreen(
                            repository = driverRepository,
                            drivers = drivers,
                            config = config,
                            onRefresh = { drivers = driverRepository.list() },
                            onActivate = { activateDriver(it) },
                            onClearActive = { clearDriver() },
                        )
                        else -> InfoScreen(
                            config = config,
                            drivers = drivers,
                            lastReport = selfTestRepository.cached(),
                            onShareReport = {
                                val text = selfTestRepository.rawShareText()
                                if (text.isNotBlank()) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "分享自检报告"))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileGlTopBar(title: String, showBack: Boolean, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (showBack) {
                TextButton(onClick = onBack) { Text("返回") }
            }
        },
    )
}

@Composable
private fun MobileGlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ACCENT,
            onPrimary = Color(0xFF00344A),
            background = Color(0xFF101418),
            surface = Color(0xFF161B21),
            surfaceVariant = Color(0xFF1E252C),
            onSurface = Color(0xFFE6EDF3),
            onSurfaceVariant = Color(0xFFAEB9C4),
        ),
        content = content,
    )
}

@Composable
private fun HomeScreen(
    config: PluginConfig,
    drivers: List<DriverPack>,
    runtime: RuntimeConfigWriter.Status,
    lastReport: SelfTestResult?,
    onOpenSelfTest: () -> Unit,
    onOpenDrivers: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("设备") {
            InfoRow("型号", Build.MODEL)
            InfoRow("系统", "Android " + Build.VERSION.RELEASE)
            InfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        }
        SectionCard("当前配置") {
            InfoRow("渲染后端", config.backend)
            InfoRow("已安装驱动", drivers.size.toString() + " 个")
            InfoRow("激活驱动", config.activeDriverId ?: "系统驱动（未激活自定义驱动）")
            InfoRow(
                "运行时配置",
                if (runtime.sharedWritten) "已写入共享路径" else "仅私有路径（共享路径不可写）",
            )
        }
        SectionCard("驱动自检") {
            val verdict = lastReport?.backends?.joinToString(" / ") { it.verdict }.orEmpty()
            Text(
                if (lastReport == null) "尚未运行自检。自检会加载 libMobileGL 并探测 GLES/Vulkan 能力。"
                else "最近一次：$verdict",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSelfTest) { Text("运行自检") }
                OutlinedButton(onClick = onOpenDrivers) { Text("驱动管理") }
            }
        }
        SectionCard("说明") {
            Text(
                "本插件是 Minecraft 启动器（Pojav/Zalith/FCL）的渲染器插件：启动器负责把 " +
                    "libMobileGL.so 与基础环境变量带入游戏进程；本界面负责配置、驱动包管理与自检。" +
                    "开源 Vulkan 驱动（Mesa PanVK）集成将在后续阶段接入。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InfoScreen(
    config: PluginConfig,
    drivers: List<DriverPack>,
    lastReport: SelfTestResult?,
    onShareReport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("插件") {
            InfoRow("名称", "MobileGL Plugin")
            InfoRow("渲染器", "MobileGL / " + config.backend)
            InfoRow("模式", "Minecraft Java 启动器渲染插件")
            InfoRow("驱动包", drivers.size.toString() + " 个")
        }
        SectionCard("最近自检") {
            if (lastReport == null) {
                Text("无记录", style = MaterialTheme.typography.bodyMedium)
            } else {
                lastReport.backends.forEach { backend ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(backend.backend, modifier = Modifier.weight(1f))
                        StatusPill(backend.verdict, verdictColor(backend.verdict))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onShareReport) { Text("分享原始 JSON") }
            }
        }
        SectionCard("开源与许可") {
            Text(
                "MobileGL 以 LGPL-3.0-only 发布；本插件新增代码沿用同一许可。" +
                    "后续若打包 Mesa PanVK（MIT）、Vulkan-Loader（Apache-2.0）会在关于页与 NOTICE 中声明。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard("链接") {
            Text("MobileGL: github.com/MobileGL-Dev/MobileGL", style = MaterialTheme.typography.bodySmall)
            Text("MobileGlues: github.com/MobileGL-Dev/MobileGlues-plugin", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun verdictColor(verdict: String): Color = when (verdict.uppercase()) {
    "OK", "PASS" -> PASS
    "DEGRADED", "WARN" -> WARN
    "UNSUPPORTED", "FAIL" -> FAIL
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
