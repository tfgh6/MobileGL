// SPDX-License-Identifier: LGPL-3.0-only
package top.mobilegl.plugin.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.unit.sp
import top.mobilegl.plugin.BuildConfig
import top.mobilegl.plugin.config.ConfigStore
import top.mobilegl.plugin.config.PluginConfig
import top.mobilegl.plugin.config.RuntimeConfigWriter
import top.mobilegl.plugin.driver.DriverPack
import top.mobilegl.plugin.driver.DriverRepository
import top.mobilegl.plugin.driver.ExternalLibrary
import top.mobilegl.plugin.driver.ExternalLibraryRepository
import top.mobilegl.plugin.selftest.SelfTestRepository
import top.mobilegl.plugin.selftest.SelfTestResult

private val BLUE = Color(0xFF2F6BFF)
private val PASS = Color(0xFF1A9C57)
private val WARN = Color(0xFFB26A00)
private val FAIL = Color(0xFFD1343C)

private sealed interface Dest {
    data object Home : Dest
    data object Settings : Dest
    data object Info : Dest
    data object Drivers : Dest
    data object ConfigFile : Dest
    data object SelfTest : Dest
}

private data class Tab(val label: String, val icon: ImageVector, val dest: Dest)

private val TABS = listOf(
    Tab("首页", Icons.Filled.Home, Dest.Home),
    Tab("设置", Icons.Filled.Settings, Dest.Settings),
    Tab("信息", Icons.Filled.Info, Dest.Info),
)

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
        var lastTab by remember { mutableIntStateOf(0) }

        fun persist(updated: PluginConfig) {
            config = updated
            store.save(updated)
            top.mobilegl.plugin.NativeEnv.apply(updated)
            runtimeStatus = runtimeWriter.write(updated)
        }

        val tabIndex = TABS.indexOfFirst { it.dest == dest }
        val isSubPage = tabIndex < 0

        BackHandler(enabled = isSubPage) { dest = TABS[lastTab].dest }
        BackHandler(enabled = !isSubPage && dest != Dest.Home) { dest = Dest.Home }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (isSubPage) {
                    MobileGlTopBar(
                        title = when (dest) {
                            Dest.Drivers -> "驱动管理"
                            Dest.ConfigFile -> "启动配置"
                            Dest.SelfTest -> "驱动自检"
                            else -> ""
                        },
                        onBack = { dest = TABS[lastTab].dest },
                    )
                }
            },
            bottomBar = {
                if (!isSubPage) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        TABS.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = tabIndex == index,
                                onClick = {
                                    lastTab = index
                                    dest = tab.dest
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = BLUE,
                                    selectedTextColor = BLUE,
                                    indicatorColor = BLUE.copy(alpha = 0.12f),
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (dest) {
                    Dest.Home -> HomeTab(
                        config = config,
                        drivers = drivers,
                        externalLibraries = externalLibraries,
                        lastReport = selfTestRepository.cached(),
                        onOpenDrivers = { lastTab = 0; dest = Dest.Drivers },
                        onOpenConfig = { lastTab = 0; dest = Dest.ConfigFile },
                        onOpenSelfTest = { lastTab = 0; dest = Dest.SelfTest },
                    )
                    Dest.Settings -> SettingsTab(
                        config = config,
                        runtime = runtimeStatus,
                        onSave = { persist(it) },
                        onOpenConfigFile = { lastTab = 1; dest = Dest.ConfigFile },
                    )
                    Dest.Info -> InfoTab(
                        config = config,
                        drivers = drivers,
                        externalLibraries = externalLibraries,
                        lastReport = selfTestRepository.cached(),
                        onOpenSelfTest = { lastTab = 2; dest = Dest.SelfTest },
                        onOpenDrivers = { lastTab = 2; dest = Dest.Drivers },
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
                        onActivate = { pack ->
                            val icd = runCatching { driverRepository.generateIcd(pack) }.getOrNull()
                            persist(config.copy(activeDriverId = pack.id, activeIcdPath = icd?.absolutePath))
                        },
                        onClearActive = { persist(config.copy(activeDriverId = null, activeIcdPath = null)) },
                        onSelectSelfTestLibrary = { lib ->
                            persist(config.copy(selfTestLibraryPath = lib?.file?.absolutePath))
                        },
                    )
                    Dest.ConfigFile -> ConfigScreen(
                        writer = runtimeWriter,
                        status = runtimeStatus,
                        onStatusChanged = { runtimeStatus = it },
                    )
                    Dest.SelfTest -> SelfTestScreen(repository = selfTestRepository, config = config)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileGlTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
    )
}

@Composable
private fun MobileGlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = BLUE,
            onPrimary = Color.White,
            background = Color(0xFFF7F8FD),
            surface = Color.White,
            surfaceVariant = Color(0xFFF2F4FA),
            onSurface = Color(0xFF191C24),
            onSurfaceVariant = Color(0xFF6B7280),
            outline = Color(0xFFE3E7F0),
        ),
        content = content,
    )
}

@Composable
private fun HomeTab(
    config: PluginConfig,
    drivers: List<DriverPack>,
    externalLibraries: List<ExternalLibrary>,
    lastReport: SelfTestResult?,
    onOpenDrivers: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenSelfTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BigTitle("MobileGL")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (config.sfpewEnabled && BuildConfig.SFPEW_BUNDLED) "SFPEW → MobileGL" else "MobileGL 直连",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "渲染后端：" + config.backend,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val verdict = lastReport?.backends?.joinToString(" / ") { it.verdict }
                    if (verdict.isNullOrBlank()) {
                        StatusPill("未自检", MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        StatusPill(verdict, verdictColor(verdict))
                    }
                    StatusPill(if (config.sfpewEnabled && BuildConfig.SFPEW_BUNDLED) "SFPEW 已启用" else "直连模式", if (config.sfpewEnabled && BuildConfig.SFPEW_BUNDLED) BLUE else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionLabel("功能")
        ActionRow("驱动管理", "驱动包、外部 .so 导入/更新", onOpenDrivers)
        ActionRow("启动配置", "runtime.conf 与环境变量", onOpenConfig)
        ActionRow("驱动自检", "失败项、替代方案与影响说明", onOpenSelfTest)

        SectionLabel("状态")
        InfoCard {
            InfoRow("设备", Build.MODEL + " · Android " + Build.VERSION.RELEASE)
            InfoRow("驱动包", drivers.size.toString() + " 个")
            InfoRow("外部库", externalLibraries.size.toString() + " 个")
            InfoRow("配置", if (config.sfpewEnabled && BuildConfig.SFPEW_BUNDLED) "SFPEW 包装器" else "直连 MobileGL")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoTab(
    config: PluginConfig,
    drivers: List<DriverPack>,
    externalLibraries: List<ExternalLibrary>,
    lastReport: SelfTestResult?,
    onOpenSelfTest: () -> Unit,
    onOpenDrivers: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BigTitle("信息")
        SectionLabel("应用")
        InfoCard {
            InfoRow("版本", BuildConfig.VERSION_NAME)
            LinkRow("GitHub 开源页面", "MobileGL / SimpleFPEWrapper") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MobileGL-Dev")))
            }
            LinkRow("驱动自检", "查看完整报告与说明", onOpenSelfTest)
        }
        SectionLabel("组件")
        InfoCard {
            LinkRow("驱动包", drivers.size.toString() + " 个", onOpenDrivers)
            LinkRow("外部库", externalLibraries.size.toString() + " 个", onOpenDrivers)
            val verdict = lastReport?.backends?.joinToString(" / ") { it.verdict }
            InfoRow("最近自检", verdict?.ifBlank { "未运行" } ?: "未运行")
        }
        SectionLabel("关于")
        InfoCard {
            InfoRow("开发者", "MobileGL-Dev")
            InfoRow("开源许可证", "GNU LGPL-3.0")
            InfoRow("兼容启动器", "FCL · Zalith · Pojav")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun BigTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = BLUE,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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