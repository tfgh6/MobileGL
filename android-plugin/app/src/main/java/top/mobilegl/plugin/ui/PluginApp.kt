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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.mobilegl.plugin.config.ConfigStore
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.config.RuntimeConfigWriter
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.DriverRepository
import top.mobilegl.plugin.driver.ExternalLibrary
import top.mobilegl.plugin.driver.ExternalLibraryRepository
import top.mobilegl.plugin.selftest.SelfTestRepository
import top.mobilegl.plugin.selftest.SelfTestResult

private val ACCENT = Color(0xFF7CD4FD)
private val PASS = Color(0xFF6BD58A)
private val WARN = Color(0xFFFFB74D)
private val FAIL = Color(0xFFEF6B6B)

private sealed interface Dest {
    data object Home : Dest
    data object Drivers : Dest
    data object Config : Dest
    data object SelfTest : Dest
    data object About : Dest
}

@Composable
fun MobileGlPluginApp() {
    MobileGlTheme {
        val context = LocalContext.current
        val store = remember { ConfigStore(context) }
        val driverRepository = remember { DriverRepository(context) }
        val externalRepository = remember { ExternalLibraryRepository(context) }
        val selfTestRepository = remember { SelfTestRepository(context) }
        val runtimeWriter = remember { RuntimeConfigWriter(context) }

        var config by remember { mutableStateOf(store.load()) }
        var drivers by remember { mutableStateOf(driverRepository.list()) }
        var externalLibraries by remember { mutableStateOf(externalRepository.list()) }
        var runtimeStatus by remember { mutableStateOf(runtimeWriter.write(config)) }
        var dest by remember { mutableStateOf<Dest>(Dest.Home) }

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
                    title = when (dest) {
                        Dest.Home -> "MobileGL 插件"
                        Dest.Drivers -> "驱动管理"
                        Dest.Config -> "启动配置"
                        Dest.SelfTest -> "驱动自检"
                        Dest.About -> "关于"
                    },
                    showBack = dest != Dest.Home,
                    onBack = { dest = Dest.Home },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (dest) {
                    Dest.Home -> HomeScreen(
                        config = config,
                        drivers = drivers,
                        externalCount = externalLibraries.size,
                        runtime = runtimeStatus,
                        lastReport = selfTestRepository.cached(),
                        onOpenDrivers = { dest = Dest.Drivers },
                        onOpenConfig = { dest = Dest.Config },
                        onOpenSelfTest = { dest = Dest.SelfTest },
                        onOpenAbout = { dest = Dest.About },
                    )
                    Dest.Drivers -> DriverScreen(
                        repository = driverRepository,
                        externalRepository = externalRepository,
                        drivers = drivers,
                        externalLibraries = externalLibraries,
                        config = config,
                        onRefresh = {
                            drivers = driverRepository.list()
                            externalLibraries = externalRepository.list()
                        },
                        onActivate = { activateDriver(it) },
                        onClearActive = { clearDriver() },
                        onSelectSelfTestLibrary = { lib ->
                            persist(config.copy(selfTestLibraryPath = lib?.file?.absolutePath))
                        },
                    )
                    Dest.Config -> ConfigScreen(
                        writer = runtimeWriter,
                        status = runtimeStatus,
                        onStatusChanged = { runtimeStatus = it },
                    )
                    Dest.SelfTest -> SelfTestScreen(
                        repository = selfTestRepository,
                        config = config,
                    )
                    Dest.About -> AboutScreen(
                        config = config,
                        drivers = drivers,
                        externalLibraries = externalLibraries,
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
    externalCount: Int,
    runtime: RuntimeConfigWriter.Status,
    lastReport: SelfTestResult?,
    onOpenDrivers: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("当前状态") {
            InfoRow("渲染后端", config.backend)
            InfoRow("渲染器", "SFPEW → MobileGL")
            InfoRow("设备", Build.MODEL + " · Android " + Build.VERSION.RELEASE)
            InfoRow("驱动包", drivers.size.toString() + " 个 · 外部库 " + externalCount + " 个")
            InfoRow(
                "运行时配置",
                if (runtime.sharedWritten) "已写入" else "仅私有副本",
            )
            val verdict = lastReport?.backends?.joinToString(" / ") { it.verdict }.orEmpty()
            InfoRow("最近自检", verdict.ifEmpty { "未运行" })
        }

        MenuButton("驱动管理", "驱动包、外部 .so 导入与更新（含 SFPEW / MobileGL 库）", onOpenDrivers)
        MenuButton("启动配置", "编辑 /sdcard/MobileGL/runtime.conf 与常用开关", onOpenConfig)
        MenuButton("驱动自检", "失败项、替代方案与影响说明；详情向下滚动", onOpenSelfTest)
        MenuButton("关于", "许可、组件与自检报告导出", onOpenAbout)

        SectionCard("说明") {
            Text(
                "本插件是 Minecraft 启动器渲染插件：Zalith / FCL 通过 fclPlugin_V2 / legacy meta-data " +
                    "加载 libSimpleFPEWrapper.so，再由它以 libMobileGL.so 为后端。游戏内配置以启动器插件设置为准；" +
                    "本界面负责自检、驱动/库管理与配置排查。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MenuButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AboutScreen(
    config: PluginConfig,
    drivers: List<DriverPack>,
    externalLibraries: List<ExternalLibrary>,
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
            InfoRow("名称", "MobileGL Plugin / SFPEW")
            InfoRow("后端", config.backend)
            InfoRow("驱动包", drivers.size.toString() + " 个")
            InfoRow("外部库", externalLibraries.size.toString() + " 个")
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
                "MobileGL 为 LGPL-3.0-only；SimpleFPEWrapper 为其上游组件（LGPL-3.0）；" +
                    "Mesa PanVK（MIT）、Vulkan-Loader（Apache-2.0）在后续驱动阶段引入时单独声明。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard("链接") {
            Text("MobileGL: github.com/MobileGL-Dev/MobileGL", style = MaterialTheme.typography.bodySmall)
            Text("SimpleFPEWrapper: github.com/MobileGL-Dev/SimpleFPEWrapper", style = MaterialTheme.typography.bodySmall)
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
