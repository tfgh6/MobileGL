# 配置 UI App 设计 + MobileGL / MobileGlues 2.0 源码调研

- 作者：ui-app（Team 成员）
- 日期：2026-10-31
- 范围：MobileGL-Dev 组织仓库与代码结构、Magma/DirectVulkan 的 Vulkan 加载方式、MobileGlues 2.0（plugin）的 UI/配置/注入模型；据此设计"PanVK/MobileGL 配置 UI App"，附 Kotlin/Compose + JNI 代码骨架。
- 采集方式：GitHub REST API（org/repos、contents）、raw.githubusercontent.com 源码原文、Khronos Vulkan-Loader 官方文档（经本机代理抓取）。GitHub API 后期限流，部分 commit SHA 未取到，版本以源码内声明为准。
- 标注约定：**[已核实]** = 我读到的源码/文档原文；**[未验证]** = 有合理推断但缺一手证据，或依赖 task-2 结论；**[待实测]** = 只能在目标设备（Mali-G925/Android）上确认。
- 关联：task-1（mesa-panvk.md）、task-2（android-loader.md）。本文凡涉及 AOSP loader 的结论，以 task-2 的源码证据为准。

---

## 0. 结论摘要（先看这里）

1. **MobileGL 自己完全不读 `VK_ICD_FILENAMES`/`VK_DRIVER_FILES`**。它的配置层只接受 `LIBGL_*` 与 `MOBILEGL_*` 前缀的环境变量（ConfigLoader.cpp 白名单），Vulkan 走 CMake 链接的系统 `vulkan` 目标（Android 上即系统 `libvulkan.so`）。所以"给 MobileGL 设 VK_ICD_FILENAMES"在当前代码上无效——**必须换 loader 或改 MobileGL 的链接/初始化路径**。**[已核实]**
2. MobileGL 的 CMake 已有 `MOBILEGL_VULKAN_LIBRARY` 缓存变量（"Vulkan loader/MoltenVK library to link for iOS builds"），并会 `target_link_libraries(... "${MOBILEGL_VULKAN_LIBRARY}")`。**这是把自捆绑 Khronos Vulkan-Loader 接进 MobileGL 的现成钩子**：Android 构建时传 `-DMOBILEGL_VULKAN_LIBRARY=<我们打包的 libvulkan_loader.so>` 即可，不必改 MobileGL 源码就能换 loader。**[已核实，需在 Android 构建里实测]**
3. Android 系统 `libvulkan.so`（AOSP loader）不读取桌面 loader 的 ICD 环境变量——这是 task-2 的核心结论；本设计因此统一采用"**自捆绑 Khronos Vulkan-Loader + 显式 ICD**"，而不是依赖系统 loader。**[依赖 task-2；未验证独立复核]**
4. 因此 **UI App 给"别的 App"设环境变量在 Android 沙盒下不可行**（无 setenv 跨进程、无 LD_PRELOAD、无 root）。真实可行的交接只有两类：
   - **A. 同 APK/同进程内嵌**（推荐）：MobileGL + 自捆绑 loader + PanVK ICD 都在我们的 APK 里，UI 进程在 `dlopen(libMobileGL)` 之前 JNI `setenv`。
   - **B. 启动器插件**（Minecraft Java 生态已有事实标准）：像 MobileGL 仓库的 `android-plugin` 那样，通过 **Zalith Launch/RendererPlugin-v2 DSL** 或 FCL/Pojav 插件 meta-data 把 `renderer=libMobileGL.so` 和环境变量映射交给启动器，由启动器在它 fork 的游戏进程里设置。UI 只负责"写配置 + 选驱动 + 自检"，**不能也不该尝试给任意第三方 App 注入**。**[已核实插件机制存在]**
5. MobileGL 仓库版本：`Config.h` 中 `CoreVersion = {26, 8, 0, "-dev"}`，即 **26.8.0-dev**（dev 分支，2026-10 仍在更新）；仓库 **无 release、无预编译二进制**（README 明示）。android-plugin 的 Gradle 版本号也是 major=26/minor=8 + 月度 commit 数。**[已核实]**
6. MobileGlues 2.0 = `MobileGlues-plugin` v2.0.0（versionName 2.0.0 / versionCode 2000），Kotlin + Jetpack Compose，**Material3 与 Miuix 两套皮肤共用同一个 AppController**；配置写在 **`/sdcard/MG/config.json`**，由 native 渲染器在游戏启动时读取；插件通过 FCL/Pojav 插件 meta-data 注入渲染器与 env（`renderer=MobileGlues:libmobileglues.so:libmobileglues.so`）。**[已核实]**
7. MobileGlues 2.0 最值得抄的三件事：**wire 值只增不改的配置编码**（Int 枚举 + 未知键原样保留 + 容错解码）、**去抖 300ms 的单一写盘入口**（dirty + 原子写 + 损坏备份）、**UI 状态与页面完全解耦的 AppController**。**不能照搬**的是它的存储模型：`/sdcard/MG` 需要"所有文件访问"或 SAF，权限面太大；PanVK 驱动应放 **app 私有 `filesDir`**，零权限。**[已核实/设计取舍]**
8. 自检（Self-test）应作为**独立 `:probe` 进程**运行：加载自捆绑 loader → `vkCreateInstance` → `vkEnumeratePhysicalDevices` → 读 `VkPhysicalDeviceFeatures`/`Features2`/`Limits`/扩展 → 输出 JSON。重点检查 `fillModeNonSolid`、`vertexPipelineStoresAndAtomics`、`fragmentStoresAndAtomics`、`geometryShader`、`tessellationShader`、`shaderFloat64`、`imageCubeArray`、`wideLines`、`shaderClipDistance`/`shaderCullDistance`，以及 `VK_KHR_swapchain` 等。MobileGL 的 `BackendObject_DirectVulkan` 正是按这些 capability 决定向 GL 层声明哪些扩展/上限（如 `SupportsVertexPipelineStoresAndAtomics ? maxPerStageImageUniforms : 0`）。**[已核实代码路径]**
9. 崩溃回退：**启动标记 + 计数 + A/B 指针**。每次启动先原子写 `state/boot.json`（attempts+1），成功创建 probe/上下文后清零；连续 N 次（建议 3）未清 → 进安全模式（`active=system` 或 `previous`），UI 提示并可一键回滚。MobileGlues 的 `MG_COUNT_LAUNCH`/stats.json 是同类思路但方向相反（统计启动次数用于赞助弹窗），**不要把回退状态交给 native 渲染器自己写**——native 崩了就没机会写。**[设计]**
10. 交付物边界：本文件只做方案 + 关键代码骨架（**未编译，未在设备上跑过**）；完整实现应落在 lead 的 `config-ui` 代码与 `docs/PanVK-MobileGL-Integration-Guide.md`。

---

## 1. MobileGL-Dev 组织仓库全景

来源：`GET https://api.github.com/orgs/MobileGL-Dev/repos?per_page=100`（2026-10-31 抓取，15 个公开仓库）。

| 仓库 | 默认分支 | 语言 | 星 | 最近 push | 用途（据 README/内容判断） |
|---|---|---|---|---|---|
| **MobileGL** | dev | C++ | 240 | 2026-10-19 | 桌面 OpenGL 4.2 实现，多后端（DirectGLES / DirectVulkan）。**本文主角** |
| **MobileGlues** | main | C++ | 233 | 2026-10-11 | MobileGlues 渲染器核心（GL→GLES 3.x），LGPL-2.1 |
| **MobileGlues-plugin** | main | Kotlin | 63 | 2026-09-06 | **MobileGlues 2.0 的配置 App / 启动器插件**（v2.0.0） |
| **MobileGlues-release** | main | — | 1427 | 2026-08-11 | 稳定版发布仓库（APK + 文档/兼容性矩阵） |
| **MobileGL-docs** | main | HTML | 2 | 2026-07-29 | 文档站 |
| **MobileGLCodeManager** | main | C++ | 1 | 2026-02-02 | 开发期代码生成工具（与驱动无关） |
| **MobileGLDebugger** | main | C++ | 0 | 2026-08-18 | 调试工具 |
| **SimpleFPEWrapper / SFPEW-Docs** | main | C++/Python | 15 | 2026-10-31 | 固定管线模拟包装 |
| glslang / gl4es / apitrace / flat_hash_map / anland / FastSTL | — | fork/自研 | — | — | 第三方依赖与实验（anland = Wayland on Android） |

注：
- `MobileGlues-plugin` 的 git 树里有一个 **`commit: MobileGlues`**（gitlink/子模块）且 `settings.gradle.kts` 里 `include(":MobileGlues")`，即**插件 APK 直接编译 MobileGlues 核心仓库的 C++**，不走预编译 so。**[已核实]**
- 没有单独的"MobileGlues 2.0"仓库：**2.0 就是 `MobileGlues-plugin` 的 2.0.0 版本**（README "latest version: 2.0.0"、Gradle versionName 2.0.0）。**[已核实]**
- 组织里**没有** PanVK/Mesa 相关仓库；"PanVK-Mali-Android-Stubs" 不在该组织（用户提到的可能是第三方项目，未在本次抓取中出现，**未验证**）。

---

## 2. MobileGL 仓库（dev @ 2026-10）

### 2.1 版本与顶层目录

`MobileGL/Config.h`：`CoreVersion = {26, 8, 0, "-dev", VersionType::Development}`，`CacheVersion = 0`。**[已核实]**

顶层（GitHub contents API）：

```
.github/ 3rdparty/ MobileGL/ android-plugin/ buildsystem/ include/ scripts/ tools/
CMakeLists.txt build.gradle selfcheck.c README.md COPYING* .gitmodules .clang-*
```

`MobileGL/` 内：`Config.h`、`ConfigLoader.cpp`、`Init.cpp/.h`、`GlobalObjects.cpp`、`MG_Backend/`、`MG_Impl/`、`MG_State/`、`MG_Util/`、`MG_Test/`、`MG_Benchmark/`、`MG_IntegrationTest/`。

模块职责（README）：
- **MG_State**：GL/EGL 状态跟踪。
- **MG_Impl**：GL 前端 API 实现（状态 + 后端函数表）。
- **MG_Backend**：后端翻译层，当前 `DirectGLES`（目标 GLES）与 `DirectVulkan`（目标 Vulkan，即用户说的 Magma）。
- **MG_Util**：着色器翻译（glslang/SPIRV-Cross）、后端加载器、日志、自检探针。

### 2.2 Vulkan（DirectVulkan/Magma）加载路径 —— 关键结论

**结论：MobileGL 没有自己的 Vulkan 动态加载器；它把 Vulkan 头文件直接编进来，并由 CMake 链接平台的 `vulkan` 目标。Android 上就是系统 `libvulkan.so`。它也不读 ICD 环境变量。** 证据链：

1. `MobileGL/MG_Backend/DirectVulkan/Renderer/VulkanRenderer.cpp` 直接 `#include <vulkan/vulkan_core.h>`、`<vulkan/utility/vk_format_utils.h>`。**[已核实]**
2. `CMakeLists.txt`（dev）：
   - 第 18 行：`set(MOBILEGL_VULKAN_LIBRARY "" CACHE FILEPATH "Vulkan loader/MoltenVK library to link for iOS builds")`
   - 第 205-207 行：`add_subdirectory(3rdparty/Vulkan-Headers)` 等（只有 headers + utility，无 loader 实现）。
   - 第 598 行附近：`vulkan`；第 640-641 行：`if (MOBILEGL_VULKAN_LIBRARY) target_link_libraries(MobileGL PUBLIC "${MOBILEGL_VULKAN_LIBRARY}")`；否则 `find_package(Vulkan)` + `Vulkan::Vulkan`。**[已核实]**
3. `MobileGL/ConfigLoader.cpp`：`IsAcceptedPrefix()` 只接受 `LIBGL_` 与 `MOBILEGL_`；`InitializeAcceptedEnvVariables()` 遍历 `environ` 后放进私有 map。**没有 `VK_` 前缀**，也没有 `getenv("VK_ICD_FILENAMES")`。**[已核实]**
4. 初始化链：`MobileGL/Init.cpp` → `MG_Backend::Init()`（`MG_Backend/Init.cpp`）→ 按 `MG_Config::ActiveBackendType` new `DirectVulkan::BackendObject_DirectVulkan` → `BackendObject_DirectVulkan::InitWindowSurface()` → `VulkanRenderer(config)` + `Initialize()`（创建 VkInstance/Device）。**[已核实]**
5. 能力查询在 `MG_Util/BackendLoaders/Vulkan/Loader.cpp` 的 `QueryVulkanCapabilities()`；`Loader.h` 的 `VulkanCapabilities` 已有 `SupportsVertexPipelineStoresAndAtomics`、`SupportsFragmentStoresAndAtomics`、`SupportsGeometryShader`、`SupportsShaderFloat64`、`SupportsImageCubeArray`、`Supports2DArrayCompatible3DImages` 等字段。**[已核实]**
6. 这些 capability 直接决定 GL 侧声明：`BackendObject_DirectVulkan.cpp` 中 `MaxVertexShaderStorageBlocks = SupportsVertexPipelineStoresAndAtomics ? maxPerStageStorageBlocks : 0`（约 983 行）、`MaxVertexImageUniforms = ... ? maxPerStageImageUniforms : 0`（约 1000 行）、`SupportsShaderClipDistance/CullDistance` 控制 `MaxClipDistances/MaxCullDistances`（约 1017-1031 行）、`UpdateAdvertisedExtensions()` 按能力广告扩展。**[已核实]**
7. 反过来，**这正是"原厂 ARM Vulkan 驱动缺 fillModeNonSolid / vertexPipelineStoresAndAtomics"会伤到 MobileGL 的机制**：缺能力 → MobileGL 把对应 GL 上限/扩展降为 0/不广告 → GL 4.2 应用（如 Minecraft 的 Sodium/Iris 路径）看到残缺 GL。**[已核实代码路径；"具体哪个 GL 扩展因此缺失"需按目标 App 实测]**

对集成的直接含义：
- 想让 PanVK 生效，**必须让 MobileGL 链接的 Vulkan loader 变成我们可控的 loader**。`MOBILEGL_VULKAN_LIBRARY` 是现成入口；Android 构建应显式传它，避免 `find_package(Vulkan)` 命中 NDK 的 `libvulkan.so`。**[已核实，构建需实测]**
- 若走启动器插件路线，插件 APK 的 `jniLibs/arm64-v8a/` 里同时放 `libMobileGL.so` 与自捆绑 loader（例如 `libvulkan_loader_khr.so`），并让 MobileGL 的 DT_NEEDED 指向后者；ICD 用 `VK_DRIVER_FILES` 指向 APK/私有目录里的 PanVK .so。**[设计，需实测 linker 行为]**

### 2.3 环境变量（已核实部分）

`README.md` 的 Environment Variables 表 + `Config.h` 的 `FeaturesTable` 注释：

| 变量 | 取值 | 默认 | 证据 |
|---|---|---|---|
| `MOBILEGL_BACKEND_TYPE` | `DirectGLES` / `DirectVulkan` | `DirectGLES` | README |
| `MOBILEGL_DISABLE_TIMERQUERY` | 0/1 | 0 | README + Config.h |
| `MOBILEGL_ESPRYT_USE_ANGLE` | 0/1 | 0 | README |
| `MOBILEGL_MAGMA_DISABLE_SUBGROUP` | 0/1 | 0 | README + Config.h |
| `MOBILEGL_ADVERTISE_FP64` | 0/1（README 截断，**未验证完整语义**） | — | README |
| `MOBILEGL_MAGMA_FRAMESINFLIGHT` | 整数（默认 3，夹到 swapchain maxImageCount 且 ≥2） | 3 | `VulkanRendererConfig.h` 注释 |
| `MOBILEGL_LOG_FILE_PATH` | 日志文件路径（在 ConfigLoader::Init 之前读取） | — | Config.h 注释 |
| `LIBGL_ES` / 其余 `LIBGL_*` | 兼容旧名 | — | ConfigLoader 白名单 |

布尔真值规则（ConfigLoader.cpp）：**set、非空、非 "0"、非 "false"（大小写不敏感）**。这决定了 UI 里开关的序列化必须写 `1`/`0`，不能写 `true`/`false`。**[已核实]**

### 2.4 Android 构建方式

MobileGL 的 Android 产物在 **`android-plugin/`**（不是仓库根的 build.gradle）：

- `android-plugin/settings.gradle.kts`：`include(":app", ":MobileGL")`，`:MobileGL` 的 projectDir = `..`（直接编译仓库根 CMake 工程）。**[已核实]**
- `android-plugin/build.gradle.kts`：AGP **8.6.0**；`mobilegl.abis`/`MOBILEGL_ABIS` 默认 **arm64-v8a**（`all` 时加 x86_64）；日志等级 `MOBILEGL_LOG_ACTIVE_LEVEL` 默认 `MOBILEGL_LOG_LEVEL_INFO`。**[已核实]**
- `android-plugin/app/build.gradle.kts`：
  - `buildscript` 依赖 `com.github.ZalithLauncher.RendererPlugin-v2:dsl:1.0-alpha6`（JitPack），并 `apply(plugin = "com.launchers_plugin.renderer.dsl")`。**[已核实]**
  - `namespace=top.mobilegl.plugin`，`applicationId=top.mobilegl.plugin`，`compileSdk=34`，`minSdk=26`，`targetSdk=34`，`ndkVersion="27.3.13750724"`。**[已核实]**
  - `pluginRendererConfig` 用 DSL 描述渲染器：`displayName="MobileGL"`、`rendererId="opengles3"`、`rendererGLPath=nativePath("libMobileGL.so")`、`rendererEGLPath=nativePath("libMobileGL.so")`，env 含 `LIBGL_ES=3`、`selectable(MOBILEGL_BACKEND_TYPE, DirectGLES, [DirectVulkan])`、若干 `toggleable(MOBILEGL_*)`。**[已核实]**
  - 同时保留 legacy manifest 占位符（`fclPlugin`、`des`、`renderer`、`boatEnv`、`pojavEnv`），`rendererLib="libMobileGL.so"`。**[已核实]**
  - flavors：`plugin` / `trace`；`packaging.jniLibs.useLegacyPackaging = true`；CMake 版本 `3.22.1`；APK 命名 `MobileGL-plugin-release-<sha>.apk`。**[已核实]**
- **UI 现状**：只有 `PostActivity`（驱动 POST 自检结果页，Java + 手写 LinearLayout/ScrollView）、`PluginActivity`（立即 finish 的空壳）、`BenchService`（独立 `:bench` 进程跑基准）。**没有配置页、没有驱动管理页、没有 Compose**。所以"配置 UI App"是在这块上扩展，而不是替换掉一个已有 UI。**[已核实]**

### 2.5 自检（POST）现状

- `MG_Util/SelfTest/`：`DriverPost.cpp/.h`、`DriverPostJni.cpp`、`DriverBenchJni.cpp`、`DriverBugProbes.*`、`PersistentBufferOrderingProbe.*`、`PrimitivesGeneratedNoXfbProbe.*`（含 .vert/.tesc/.tese 与预编译 SPIR-V 头）。
- `PostActivity.java`（33 KB）：标题 **"MobileGL Driver POST"**，PASS/WARN/FAIL 三色、按能力展开/折叠的格式-能力表、缓存单次结果（`POST_LOCK`、`postCompleted`）、Bench 按钮通过 `BenchService` 广播回传 JSON。**[已核实]**
- 对 UI 设计的启示：**POST 结果应是结构化 JSON（能力矩阵），UI 只渲染**；MobileGL 已把"报告 JSON"这条路走通，配置 App 可直接复用/扩展这套 JNI。

## 3. MobileGlues 2.0（MobileGlues-plugin v2.0.0）

### 3.1 仓库与构建

`MobileGlues-plugin/app/build.gradle.kts`（main，2026-10 读）：
- `namespace/applicationId = com.fcl.plugin.mobileglues`；`compileSdk=36`、`minSdk=26`、`targetSdk=36`；`ndkVersion="27.3.13750724"`；`versionCode=2000`、`versionName="2.0.0"`。**[已核实]**
- `externalNativeBuild.cmake` 指向 `src/main/cpp/CMakeLists.txt`（只编一个 `mobileglues_info_getter` JNI 小库，见 3.4）；渲染器本体由 `implementation(project(":MobileGlues"))` 编进来。**[已核实]**
- `packaging.jniLibs.useLegacyPackaging = true`；`buildFeatures { buildConfig; aidl; compose }`。**[已核实]**
- 依赖版本（`gradle/libs.versions.toml`）：AGP **8.12.3**、Kotlin **2.3.20**、Compose BOM **2026.06.01**、activity **1.13.0**、lifecycle **2.9.4**、coroutines **1.10.2**、Gson **2.13.2**、Miuix **0.8.8**（注释：0.9.0 要 compileSdk 37，故停在 0.8.8）。**[已核实]**

### 3.2 UI 架构（值得抄）

- 入口 `MainActivity.kt` + `ui/AppRoot.kt`；皮肤两套：`ui/material/*` 与 `ui/miuix/*`，同一份 `AppController`。
- `ui/AppController.kt`（45 KB）是唯一状态源：`AppTab { Home, Settings, Info }`、`AppSubPage { GlInfo, Privacy, ThirdParty }`；`MutableStateFlow` 暴露 `tab/subPage/snackbar/confirmRequest/configStore/loadState/benchState/glInfo` 等；页面本身无状态，只读 controller + 回调。**[已核实]**
- `MaterialApp.kt`：`Scaffold + NavigationBar/NavigationRail`（高度不足时自动切侧栏）、`AnimatedContent` 过场、`rememberSaveableStateHolder` 保留每页滚动位置、`BackHandler` 处理子页返回、对话框统一挂壳层。**[已核实]**
- 皮肤默认值按系统判断（MIUI/HyperOS → Miuix），用户选过就尊重存储值。**[已核实]**

### 3.3 配置文件：`/sdcard/MG/config.json`（wire 值 + 容错 + 未知键保留）

这是该仓库最有参考价值的部分，三层：

1. **`settings/MGConfig.kt`**：不可变 `data class MGConfig`，字段默认值只此一份；枚举实现 `WireValue`（磁盘整数**永不改**，新增档位只追加）；`GlVersion.fromWire` 刻意复刻 native 的**夹取**规则（47→4.6、38→3.3、31→3.2），避免"App 保存一次就把用户没动过的值改掉"。**[已核实]**
2. **`settings/MGConfigCodec.kt`**：键名唯一定义处；`decode` 永不抛异常（单字段坏 → 回落默认，只有 JSON 语法错才算文件损坏）；`encode` 带 `foreignKeys`（native 认识但 App 不认识的键原样写回，例如 `hideMGEnvLevel`）；旧键（`multidrawMode` 等）保留在 KNOWN_KEYS 里以便下次保存时清掉。**[已核实]**
3. **`settings/MGConfigStore.kt`**：全 App 唯一写盘入口；`config==null` = 未加载（UI 回调失效）；`update()` 只改内存并发状态，**300ms debounce** 后异步落盘；`dirty` 标志避免无改动写盘；`Mutex` 串行化；`NonCancellable + IO` 写；**原子写**（临时文件+fsync+rename，Direct storage 实现）；JSON 语法损坏 → 先备份 `config.json.corrupt-*` 再 `forget()`，由用户决定是否重置；写入失败通过 `events` 上报。**[已核实]**

配套的存储抽象 `settings/MgStorage.kt`：`DirectMgStorage`（`MANAGE_EXTERNAL_STORAGE` 或 Android 10- 的 READ/WRITE）与 `SafMgStorage`（SAF tree URI，`takePersistableUriPermission`），两种模式写同一个 `/sdcard/MG`；native 永远读真实路径。App 自己的偏好放 `PluginConfigStore`（SharedPreferences：皮肤、授权方式、SAF URI、隐私同意、ANGLE 来源包名、赞助计数）。**[已核实]**

### 3.4 渲染器注入模型（MobileGlues 2.0 的事实标准）

`AndroidManifest.xml`（main）：
- `<meta-data android:name="fclPlugin" android:value="true"/>` + `des`、`renderer`、`boatEnv`、`pojavEnv`、`minMCVer`/`maxMCVer` 占位符（由 Gradle `manifestPlaceholders` 填）。**[已核实]**
- `build.gradle.kts` 里 `renderer = "MobileGlues:libmobileglues.so:libmobileglues.so"`；`boatEnv/pojavEnv` 填 `LIBGL_ES=3`、`POJAV_RENDERER=opengles3`、`POJAVEXEC_EGL=libmobileglues.so`、`LIBGL_EGL=libmobileglues.so`、`MG_COUNT_LAUNCH=1`（注释：只有经启动器起来的才算一次启动）。**[已核实]**
- `<service android:name=".MgQueryService" android:process=":mgquery">`：**一次性进程**跑 `dlopen libmobileglues.so` + EGL context，查 GL 信息后进程退出。原因写在注释里：库的静态构造只读一次 `MG_ANGLE_DIR`/配置，而库离不开进程。**[已核实]**
- `MGInfoGetter.kt` → JNI `mobileglues_info_getter.cpp`：先 `setenv("MG_PLUGIN_STATUS","1")`、`setenv("MG_DIR_PATH", ...)`、`setenv("MG_ANGLE_DIR", ...)` 再 `dlopen("libmobileglues.so")`，然后 `eglGetDisplay/eglInitialize/eglChooseConfig/eglCreatePbufferSurface/eglCreateContext/eglMakeCurrent`，最后 `glGetStringi(GL_EXTENSIONS)` 输出扩展列表，并检查 `GL_MG_mobileglues`、`GL_MG_backend_string_getter_access`、`GL_MG_settings_string_dump` 三个自定义扩展判断能力。**[已核实]**

**这对 PanVK 的直接启示**：MobileGlues 之所以能注入，是因为**启动器认它的插件 meta-data 并在游戏进程里设置 env / 加载它的 .so**；它自己从不尝试"改别的 App 的环境变量"。PanVK 配置 App 必须复用同一条路（见 §4.2）。

### 3.5 可借鉴 vs 需差异化

| 维度 | MobileGlues 2.0 做法 | 我们的取舍 |
|---|---|---|
| 配置位置 | `/sdcard/MG/config.json`（共享存储） | **app 私有 `filesDir`**，零存储权限；导出/导入用 SAF |
| 配置编码 | Int wire + 未知键保留 + 容错 | **照抄**，另加 `schemaVersion` + 迁移 |
| 写盘 | 300ms debounce + 原子写 + 损坏备份 | **照抄**，另加"启动前置写"（崩溃回退需要同步写） |
| UI 架构 | AppController 单状态源 + 双皮肤 | **照抄**（先只做 Material3，皮肤留接口） |
| 驱动生命周期 | 无（只有一个内置 libmobileglues.so） | **新增**：导入/校验/多版本/启用/回滚/A-B |
| 自检 | 弱（GL info dump） | **新增**：Vulkan 能力矩阵 + 必选项判定 + 设备创建冒烟 |
| 失败恢复 | 无 crash-loop 机制 | **新增**：boot marker + watchdog + 安全模式 |
| 权限面 | 所有文件访问/SAF | **零权限**（filesDir），只在导入导出时用 SAF |

---

## 4. 配置 UI App 设计（PanVK Driver Manager for MobileGL）

### 4.1 目标与非目标

**目标**
- 管理 PanVK 驱动包（导入 zip/so、校验 ABI/sha256、解压安装、启用/禁用/回滚）。
- 生成/维护 Vulkan ICD JSON；在**同一进程**里于 `dlopen(libMobileGL)`/Vulkan 初始化前注入 `VK_DRIVER_FILES`（或 `VK_ICD_FILENAMES`）等 env。
- 作为启动器插件把渲染器 + env 交给 Minecraft 启动器（Zalith RendererPlugin-v2 DSL / FCL/Pojav meta-data）。
- 能力自检、扩展检查、日志、导入导出、About。
- 安全：启动自检、崩溃回退、安全模式、禁用开关。

**非目标**
- 不给任意的"别的 App"注入 env（见 4.2，做不到）。
- 不 root、不修改系统分区、不动 `/system`/`/vendor`、不依赖 Magisk。
- 不实现 Mesa/PanVK 编译（task-1/lead 负责），只消费其产物。

### 4.2 与渲染器进程的交接模型（为什么"给别的 App 设 env"不可行）

Android 沙盒下，一个普通 App **不能**：
- `setenv` 到另一个 App 的进程（env 是进程属性，只在 fork/exec 时继承）；
- `LD_PRELOAD`/注入（无 ptrace 权限、SELinux 阻止、无 root）；
- 修改系统的 `libvulkan.so` 或 AOSP loader 的 ICD 搜索路径（只读分区 + 非 root）；
- 通过 `am`/`pm` 给别的 App 传环境变量（没有这种接口）。

因此只有三种真实可行的交接：

**方案 A（推荐，单 APK 内嵌）：** MobileGL + 自捆绑 Khronos loader + PanVK ICD 全部打进同一个 APK。UI 和应用渲染在**同一进程**（或同一 APK 的 `:probe`/`:render` 子进程），由 JNI 在 `dlopen` 前 `setenv`。适用于：本项目自己的宿主/演示 App、以及"把 MobileGL 直接编进启动器"的分支。
- 关键：MobileGL 构建时 `-DMOBILEGL_VULKAN_LIBRARY=<bundled loader>`，让它的 DT_NEEDED 指向我们的 loader；`VK_DRIVER_FILES` 指向安装目录里的 ICD JSON。
- A/B 回退、自检、日志都在同一 APK 内闭环，最容易做对。

**方案 B（推荐，启动器插件，覆盖 Minecraft Java 生态）：** 把本 App 做成启动器插件（与 MobileGL 的 `android-plugin` 同构）：manifest 里声明 renderer + env 映射，启动器在它启动的游戏进程里设置；插件 APK 自带 `libMobileGL.so` + 自捆绑 loader + ICD。
- FCL/Pojav：`fclPlugin=true` + `renderer` + `boatEnv`/`pojavEnv`。
- Zalith Launcher 新版：`RendererPlugin-v2` DSL 的 `renderer(...)`/`env(...)`（MobileGL android-plugin 已用 AGP 8.6.0 + DSL 1.0-alpha6 跑通）。
- 此时 UI 的"Per-app"实际是"每个启动器实例/游戏配置"（Minecraft 版本隔离），不是任意第三方 App。

**方案 C（独立 Driver Manager App，能力受限）：** 只做"驱动仓库 + 安装 + 自检 + 导出配置"，通过 ContentProvider/Intent 把配置交给方案 A/B 的宿主；或者把驱动安装到二者共享的目录。它**无法**让任意第三方 App 用上 PanVK——除非那个 App 自己内嵌了我们的 loader。
- 如果产品必须叫"独立 App"，UI 可以独立，但**真正生效的注入点必须在宿主/插件里**，这点必须在 UI 里明确提示，避免误导。

> 结论：**UI 与注入点分离**——UI 进程负责"文件 + 配置 + 自检 + 回退状态"；注入点（同 APK 宿主或启动器插件）负责"在 fork/dlopen 前 setenv"。二者通过私有配置文件（同一 APK 内即直接读）或显式 Intent/ContentProvider 通信。

### 4.3 屏幕 / 导航

单 Activity + Navigation-Compose（或照抄 MobileGlues 的 `AppController` 单状态源；两者都行，推荐前者以减少自定义代码）：

```
Home / Dashboard        设备摘要、当前 driver、上次自检结论、健康度、快捷开关
Renderer                后端选择 DirectVulkan/DirectGLES、渲染器库路径、内置/外部 MobileGL
Driver Manager          已安装驱动列表（版本/ABI/sha256/来源/状态）、导入、启用、删除、A/B 槽位
Environment & Features  env 键值编辑（白名单）、MobileGL 功能开关、高级（VK_LOADER_DEBUG 等）
Per-app / Profiles      按启动器实例/包名覆盖（仅在方案 B/A 的宿主内有效，UI 明确标注）
Self-test               必选项 PASS/WARN/FAIL 矩阵、扩展列表、limits、创建 Device 冒烟、报告导出
Logs                    native 日志文件 tail、过滤、清空、分享
Import / Export         配置 JSON / 驱动包 zip / 自检报告；SAF 选择
About                   版本、license、第三方、设备信息、诊断信息复制
```

导航实现：`Home` 为根，`DriverManager/Environment/PerApp/SelfTest/Logs/ImportExport/About` 为二级路由；危险操作（删除驱动、切系统驱动、安全模式）走二次确认 + 结果 Snackbar。**沿用 MobileGlues 的"子页返回键优先、每页保存滚动位置"** 这两个细节。

### 4.4 配置 schema（`config/settings.json`，v1）

设计原则：**与 native/loader 共享的键尽量用字符串而非枚举序号**；未知键原样保留（照抄 MGConfigCodec）；`schemaVersion` 单调递增；每次保存前先迁移。

```jsonc
{
  "schemaVersion": 1,
  "renderer": {
    "backend": "DirectVulkan",              // DirectVulkan | DirectGLES
    "library": "libMobileGL.so",
    "hostMode": "embedded",                 // embedded | launcher-plugin | standalone
    "driverMode": "panvk",                  // panvk | system | disabled
    "activeDriverId": "panvk-25.3.0-abc12345",
    "previousDriverId": "system"            // A/B 回退目标
  },
  "env": {
    "VK_DRIVER_FILES": "",                  // 空 = 不设置；非空时由 NativeBridge 注入
    "VK_ICD_FILENAMES": "",                 // 兼容旧 loader；两者都设时 VK_DRIVER_FILES 优先
    "VK_LOADER_DEBUG": "",                  // 仅 debug 构建允许 error|warn
    "MOBILEGL_BACKEND_TYPE": "DirectVulkan",
    "MOBILEGL_DISABLE_TIMERQUERY": 0,
    "MOBILEGL_MAGMA_DISABLE_SUBGROUP": 0,
    "MOBILEGL_MAGMA_FRAMESINFLIGHT": 3,
    "MOBILEGL_LOG_FILE_PATH": ""
  },
  "safety": {
    "selfTestOnLaunch": true,
    "crashLoopThreshold": 3,
    "crashWindowMinutes": 10,
    "autoRollback": true,
    "safeMode": false
  },
  "logging": { "level": "info", "maxFiles": 5, "maxBytesPerFile": 4194304 },
  "profiles": [
    { "id": "default", "match": { "packageName": "*" }, "env": {}, "driverId": null }
  ]
}
```

`config/drivers.json`（注册表，UI 与 native 都可读）：

```jsonc
{
  "schemaVersion": 1,
  "drivers": [
    {
      "id": "panvk-25.3.0-abc12345",
      "displayName": "PanVK 25.3.0 (Mali-G925)",
      "version": "25.3.0",
      "abi": "arm64-v8a",
      "sha256": "…64 hex…",
      "sizeBytes": 12345678,
      "installedAt": "2026-10-31T05:00:00Z",
      "source": "zip",
      "library": "libvulkan_panvk.so",
      "icdJson": "panvk_icd.aarch64.json",
      "elf": { "class": 64, "data": "LSB", "machine": 183 },
      "apiVersion": "1.3.0",
      "isPortabilityDriver": false,
      "probe": { "ok": true, "at": "2026-10-31T05:01:00Z", "score": 0.92 }
    }
  ]
}
```

**默认值**：`driverMode=system`、`activeDriverId=null`、`safeMode=false`、`selfTestOnLaunch=true`、`crashLoopThreshold=3`、`VK_LOADER_DEBUG=""`。
**迁移规则**：读 `schemaVersion`；缺失视为 1 并补齐键；未知 `schemaVersion` > 当前 → 拒绝写入（只读展示 + 提示升级 App），避免降级写坏。**未知键（顶层与 env 内）必须原样保留**。

### 4.5 文件布局（`filesDir`，零权限）

```
/data/data/<pkg>/files/
  config/settings.json
  config/drivers.json
  drivers/panvk/<id>/libvulkan_panvk.so        # 0555，只读
  drivers/panvk/<id>/panvk_icd.aarch64.json    # 0555；library_path 指向上面的 .so
  drivers/panvk/<id>/manifest.json             # 版本/来源/sha256/ELF 头
  drivers/panvk/<id>/SHA256SUMS
  drivers/panvk/<id>/NOTICE.txt                # LGPL/MIT 等许可与来源
  drivers/active.json                          # {"id": "...", "generation": 7}
  drivers/previous.json                        # 回滚目标
  state/boot.json                              # {"attempts":1,"startedAt":...,"clean":false,"pid":...}
  state/safe_mode.json
  state/selftest-last.json
  logs/panvk.log  logs/panvk.log.1 ...         # 环形
  exports/
```

- 导入暂存放 `cacheDir/import-<uuid>/`（可被系统清理，失败不影响正式目录）。
- 安装策略：**先在同级 `<id>.tmp` 完整写入 + fsync + 校验，再 `rename` 成 `<id>`**；`active.json` 用临时文件 + rename 原子切换。
- ICD JSON 里的 `library_path` 建议写**绝对路径**（loader 文档：相对路径相对于 manifest 所在目录；裸文件名走库搜索路径）——绝对路径最可控，且 App 能校验它落在自己的 `filesDir` 内。**[已核实：Vulkan-Loader docs §Driver Manifest]**

ICD JSON 模板（字段来自 Khronos 文档 `docs/LoaderDriverInterface.md` 的 manifest 定义，**已核实**）：

```json
{
  "file_format_version": "1.0.1",
  "ICD": {
    "library_path": "/data/data/dev.mobilegl.panvk/files/drivers/panvk/<id>/libvulkan_panvk.so",
    "api_version": "1.3.0",
    "is_portability_driver": false
  }
}
```

> `is_portability_driver`：若 PanVK 被当成非 conformant portability 驱动，需要设为 true，并且 App 侧创建 instance 时要带 `VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR`（`VK_KHR_portability_enumeration`）。Mesa PanVK 在 Android 上属于哪种，**未验证**，做成配置项并给默认 false。

### 4.6 驱动导入 / 校验 / 安装流水线

```
SAF(ACTION_OPEN_DOCUMENT, application/zip|octet-stream)
  → 流式复制到 cacheDir/import-<uuid>/pack.zip（最大体积限制，建议 ≤512MiB）
  → ZipInputStream 逐项校验：拒绝绝对路径 / ".." / 符号链接 / 非白名单顶层目录
     （白名单：lib*.so、*.json、SHA256SUMS、NOTICE*、manifest.json）
  → 解压到 cacheDir/import-<uuid>/unpacked/
  → 找 ICD JSON（默认 panvk_icd*.json）+ 其 library_path 指向的 .so
  → ELF 校验：前 4 字节 0x7F 'E' 'L' 'F'；EI_CLASS=2(64)；EI_DATA=1(LSB)；
     e_machine(offset 0x12, LE u16)=183(0xB7, AARCH64)
  → sha256 校验（SHA256SUMS 或包内 manifest 指定；冲突则 fail）
  → 解析 ICD JSON：file_format_version / library_path / api_version / is_portability_driver
     路径规范化：library_path 必须是包内文件，resolve 后仍在本包目录（防穿越）
  → 分配 id = "panvk-<apiVersion>-<sha256[0..8]>"
  → 拷到 drivers/panvk/<id>.tmp/ → fsync → rename → chmod 0555（目录 0755）
  → 更新 drivers.json（保留未知键）→ 询问"设为当前驱动？"
  → 可选：立刻跑 Self-test（:probe 进程）
```

实现要点：
- **校验失败一律删除暂存并报错**，绝不半安装。
- `.so` 必须 `chmod 0555`；Android 14 对"可写位置 dlopen"的限制需实测（见 §7），**只读化是成本最低的兼容动作**。
- 驱动包可做"签名清单"（文件列表 + sha256 的 Ed25519 签名）便于分发防篡改；MVP 先做 sha256 + 用户确认。

### 4.7 环境变量注入时序（最容易做错的地方）

**同进程（方案 A）**
1. `Application.onCreate`：`BootGuard.markBootStart()`（同步写 `state/boot.json`，`fsync`）。
2. 读取 `settings.json`，算出 env（`VK_DRIVER_FILES` 指向当前驱动 ICD；`MOBILEGL_BACKEND_TYPE=DirectVulkan`）。
3. JNI `NativeBridge.exportEnv(map)`：C++ 里对每个键 `setenv(k, v, 1)`；**必须在第一次 `dlopen(libMobileGL.so)` / 第一次 Vulkan 调用之前**。MobileGL 的 `ConfigLoader::Init()` 把 `LIBGL_*/MOBILEGL_*` 读进私有 map，之后的 `setenv` 对 MobileGL 不生效；Khronos loader 在 `vkCreateInstance` 时才读 `VK_DRIVER_FILES`，窗口稍宽松，但统一"先 setenv 再 dlopen"最稳。
4. `System.loadLibrary("MobileGL")`（或 JNI `dlopen` 绝对路径）→ `MobileGL::Initialize` → `MG_Backend::Init` → `DirectVulkan` → `VulkanRenderer::Initialize` 创建 instance/device。
5. 成功创建 GL 上下文/首帧后 `BootGuard.markClean()`。

**启动器插件（方案 B）**
- 启动器进程 fork 出游戏进程时设置 env（FCL `boatEnv/pojavEnv`；Zalith DSL `env{}`）。MobileGL 侧的 loader 必须已是 APK 自带的 Khronos loader（DT_NEEDED），否则系统 loader 仍忽略 `VK_DRIVER_FILES`。
- 插件 UI 只写自己的 `settings.json`/`drivers.json`，并把 `VK_DRIVER_FILES` 作为 env 值交给启动器。**风险**：插件 APK 私有目录里的驱动，游戏进程未必读得到；方案 B 下驱动文件应打进 APK 的 `jniLibs`（系统解压到 nativeLibraryDir）或放启动器可读位置（**待实测**，见 R3）。

### 4.8 Self-test 设计

**执行位置**：独立 `:probe` 进程（`android:process=":probe"` 的 Service/Activity）。原因：坏的 ICD 会让 `vkCreateInstance`/`dlopen` 直接 SIGSEGV；独立进程崩溃不拖垮 UI，也方便计数。MobileGlues 的 `:mgquery`、MobileGL 的 `:bench` 都是同一模式。**[已核实这些先例]**

**步骤**（每步计时、可跳过；单步超时 5s，整体 30s）：
1. `setenv("VK_DRIVER_FILES", icdJsonAbsolutePath, 1)`，可选 `setenv("VK_LOADER_DEBUG","error",1)`；清 `VK_ICD_FILENAMES` 避免旧值覆盖。
2. `dlopen(loaderPath)`；`dlsym("vkGetInstanceProcAddr")`。
3. `vkEnumerateInstanceVersion`（可选）→ 选 `apiVersion`（1.3→1.2→1.1 回退，记录实际值）。
4. `vkCreateInstance`（可选 portability flag）→ 失败给出 `VkResult` 字符串。
5. `vkEnumeratePhysicalDevices` → ≥1 个；记录 `deviceName/vendorID/deviceID/apiVersion/driverVersion`（`vkGetPhysicalDeviceProperties`）。
6. `vkGetPhysicalDeviceFeatures` + `vkGetPhysicalDeviceFeatures2`（如支持）→ 必选项：
   - `fillModeNonSolid`（GL `glPolygonMode` 线框/点模式依赖；MobileGL 中具体使用点**未逐行核实**，见 R6）
   - `vertexPipelineStoresAndAtomics`（GL 4.2 SSBO/image 顶点阶段；MobileGL 用它决定 `GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS/IMAGE_UNIFORMS`，见 §2.2-6）
   - `fragmentStoresAndAtomics`
   - `geometryShader`、`tessellationShader`（GL 4.x；MobileGL 会广告/降级）
   - `shaderFloat64`、`shaderInt64`、`shaderClipDistance`、`shaderCullDistance`、`wideLines`、`imageCubeArray`、`shaderStorageImageWriteWithoutFormat`、`shaderStorageImageExtendedFormats`、`independentBlend`、`dualSrcBlend`、`depthClamp`
   - 三态：**PASS（全有）/ WARN（缺可选）/ FAIL（缺必选）**；WARN 文案说明"MobileGL 会把对应 GL 上限/扩展降为 0"。
7. `vkGetPhysicalDeviceLimits`：记录 `maxImageDimension2D/3D/Cube`、`maxFramebufferWidth/Height`、`maxVertexOutputComponents`、`maxFragmentOutputAttachments`、`maxColorAttachments`、`maxUniformBufferRange`、`maxStorageBufferRange` 等——与 MobileGL 的 clamp 逻辑对照（`BackendObject_DirectVulkan::UpdateDynamicBackendParameters`）。**不要硬编码"GL 4.2 最低值"清单**（需查 spec），UI 展示 MobileGL 实际采用值即可。
8. 设备扩展：`vkEnumerateDeviceExtensionProperties` → 必查 `VK_KHR_swapchain`（WSI；PanVK Android WSI 支持范围未验证）；建议查 `VK_KHR_maintenance1/2/3`、`VK_EXT_robustness2`、`VK_KHR_image_format_list`（按 MobileGL 实际请求核对）。
9. **冒烟**：用与 MobileGL 相同的 feature 集 `vkCreateDevice` + `vkGetDeviceQueue` + `vkDestroyDevice`；这是"能力表说有、实际建 device 失败"的唯一可靠检查。
10. 输出 JSON（`state/selftest-last.json` + 屏幕）：`ok`, `failures[]`, `warnings[]`, `device{}`, `apiVersion`, `driverVersion`, `features{}`, `limits{}`, `extensions[]`, `elapsedMs`。

**UI**：三色矩阵（照抄 MobileGL PostActivity 的 PASS/WARN/FAIL 配色与折叠表），顶部显示"当前驱动 + 实际加载的 loader/ICD 路径 + 是否 portability"。

### 4.9 崩溃循环检测 / 安全模式 / 双驱动 A-B

**BootGuard（Kotlin，App 进程启动最早执行）**
- 每次 `Application.onCreate`：读 `state/boot.json`；若 `clean==false` 且 `now - startedAt < crashWindowMinutes` → `attempts+1`；同步原子写回（临时文件 + fsync + rename + `FileDescriptor.sync()`）。
- 成功后（GL 上下文创建成功/首帧）`markClean()`：`clean=true, attempts=0`。
- `attempts >= crashLoopThreshold` → 写 `state/safe_mode.json`，`driverMode=system`（不改用户原配置，另存 `lastGood`），UI 启动即弹"上次启动连续失败，已回退系统驱动；原因见日志"。
- **watchdog**：`markClean` 之前启动 `Handler` 5s 超时兜底（或 `WorkManager` 一次性任务），超时仍未 clean → 标记 `clean=false` 并记录阶段（"load"/"vkCreateInstance"/"firstFrame"），供下次判断卡死而非崩溃。**不要用 native 自己写标记文件**——崩溃时写不进去。
- 额外：把 `Build.FINGERPRINT`、驱动 id、上次 selftest 结果写进 `boot.json`，方便定位。

**A/B 双驱动**
- `active.json` 与 `previous.json` 两个指针；切换驱动时先写 `previous` 再写 `active`（原子）。
- 回滚触发：启动失败 N 次、自检 FAIL、用户手动"回退"。
- 回滚动作：`active = previous`（或 `system`）→ 重新注入 env → 下次启动生效；UI 提供"固定此驱动（不再自动回退）"开关。
- 保留最近 2 个驱动版本 + 一个 `bundled` 槽位（APK 内 jniLibs 基线驱动，永远可回退）。

### 4.10 日志

- native 日志写文件（MobileGL 支持 `MOBILEGL_LOG_FILE_PATH`，**已核实注释**）；我们的 probe/render 也写同一目录。
- UI 只做 tail + 过滤 + 复制/分享；**不要尝试 `logcat` 读别的进程**（READ_LOGS 是签名权限，普通 App 只能读自己进程的 logcat）。
- 环形：每文件 4 MiB，保留 5 个；日志禁止写设备序列号/账号等敏感信息。

### 4.11 导入 / 导出

- 导出：`settings.json` + `drivers.json`（可剔除绝对路径）+ 最近自检报告 → 一个 zip（SAF `ACTION_CREATE_DOCUMENT`）。
- 导入：配置只允许 JSON，驱动包另走"导入驱动"。导入配置时**校验 schemaVersion + 只合并白名单键**，未知键保留但折叠展示；对 `activeDriverId` 做存在性校验，不存在则回退 system。
- 可选：导出驱动包（把 `drivers/panvk/<id>/` 重新打 zip），便于分享。

### 4.12 安全与隐私

- 全部文件在 `filesDir`（`MODE_PRIVATE`），不申请存储权限；导入/导出用 SAF 单次授权。
- 不申请 `QUERY_ALL_PACKAGES`；若需要发现启动器，用 `<queries><package .../></queries>`（照抄 MobileGlues-plugin 的白名单做法）。**[已核实先例]**
- 解压全程做路径规范化 + 体积上限（防 zip bomb：解压总量 ≤1 GiB、单文件 ≤512 MiB、条目数 ≤10k）。
- 不自动下载驱动；若将来加下载，必须显示来源 + sha256 + 签名校验。

---

## 5. 代码骨架（未编译，供 lead 参考/裁剪）

> 包名示例 `dev.mobilegl.panvk`；只保留关键文件，省略 theme/resources。**所有代码未编译、未在设备上运行。**

### 5.1 `MainActivity.kt`（Compose 导航 + 状态源）

```kotlin
package dev.mobilegl.panvk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.mobilegl.panvk.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PanVkApp
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val stateHolder = rememberSaveableStateHolder()
                val vm = app.controller
                NavHost(nav, startDestination = "home") {
                    composable("home") { stateHolder.SaveableStateProvider("home") { HomeScreen(vm) } }
                    composable("renderer") { RendererScreen(vm) }
                    composable("drivers") { DriverManagerScreen(vm) }
                    composable("env") { EnvironmentScreen(vm) }
                    composable("profiles") { ProfilesScreen(vm) }
                    composable("selftest") { SelfTestScreen(vm) }
                    composable("logs") { LogsScreen(vm) }
                    composable("transfer") { ImportExportScreen(vm) }
                    composable("about") { AboutScreen(vm) }
                }
            }
        }
    }
}
```

### 5.2 `DriverRepository.kt`（导入 / 校验 / 安装 / 启用 / 回滚）

```kotlin
package dev.mobilegl.panvk.driver

import android.content.Context
import android.net.Uri
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class DriverRepository(private val ctx: Context) {
    private val root = File(ctx.filesDir, "drivers")
    private val panvk = File(root, "panvk")

    data class Installed(
        val id: String, val dir: File, val icdJson: File, val library: File,
        val sha256: String, val apiVersion: String,
    )

    suspend fun importZip(uri: Uri): Result<Installed> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(ctx.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
            try {
                val zip = File(staging, "pack.zip")
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var total = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            total += n
                            require(total <= MAX_ARCHIVE_BYTES) { "archive too large" }
                            out.write(buf, 0, n)
                        }
                    }
                }
                val unpacked = File(staging, "unpacked").apply { mkdirs() }
                unzipSafely(zip, unpacked)

                val icd = unpacked.walkTopDown().firstOrNull {
                    it.isFile && it.name.matches(Regex("panvk_icd.*\\.json"))
                } ?: error("no panvk_icd*.json in package")
                val manifest = IcdManifest.parse(icd.readText())
                val lib = File(unpacked, manifest.libraryPath).canonicalFile
                require(lib.path.startsWith(unpacked.canonicalPath + File.separator)) { "library_path escapes package" }
                require(lib.isFile) { "library not found: ${manifest.libraryPath}" }
                checkElfArm64(lib)

                val sha = sha256(zip)
                val id = "panvk-${manifest.apiVersion}-${sha.take(8)}"
                val dst = File(panvk, id)
                if (!dst.exists()) {
                    val tmp = File(panvk, "$id.tmp").apply { mkdirs() }
                    unpacked.copyRecursively(tmp, overwrite = true)
                    Os.rename(tmp.absolutePath, dst.absolutePath)   // 原子发布
                }
                harden(dst)
                val installed = Installed(id, dst, File(dst, icd.name), File(dst, manifest.libraryPath), sha, manifest.apiVersion)
                DriversRegistry(ctx).upsert(installed)               // 保留未知键
                installed
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    fun activate(id: String) = DriversRegistry(ctx).setActive(id, alsoSetPrevious = true)
    fun rollback(): String? = DriversRegistry(ctx).rollback()

    private fun unzipSafely(zip: File, out: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var count = 0; var total = 0L
            while (true) {
                val e = zis.nextEntry ?: break
                if (++count > MAX_ENTRIES) error("too many entries")
                val name = e.name.replace('\\', '/')
                require(!name.startsWith("/") && !name.split('/').contains("..")) { "unsafe path: $name" }
                val f = File(out, name).canonicalFile
                require(f.path.startsWith(out.canonicalPath + File.separator)) { "zip slip: $name" }
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                f.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = zis.read(buf); if (n < 0) break
                        total += n; require(total <= MAX_UNPACKED_BYTES) { "unpacked too large" }
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun checkElfArm64(f: File) {
        val h = ByteArray(20); f.inputStream().use { it.readFully(h) }
        require(h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() &&
                h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()) { "not ELF" }
        require(h[4].toInt() == 2) { "not ELF64" }
        require(h[5].toInt() == 1) { "not little-endian" }
        val machine = (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
        require(machine == 183) { "e_machine=$machine, expected 183 (AArch64)" }
    }

    /** dlopen 只需可读/可执行，不需要可写；只读化也是应对 Android 14 限制的最便宜动作。 */
    private fun harden(dir: File) {
        dir.walkTopDown().forEach { f ->
            if (f.isDirectory) Os.chmod(f.absolutePath, 0b111_101_101)  // 0755
            else Os.chmod(f.absolutePath, 0b101_101_101)                 // 0555
        }
    }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").run {
        f.inputStream().use { input ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = input.read(b); if (n < 0) break; update(b, 0, n) }
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) { val n = read(b, off, b.size - off); if (n < 0) error("eof"); off += n }
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val MAX_UNPACKED_BYTES = 1024L * 1024 * 1024
        const val MAX_ENTRIES = 10_000
    }
}
```

### 5.3 `NativeBridge.kt` + `panvk_probe.cpp`（env 注入 + Vulkan 能力探测）

```kotlin
package dev.mobilegl.panvk.native

object NativeBridge {
    init { System.loadLibrary("panvk_probe") }

    /** 在 dlopen(libMobileGL) 之前调用；只允许白名单键。 */
    external fun exportEnv(keys: Array<String>, values: Array<String>): Int

    /** 在 :probe 进程里跑：setenv → dlopen(loader) → vkCreateInstance → 枚举设备/能力，返回 JSON。 */
    external fun probe(loaderPath: String, icdJsonPath: String, timeoutMs: Int): String
}
```

```cpp
// app/src/main/cpp/panvk_probe.cpp —— 骨架，未编译
#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <vulkan/vulkan.h>

namespace {
constexpr const char* kAllowedEnv[] = {
    "VK_DRIVER_FILES", "VK_ICD_FILENAMES", "VK_LOADER_DEBUG",
    "VK_LOADER_DRIVERS_SELECT", "MOVILEGL_DUMMY",
    "MOBILEGL_BACKEND_TYPE", "MOBILEGL_DISABLE_TIMERQUERY",
    "MOBILEGL_MAGMA_DISABLE_SUBGROUP", "MOBILEGL_MAGMA_FRAMESINFLIGHT",
    "MOBILEGL_LOG_FILE_PATH", "MG_DRIVER_ID",
};
bool allowed(const char* k) {
    for (auto* a : kAllowedEnv) if (std::strcmp(k, a) == 0) return true;
    return false;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_mobilegl_panvk_native_NativeBridge_exportEnv(
    JNIEnv* env, jobject, jobjectArray keys, jobjectArray values) {
    const jsize n = env->GetArrayLength(keys);
    if (n != env->GetArrayLength(values)) return -1;
    for (jsize i = 0; i < n; ++i) {
        auto jk = (jstring)env->GetObjectArrayElement(keys, i);
        auto jv = (jstring)env->GetObjectArrayElement(values, i);
        const char* k = env->GetStringUTFChars(jk, nullptr);
        const char* v = env->GetStringUTFChars(jv, nullptr);
        if (allowed(k)) setenv(k, v, 1);
        env->ReleaseStringUTFChars(jk, k);
        env->ReleaseStringUTFChars(jv, v);
        env->DeleteLocalRef(jk); env->DeleteLocalRef(jv);
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mobilegl_panvk_native_NativeBridge_probe(
    JNIEnv* env, jobject, jstring jLoader, jstring jIcd, jint /*timeoutMs*/) {
    const char* loader = env->GetStringUTFChars(jLoader, nullptr);
    const char* icd = env->GetStringUTFChars(jIcd, nullptr);

    // 1) 先 setenv，再 dlopen loader。
    unsetenv("VK_ICD_FILENAMES");
    setenv("VK_DRIVER_FILES", icd, 1);

    void* h = dlopen(loader, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        std::string e = "{\"ok\":false,\"stage\":\"dlopen\"}";
        env->ReleaseStringUTFChars(jLoader, loader);
        env->ReleaseStringUTFChars(jIcd, icd);
        return env->NewStringUTF(e.c_str());
    }

    auto gipa = (PFN_vkGetInstanceProcAddr)dlsym(h, "vkGetInstanceProcAddr");
    auto createInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    auto enumDevices = (PFN_vkEnumeratePhysicalDevices)gipa(nullptr, "vkEnumeratePhysicalDevices");

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "PanVK Probe";
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult r = createInstance(&ci, nullptr, &instance);
    if (r != VK_SUCCESS) {
        std::string e = "{\"ok\":false,\"stage\":\"vkCreateInstance\",\"vkResult\":" +
                        std::to_string((int)r) + "}";
        dlclose(h);
        env->ReleaseStringUTFChars(jLoader, loader);
        env->ReleaseStringUTFChars(jIcd, icd);
        return env->NewStringUTF(e.c_str());
    }

    uint32_t count = 0;
    enumDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devs(count);
    enumDevices(instance, &count, devs.data());

    VkPhysicalDeviceProperties props{};
    VkPhysicalDeviceFeatures feats{};
    auto getProps = (PFN_vkGetPhysicalDeviceProperties)gipa(instance, "vkGetPhysicalDeviceProperties");
    auto getFeats = (PFN_vkGetPhysicalDeviceFeatures)gipa(instance, "vkGetPhysicalDeviceFeatures");
    getProps(devs[0], &props);
    getFeats(devs[0], &feats);

    const bool required = feats.fillModeNonSolid && feats.vertexPipelineStoresAndAtomics;
    std::string out = "{\"ok\":true,\"device\":\"" + std::string(props.deviceName) + "\",";
    out += "\"apiVersion\":\"" + std::to_string(VK_VERSION_MAJOR(props.apiVersion)) + "." +
           std::to_string(VK_VERSION_MINOR(props.apiVersion)) + "." +
           std::to_string(VK_VERSION_PATCH(props.apiVersion)) + "\",";
    out += "\"required\":{\"fillModeNonSolid\":" + std::string(feats.fillModeNonSolid ? "true" : "false") +
           ",\"vertexPipelineStoresAndAtomics\":" + std::string(feats.vertexPipelineStoresAndAtomics ? "true" : "false") + "},";
    out += "\"requiredOk\":" + std::string(required ? "true" : "false");
    out += ",\"features\":{\"fragmentStoresAndAtomics\":" + std::string(feats.fragmentStoresAndAtomics ? "true" : "false");
    out += ",\"geometryShader\":" + std::string(feats.geometryShader ? "true" : "false");
    out += ",\"tessellationShader\":" + std::string(feats.tessellationShader ? "true" : "false");
    out += ",\"shaderFloat64\":" + std::string(feats.shaderFloat64 ? "true" : "false");
    out += ",\"imageCubeArray\":" + std::string(feats.imageCubeArray ? "true" : "false") + "}}";

    // TODO(正式实现)：vkCreateDevice 冒烟、Features2、device extension、超时线程、全程 JSON 转义。
    auto destroy = (PFN_vkDestroyInstance)gipa(instance, "vkDestroyInstance");
    if (destroy) destroy(instance, nullptr);
    dlclose(h);
    env->ReleaseStringUTFChars(jLoader, loader);
    env->ReleaseStringUTFChars(jIcd, icd);
    return env->NewStringUTF(out.c_str());
}
```

> 注意：示例里 `kAllowedEnv` 有一处笔误 `MOVILEGL_DUMMY`，正式实现应删除；保留它是为了提醒"白名单必须逐项 review，不能把整份环境变量透传"。

### 5.4 `SettingsStore.kt`（DataStore + JSON + 迁移）

```kotlin
package dev.mobilegl.panvk.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.ds by preferencesDataStore(name = "panvk_settings")

class SettingsStore(private val ctx: Context) {
    private val KEY = stringPreferencesKey("settings_json")

    val settings: Flow<Settings> = ctx.ds.data
        .map { SettingsCodec.decode(it[KEY]) }
        .catch { emit(Settings.Default) }

    suspend fun update(transform: (Settings) -> Settings) {
        ctx.ds.edit { prefs ->
            prefs[KEY] = SettingsCodec.encode(transform(SettingsCodec.decode(prefs[KEY])))
        }
    }
}

data class Settings(
    val schemaVersion: Int = 1,
    val backend: String = "DirectVulkan",
    val hostMode: String = "embedded",
    val driverMode: String = "system",
    val activeDriverId: String? = null,
    val previousDriverId: String? = null,
    val env: Map<String, String> = emptyMap(),
    val crashLoopThreshold: Int = 3,
    val safeMode: Boolean = false,
) { companion object { val Default = Settings() } }

object SettingsCodec {
    private const val CURRENT = 1
    private val KNOWN_ENV = setOf(
        "VK_DRIVER_FILES", "VK_ICD_FILENAMES", "VK_LOADER_DEBUG",
        "MOBILEGL_BACKEND_TYPE", "MOBILEGL_DISABLE_TIMERQUERY",
        "MOBILEGL_MAGMA_DISABLE_SUBGROUP", "MOBILEGL_MAGMA_FRAMESINFLIGHT",
        "MOBILEGL_LOG_FILE_PATH",
    )

    fun decode(raw: String?): Settings {
        if (raw.isNullOrBlank()) return Settings.Default
        return runCatching {
            val o = JSONObject(raw)
            var s = Settings.Default.copy(
                schemaVersion = o.optInt("schemaVersion", 1),
                backend = o.optString("backend", "DirectVulkan"),
                hostMode = o.optString("hostMode", "embedded"),
                driverMode = o.optString("driverMode", "system"),
                activeDriverId = o.optStringOrNull("activeDriverId"),
                previousDriverId = o.optStringOrNull("previousDriverId"),
                crashLoopThreshold = o.optInt("crashLoopThreshold", 3),
                safeMode = o.optBoolean("safeMode", false),
            )
            o.optJSONObject("env")?.let { env ->
                s = s.copy(env = buildMap {
                    for (k in env.keys()) if (k in KNOWN_ENV) put(k, env.optString(k, ""))
                })
            }
            // 迁移点：if (s.schemaVersion < CURRENT) { ...; s = s.copy(schemaVersion = CURRENT) }
            s
        }.getOrDefault(Settings.Default)
    }

    fun encode(s: Settings): String = JSONObject().apply {
        put("schemaVersion", CURRENT)
        put("backend", s.backend); put("hostMode", s.hostMode); put("driverMode", s.driverMode)
        s.activeDriverId?.let { put("activeDriverId", it) }
        s.previousDriverId?.let { put("previousDriverId", it) }
        put("env", JSONObject(s.env as Map<*, *>))
        put("crashLoopThreshold", s.crashLoopThreshold); put("safeMode", s.safeMode)
    }.toString()

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (has(k) && !isNull(k)) optString(k) else null
}
```

### 5.5 `SelfTestScreen.kt`（结果模型 + UI）

```kotlin
package dev.mobilegl.panvk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mobilegl.panvk.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Level { PASS, WARN, FAIL }
data class CheckRow(val name: String, val level: Level, val detail: String)

class SelfTestViewModel(private val ctl: AppController) {
    var rows by mutableStateOf<List<CheckRow>>(emptyList()); private set
    var running by mutableStateOf(false); private set
    var summary by mutableStateOf("尚未运行"); private set

    fun run(scope: CoroutineScope) = scope.launch {
        running = true; summary = "探测中…"
        val report = ctl.runProbe()          // 走 :probe 进程，见 NativeBridge.probe
        rows = report.rows
        summary = when {
            report.rows.any { it.level == Level.FAIL } -> "FAIL：缺少必选能力，MobileGL 会降级或无法工作"
            report.rows.any { it.level == Level.WARN } -> "WARN：可运行，但部分 GL 扩展/上限会被降到 0"
            else -> "PASS：必选能力齐全"
        }
        running = false
    }
}

@Composable
fun SelfTestScreen(vm: AppController) {
    val st = remember { SelfTestViewModel(vm) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("驱动自检", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(st.summary)
        Button(onClick = { st.run(scope) }, enabled = !st.running, modifier = Modifier.padding(vertical = 12.dp)) {
            Text(if (st.running) "运行中…" else "重新运行")
        }
        LazyColumn {
            items(st.rows) { row ->
                val color = when (row.level) {
                    Level.PASS -> Color(0xFF4CAF50)
                    Level.WARN -> Color(0xFFFF9800)
                    Level.FAIL -> Color(0xFFF44336)
                }
                ListItem(
                    headlineContent = { Text(row.name) },
                    supportingContent = { Text(row.detail) },
                    trailingContent = { Text(row.level.name, color = color) },
                )
            }
        }
    }
}
```

### 5.6 Gradle / Manifest / jniLibs 关键片段

```kotlin
// app/build.gradle.kts（片段）
android {
    namespace = "dev.mobilegl.panvk"
    compileSdk = 36
    ndkVersion = "27.3.13750724"           // 与 MobileGL/MobileGlues 一致
    defaultConfig {
        applicationId = "dev.mobilegl.panvk"
        minSdk = 29                        // 建议 ≥29；若必须兼容 26 需额外实测 SAF/私有目录行为
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging {
        jniLibs {
            useLegacyPackaging = true      // => android:extractNativeLibs="true"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose")        // 版本放进 version catalog
    implementation("androidx.datastore:datastore-preferences")      // 版本放进 version catalog
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

```xml
<!-- app/src/main/AndroidManifest.xml（片段） -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- 不需要存储权限：驱动与配置都在 filesDir；导入/导出走 SAF -->
    <queries>
        <package android:name="com.tungsten.fcl" />
        <package android:name="com.movtery.zalithlauncher.v2" />
    </queries>
    <application
        android:name=".PanVkApp"
        android:label="PanVK Manager"
        android:extractNativeLibs="true"
        android:allowBackup="false"
        android:hasFragileUserData="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <!-- 自检独立进程：坏驱动崩溃不拖垮 UI -->
        <service android:name=".probe.ProbeService" android:process=":probe" android:exported="false" />
        <!-- 若同时作为启动器插件（方案 B），保留 MobileGL android-plugin 的 meta-data：
             fclPlugin / fclPlugin_V2 / renderer / boatEnv / pojavEnv 由 Gradle DSL 生成 -->
    </application>
</manifest>
```

```
app/src/main/
  jniLibs/arm64-v8a/
    libpanvk_probe.so          # 我们的 JNI（CMake 产物）
    libvulkan_loader_khr.so    # 自捆绑 Khronos Vulkan-Loader（SONAME 供 MobileGL DT_NEEDED）
    libMobileGL.so             # MobileGL 产物（若内嵌；也可由 launcher 插件提供）
    libvulkan_panvk.so         # 可选：bundled 基线 PanVK 驱动（方案 B 的稳定回退槽）
  cpp/
    CMakeLists.txt
    panvk_probe.cpp
  assets/
    panvk_icd.aarch64.json     # 可选：bundled 驱动的 ICD（library_path 打包时生成）
```

> **打包注意**：`libvulkan_loader_khr.so` 必须与 MobileGL 链接时的 `MOBILEGL_VULKAN_LIBRARY` 完全一致（SONAME/ABI）；ICD JSON 的 `library_path` 若指向 `nativeLibraryDir`，必须在运行时用 `applicationInfo.nativeLibraryDir` 动态生成，**安装路径随机不能写死**。

---

## 6. 与 MobileGlues 2.0 的差异化总结

| 需求 | MobileGlues 2.0 | 本方案 |
|---|---|---|
| 目标 API | GL → GLES 3.2 | GL 4.2 → **Vulkan（PanVK）** |
| 驱动来源 | APK 内置 | **可导入第三方 PanVK 包**（版本化、可回滚） |
| 配置位置 | /sdcard/MG（共享存储） | filesDir（私有） |
| 权限 | MANAGE_EXTERNAL_STORAGE 或 SAF | **零存储权限** + SAF 按需 |
| 注入点 | 启动器插件 meta-data | 同左 + 自捆绑 loader + `VK_DRIVER_FILES` |
| 自检 | GL 信息 dump | **Vulkan 能力矩阵 + 必选项 + 建 Device 冒烟** |
| 失败恢复 | 无 | **boot marker / crash-loop / A-B / 安全模式** |
| 多配置 | 单份 config.json | 单份 settings.json + profiles |
| 日志 | /sdcard/MG/latest.log | filesDir/logs（环形）+ MobileGL 原生日志 |

---

## 7. 风险与未验证点（必须写进主指南）

| # | 风险 | 状态 | 建议 |
|---|---|---|---|
| R1 | Android 系统 loader 是否真的忽略 `VK_ICD_FILENAMES`/`VK_DRIVER_FILES` | **未独立复核**，以 task-2 源码证据为准 | 主指南引用 AOSP 文件+行号；本设计不依赖系统 loader |
| R2 | 自捆绑 Khronos loader 在 App 进程里加载 `filesDir` 下的 ICD .so：Android 10+/14 W^X、SELinux、linker namespace 是否允许 | **待实测** | 备选：ICD 也放 `jniLibs`（随 APK）；或 `android_dlopen_ext` + 只读 fd；驱动文件 `chmod 0555` |
| R3 | 方案 B（启动器插件）：游戏进程能否读到插件 APK 私有目录里的 ICD/驱动 | **待实测/设计未闭环** | 驱动打进 APK `jniLibs`（系统解压到 nativeLibraryDir），或让启动器复制到共享目录；"换驱动=重装 APK"的折中需产品决策 |
| R4 | PanVK 是否需要 `is_portability_driver=true` + `VK_KHR_portability_enumeration` | **未验证** | 做成配置项；probe 两种都试并记录 |
| R5 | MobileGL 的 `MOBILEGL_VULKAN_LIBRARY` 在 Android 上是否被 `find_package(Vulkan)` 分支抢先 | **未验证** | 构建时显式传变量并检查 `readelf -d libMobileGL.so` 的 NEEDED |
| R6 | `fillModeNonSolid` 在 MobileGL 代码中的具体使用点（polygonMode）未逐行核对 | **未验证** | `grep -rn "polygonMode\|fillModeNonSolid" MobileGL/MG_Backend/DirectVulkan` 核实后再写主指南 |
| R7 | MobileGL 26.8.0-dev 是移动靶（dev 分支持续更新），本文行为可能过期 | 已知 | 记录 commit SHA；构建时锁定 |
| R8 | `VK_LOADER_DEBUG` 在 release 可能泄露路径/设备信息 | 已知 | 仅 debug 开放；日志脱敏 |
| R9 | 独立 Driver Manager App 的用户预期：会以为"装了 App 就能让所有游戏用 PanVK" | 产品风险 | Home 页显著提示"仅对内置宿主/支持的启动器生效" |
| R10 | 本文件所有 Kotlin/C++ 代码**未编译**、未在 Mali-G925 上跑过 | 已知 | lead 集成时重写并跑 `./gradlew :app:assembleDebug` |

---

## 8. 证据 URL 清单

**MobileGL（dev）**
- https://github.com/MobileGL-Dev/MobileGL
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/Config.h
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/ConfigLoader.cpp
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/Init.cpp
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/MG_Backend/Init.cpp
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/MG_Util/BackendLoaders/Vulkan/Loader.h
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/MG_Util/BackendLoaders/Vulkan/Loader.cpp
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/MG_Backend/DirectVulkan/BackendObject_DirectVulkan.cpp
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/MG_Backend/DirectVulkan/Renderer/VulkanRenderer.cpp
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/MobileGL/MG_Backend/DirectVulkan/VulkanRendererConfig.h
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/CMakeLists.txt
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/android-plugin/app/build.gradle.kts
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/android-plugin/app/src/main/AndroidManifest.xml
- https://github.com/MobileGL-Dev/MobileGL/blob/dev/android-plugin/app/src/main/java/top/mobilegl/plugin/PostActivity.java
- https://github.com/MobileGL-Dev/MobileGL/tree/dev/MobileGL/MG_Util/SelfTest

**MobileGlues / MobileGlues 2.0**
- https://github.com/MobileGL-Dev/MobileGlues-plugin
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/build.gradle.kts
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/AndroidManifest.xml
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/java/com/fcl/plugin/mobileglues/settings/MGConfig.kt
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/java/com/fcl/plugin/mobileglues/settings/MGConfigCodec.kt
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/java/com/fcl/plugin/mobileglues/settings/MGConfigStore.kt
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/java/com/fcl/plugin/mobileglues/settings/MgStorage.kt
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/java/com/fcl/plugin/mobileglues/settings/PluginConfigStore.kt
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/java/com/fcl/plugin/mobileglues/ui/AppController.kt
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/java/com/fcl/plugin/mobileglues/ui/material/MaterialApp.kt
- https://github.com/MobileGL-Dev/MobileGlues-plugin/blob/main/app/src/main/cpp/mobileglues_info_getter.cpp
- https://github.com/MobileGL-Dev/MobileGlues-release

**Vulkan Loader 规范（Khronos）**
- https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderDriverInterface.md
  - L116-157：`VK_DRIVER_FILES` / `VK_ICD_FILENAMES`（后者 deprecated；两者同时存在时前者优先）
  - L708-750：Driver Manifest 字段 `file_format_version` / `library_path` / `api_version` / `is_portability_driver`
  - L202：Windows 高完整性/受保护进程下这些覆盖变量被忽略（提醒：Android 上同理不能指望"系统级"生效）

**AOSP / Android**
- 平台 libvulkan loader 的源码证据：**见 docs/research/android-loader.md（task-2）**；本文未独立复核其文件路径与行号。

---

## 9. 下一步（给 lead 的落地建议）

1. 锁定 MobileGL commit（dev HEAD），先做一件事：Android 构建传 `-DMOBILEGL_VULKAN_LIBRARY=<bundled loader>`，用 `readelf -d libMobileGL.so | grep NEEDED` 确认 loader 被换上；同时 `grep -rn "polygonMode\|fillModeNonSolid" MobileGL/MG_Backend/DirectVulkan` 核实 R6。
2. 以本文 §5 为骨架，在 `android-plugin` 上新增 Compose 配置页（替换 PostActivity 的手写 View），或新开 `config-ui/` 模块；先做 Driver Manager + Self-test 两屏跑通。
3. 把 `:probe` 进程的 `panvk_probe.cpp` 落到 `android-plugin/app/src/main/cpp`（或新模块），复用 MobileGL 的 `MG_Util/SelfTest` 报告 JSON 结构，避免两套自检。
4. 用 `adb shell dumpsys package <pkg>` / `readelf` / `adb logcat` 在 Mali-G925 上验证 R2/R3，把结论补进主指南。
