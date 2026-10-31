# MobileGL × PanVK 插件项目——目标清单与实施计划

> 版本：v0.1（2026-10-31）  
> 工作分支：`feat/panvk-android-plugin`（基于 `dev` @ `eb090c6`）  
> 许可证：新增代码 `LGPL-3.0-only`（与 MobileGL 一致），第三方许可见 `LICENSE-NOTICE.md`  
> 状态：计划已立项，未开始编码；调研并行进行中。

---

## 0. 一页结论（先看这里）

### 0.1 项目定义

本项目不是"再写一个 MobileGL"，而是在 **MobileGL 上游仓库的 `android-plugin` 模块**上，增加一条 **可选的开源 Vulkan 驱动路径（Mesa PanVK）**，并把插件 APK 升级为"配置 UI + 驱动管理 + 驱动自检"的一体化插件：

1. 构建/打包 **Mesa PanVK（arm64-v8a）** 与自带的 **Vulkan Loader/ICD 描述**；
2. 在目标进程（宿主启动器/游戏进程）里，于 `libMobileGL.so` 初始化 Vulkan 之前注入 `VK_ICD_FILENAMES` / `VK_DRIVER_FILES`，让 MobileGL 的 DirectVulkan（Magma）后端优先走 PanVK；
3. 复用并增强 MobileGL 已有的 **Driver POST 自检**（`PostActivity` + `DriverPost.cpp`），展示 PASS/WARN/FAIL 与扩展矩阵，并支持 "厂商驱动 ↔ PanVK" 对比；
4. 提供类似 **MobileGlues 2.0 / MobileGlues-plugin** 的配置 UI：驱动导入/切换/回退、环境变量、按应用配置、日志、自检报告导出；
5. 全流程 GitHub Actions 可复现构建，默认安全回退到厂商驱动。

### 0.2 已确认的现状（决定方案的硬事实）

| # | 事实 | 证据 | 对方案的影响 |
|---|---|---|---|
| F1 | "Action 构建的插件 APK"就是 MobileGL 仓库的 `android-plugin` 模块 | `.github/workflows/apk.yml`：`gradle -p android-plugin :app:assemblePluginRelease` | 直接在该模块上扩展，不另起 APK 工程 |
| F2 | 该 APK 是 **FCL 插件**（`fclPlugin`/`renderer`/`boatEnv`/`pojavEnv` meta-data），入口 `PostActivity` | `android-plugin/app/src/main/AndroidManifest.xml` | 配置 UI 必须遵循宿主插件的进程/生命周期模型 |
| F3 | **驱动自检已经存在**：`PostActivity` 调 JNI `nativeRunDriverPost()`，渲染 PASS/WARN/FAIL 表格 | `PostActivity.java`、`DriverPostJni.cpp`、`DriverPost.cpp` | "驱动自行检测"不需要重写，做增强 + 迁移 UI |
| F4 | MobileGL 的 Vulkan 加载是 **dlopen + vkGetInstanceProcAddr**，候选名 `libvulkan.so.1` → `libvulkan.so` | `DriverPost.cpp: OpenVulkanLoaderLibrary()`、`MG_Util/BackendLoaders/Vulkan/Loader.cpp` | 注入点明确；但要"优先自定义 loader"需要小改或换名/环境变量 |
| F5 | 自检里已有 `vertexPipelineStoresAndAtomics` 等特性项；`fillModeNonSolid` 需确认实际检查项与命名 | `DriverPost.cpp:1042` 附近 | P3 要补全扩展/特性清单并与 MobileGL 需求对齐 |
| F6 | MobileGL 仓库许可证为 **LGPL-3.0**，源码头统一 `SPDX-License-Identifier: LGPL-3.0-only` | `LICENSE`、源码头 | 新增代码沿用 LGPL-3.0-only；Mesa(MIT)、Vulkan-Loader(Apache-2.0) 单独声明 |
| F7 | 本机仓库已建分支 `feat/panvk-android-plugin` 并检出；Git 用便携 MinGit（本机未装 Git） | `.tools/mingit`、`git branch` | 后续提交都在该分支；推送需用户提供凭据 |

### 0.3 三个硬闸门（任何一项不过，方案要改道）

- **G1 内核闸门（最高风险）**：PanVK 走的是上游 `panthor`/`panfrost` 内核驱动（`/dev/dri/renderD*`），而天玑 stock 内核的 Mali 是 **kbase**（`/dev/mali0`）。**这两套内核 UMD 接口不兼容**。若设备没有 panthor/panfrost，"PanVK 在 stock 系统上跑起来"在工程上不成立，stubs 只能解决 Android 平台层（gralloc/log/property），解决不了内核接口。P0 必须先验证。
- **G2 加载器闸门**：Android 系统 `libvulkan.so`（AOSP 版 Khronos loader）是否接受 `VK_ICD_FILENAMES`/`VK_DRIVER_FILES` 需要源码级确认。若不接受，必须自带 Khronos Vulkan-Loader（唯一 SONAME）并在 MobileGL 里优先 dlopen 它。
- **G3 稳定性闸门**：PanVK 对 G925 是实验性支持，GPU hang/device lost/黑屏概率高。必须默认"厂商驱动可用、PanVK 可选"，并有崩溃回路自动禁用。

---

## 1. Definition of Done（项目完成的验收口径）

- [ ] **D1** 在具备 panthor/panfrost 的设备（或同一 SoC 的自编内核环境）上，Driver POST 能枚举到 **Mesa PanVK 设备**，且报告中的关键扩展（`fillModeNonSolid`、`vertexPipelineStoresAndAtomics` 等）相对厂商驱动有明确 PASS/FAIL 对比。
- [ ] **D2** MobileGL（DirectVulkan/Magma）在该环境下实际使用 PanVK 完成初始化，并能连续渲染 ≥10 分钟不崩（测试场景由宿主启动器提供）。
- [ ] **D3** 在 stock kbase 内核上，插件能**识别"内核不支持"并自动回退**到厂商驱动，不崩溃、不白屏、错误信息可读。
- [ ] **D4** 配置 UI 可完成：查看当前驱动/设备、导入并安装 PanVK 驱动包、切换/回退、查看自检报告、导出日志；配置持久化且可导入导出。
- [ ] **D5** GitHub Actions 一条流水线产出**已签名的插件 APK**（内置 PanVK 构建产物），带版本号/校验和/第三方许可证。
- [ ] **D6** 许可证与 NOTICE 完整；分支可合并回 `dev`（文档、脚本、模块化代码，不破坏原有厂商驱动路径）。

---

## 2. 工作分解（目标清单）

### P0 闸门验证与基线（0.5–1 天，阻塞后续）

- [ ] **P0-1 设备/内核探测脚本**：`adb shell` 采集
  - `ls -l /dev/dri /dev/mali0 2>&1`
  - `ls /sys/module | grep -Ei 'panthor|panfrost|mali'`、`cat /proc/version`
  - `getprop ro.board.platform`、`getprop ro.hardware.vulkan`
  - `dmesg | grep -Ei 'panthor|panfrost|mali'`（无权限则记录）
  - 结论写入 `docs/panvk/DEVICE-REPORT.md`。
- [ ] **P0-2 内核接口判定**：确认 `/dev/dri/renderD*` 是否存在、能否 `open()`；确认 kbase 版本与 SoC。**若 G1 不过：立即停止 PanVK 主线，转 Alt 路线（见 §4）并告知用户。**
- [ ] **P0-3 厂商驱动基线**：安装当前 Action 插件 APK，跑一次 Driver POST，保存 JSON（扩展/特性/限制）作为对照基线。
- [ ] **P0-4 调研收口**：汇总 Mesa PanVK 对 G925 的支持版本/实验性开关/stubs 方案（对应三份调研报告），形成 `docs/panvk/RESEARCH-SUMMARY.md`。
- [ ] **P0-5 工程准备**：分支、许可证、目录骨架（`docs/panvk`、`tools/panvk`、`android-plugin` 内的驱动管理模块）就位。

**验收**：`DEVICE-REPORT.md` 明确回答"本机是否有 panthor/panfrost"；若否，项目按 Alt 路线重新裁剪。

### P1 PanVK 构建流水线（2–4 天，依赖 P0-2 通过）

- [ ] **P1-1 版本选型**：确定 Mesa 基线（建议 25.1+ 中已验证 G925 的分支/标签，或 pin 一个 `mesa` commit）；记录理由与已知回归。
- [ ] **P1-2 交叉编译环境**：Linux/WSL2 + Android NDK（r27/r28）+ Meson/Ninja；先交叉编译 **libdrm**（panthor 头文件/库），再编 Mesa。
- [ ] **P1-3 Mesa 配置**：`-Dplatforms=android -Dvulkan-drivers=panfrost -Dgallium-drivers=panfrost`，关闭 X11/Wayland/GLX/测试；确认 Android WSI/gralloc 依赖（`libnativewindow`/`AHardwareBuffer`）。
- [ ] **P1-4 实验性开关**：运行时 `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1`；若源码未把 G925 列入可用设备，加最小 patch（设备 ID/quirks），单独放在 `patches/`。
- [ ] **P1-5 Stubs 方案**：评估并选用 `PanVK-Mali-Android-Stubs`（或等价 patch），解决 Android 平台层编译/运行依赖；记录其边界（不解决内核）。
- [ ] **P1-6 产物**：`libpanvk.so` + ICD JSON 模板 + 构建元数据（Mesa commit、NDK 版本、sha256）。
- [ ] **P1-7 本地验证**：`readelf -d` 检查依赖；用独立 JNI/CLI 探针 `vkEnumeratePhysicalDevices` 验证（设备端）。
- [ ] **P1-8 CI 任务**：`build-panvk.yml`（缓存 mesa 依赖、ccache、artifact 上传，附 LICENSE）。

**验收**：CI 可重复产出 `panvk-arm64-<commit>.zip`；在具备 panthor 的环境里枚举到设备。

### P2 Loader / ICD / 注入链路（2–3 天，可与 P1 并行设计）

- [ ] **P2-1 打包路径定案**：
  - 内置驱动：`jniLibs/arm64-v8a/libpanvk.so`（安装后位于 `nativeLibraryDir`）；
  - ICD JSON：随 APK assets 分发，首次运行生成到 `filesDir/drivers/<id>/icd.json`（JSON 内 `library_path` 填**绝对路径**）；
  - 导入驱动：`filesDir/drivers/...` 版本化目录（导入 zip），A/B 槽。
- [ ] **P2-2 Loader 策略**（依赖 G2 调研结论）：
  - 若系统 loader 支持 env：直接 `setenv` + 系统 `libvulkan.so`；
  - 否则：NDK 编译 Khronos Vulkan-Loader（唯一 SONAME，如 `libvulkan_panvk.so`），在 MobileGL 的 loader 打开逻辑里**优先尝试**；
  - 两条路径都保留，配置项可切换。
- [ ] **P2-3 JNI `driver_bridge`**：在 `System.loadLibrary("MobileGL")` 之前设置
  `VK_ICD_FILENAMES`、`VK_DRIVER_FILES`、`VK_LOADER_DEBUG`、`PAN_I_WANT_A_BROKEN_VULKAN_DRIVER`，
  再 dlopen loader → `vkGetInstanceProcAddr` → 探针 → 失败回退；thread-safe、幂等。
- [ ] **P2-4 MobileGL 侧最小改动**：支持 `MOBILEGL_VULKAN_LOADER`（或内置搜索名）指向自带 loader；不改动厂商默认路径的行为。
- [ ] **P2-5 自检联动**：Driver POST 增加"驱动来源/loader 路径/ICD 路径"字段，能区分系统驱动 vs PanVK。

**验收**：自检报告出现 `Mesa PanVK` 设备与正确 API 版本；切换开关后能回到厂商驱动。

### P3 驱动自检子系统增强（1–2 天，复用现有 Driver POST）

- [ ] **P3-1 清单对齐**：把 MobileGL 实际依赖的 Vulkan 扩展/特性（含 `fillModeNonSolid`、`vertexPipelineStoresAndAtomics`、descriptor indexing、timeline semaphore、dynamic rendering 等）与 `DriverPost.cpp` 检查项逐条对齐，补缺。
- [ ] **P3-2 对比模式**：同一设备上"厂商驱动 vs PanVK"两份报告 diff，高亮 PASS→FAIL、FAIL→PASS。
- [ ] **P3-3 报告模型**：JSON schema（device/driver/apiVersion/extensions/features/limits/verdict），支持导出/分享/复制。
- [ ] **P3-4 UI 迁移**：TextView 表格迁移到 Compose（可折叠分组、颜色状态、搜索/过滤）；保留极简模式（无 Compose 依赖时可用）。
- [ ] **P3-5 启动自检策略**：可配置"启动时自动跑一次"，结果缓存（按驱动 hash + 配置 hash）。

**验收**：自检页能显示两台"设备"（厂商/PanVK，环境切换后）并导出 JSON。

### P4 配置 UI（MobileGlues 2.0 风格，3–5 天）

- [ ] **P4-1 信息架构**：首页（当前渲染器/驱动/状态卡）、驱动管理、渲染器设置、自检、日志、关于/导出；Material 3（可选 Miuix 主题）。
- [ ] **P4-2 配置存储**：JSON + schemaVersion + 迁移（参考 `MGConfigCodec/MGConfigStore`）；导入/导出；敏感路径脱敏。
- [ ] **P4-3 驱动管理器**：SAF 导入 zip；校验（ELF aarch64、sha256、路径穿越、大小上限）；安装/激活/停用/卸载/回退；显示版本、来源、Mesa commit。
- [ ] **P4-4 进程/宿主模型**：明确"设置 Activity（可独立启动）+ 注入 Activity（宿主拉起、无界面）"双入口；与 FCL 的 `renderer`/环境 meta-data 兼容。
- [ ] **P4-5 环境变量面板**：只读展示实际生效值（loader/ICD/PAN_*），高级用户可编辑并做合法性校验。
- [ ] **P4-6 日志/诊断**：logcat 过滤、导出、崩溃摘要；i18n（中/英）、深色模式。

**验收**：无 adb 情况下完成"导入 PanVK 包→激活→自检→回退"全流程。

### P5 安全回退与稳定性（2 天，贯穿 P2–P4）

- [ ] **P5-1 崩溃回路检测**：启动标记 + 连续失败计数（阈值 2–3 次）→ 自动禁用 PanVK 并提示；日志保留。
- [ ] **P5-2 Device Lost/Hang**：心跳 + 超时；`VK_ERROR_DEVICE_LOST` 时优雅销毁、回退；禁止在 UI 线程等待。
- [ ] **P5-3 安全模式**：配置开关/快捷入口"禁用 PanVK"，供白屏时自救；自检失败一键回退。
- [ ] **P5-4 沙盒错误处理**：dlopen/命名空间/SELinux 失败的可读错误码与建议（不静默）。
- [ ] **P5-5 影响面说明**：插件只影响目标进程，**不替换 `/vendor`、不影响 SurfaceFlinger**；明确"全系统生效"必须 root/Magisk 的边界。

**验收**：人为制造 PanVK 初始化失败/崩溃后，第二次启动自动回到厂商驱动。

### P6 CI/CD 与发布（1–2 天）

- [ ] **P6-1 `build-panvk.yml`**：Mesa 构建 + 产物校验 + artifact。
- [ ] **P6-2 `apk.yml` 扩展**：下载 PanVK artifact → 放入 `android-plugin/app/src/main/jniLibs/arm64-v8a` → `assemblePluginRelease` → 签名 → 上传。
- [ ] **P6-3 可复现性**：pin 所有版本（Mesa commit、NDK、Gradle、依赖），产物附 `build-info.json` 与 sha256。
- [ ] **P6-4 发布物**：APK + 源码 tag + NOTICE + 更新日志 + 已知问题。

**验收**：从干净 fork 触发一次 workflow，产出可安装 APK。

### P7 真机验收与文档（2 天）

- [ ] **P7-1** 真机矩阵：支持 panthor 的环境（若有）与 stock kbase 环境各跑 D1–D3。
- [ ] **P7-2** 性能/温度采样（至少记录 fps 与温升，不做承诺性优化）。
- [ ] **P7-3** 文档：README（用户向）、集成指南（开发者向）、FAQ、许可证页。
- [ ] **P7-4** 代码评审与合并 `dev`（保持厂商路径默认不变）。

---

## 3. 里程碑与排期（参考，按 1 人全职估算）

| 里程碑 | 内容 | 工期 | 依赖 |
|---|---|---|---|
| M0 | P0 闸门 + 基线 + 工程准备 | 0.5–1 天 | — |
| M1 | P1 PanVK 构建流水线 | 2–4 天 | M0（G1 通过） |
| M2 | P2 注入链路 + P3 自检增强 | 3–4 天 | M1（或并行设计） |
| M3 | P4 配置 UI + P5 回退 | 4–6 天 | M2 |
| M4 | P6 CI + P7 真机验收 | 2–3 天 | M3 |
| **合计** | | **约 2–3 周** | 不含内核/设备侧阻塞 |

> 若 G1 不过（stock kbase），PanVK 主线无法交付，M1–M3 需转为 Alt 路线（见 §4），工期与目标随之变化。

---

## 4. 关键决策与备选路线

### 4.1 已定决策

- **D-01 载体**：在 MobileGL 的 `android-plugin` 上扩展（F1–F3），不新建独立 APK 工程；配置 UI 与该插件同 APK。
- **D-02 自检**：复用 `DriverPost`/`PostActivity`，增强而非重写。
- **D-03 许可证**：新增代码 `LGPL-3.0-only`，与 MobileGL 头保持一致；Mesa(MIT)/Vulkan-Loader(Apache-2.0) 在 NOTICE 声明。
- **D-04 默认安全**：厂商驱动为默认路径；PanVK 必须显式启用，失败自动回退。
- **D-05 分支**：`feat/panvk-android-plugin`（已创建，基于 `dev`）。

### 4.2 备选路线（G1 不通过时）

- **Alt-A 厂商驱动增强**：留在 ARM 闭源驱动，靠 MobileGL 的特性降级/模拟绕过缺失扩展（fillModeNonSolid 可用 wireframe 模拟等）；零内核依赖，最快可交付。
- **Alt-B Magisk/KernelSU + panthor 模块**：需要 root/解锁 BL，超出"不 Root"约束，仅作为用户可选进阶。
- **Alt-C 自编内核（panthor 上游化）**：成本最高，需内核源码与设备树适配；只建议有内核经验的用户。
- **Alt-D 换设备验证**：用已有 panthor 的设备（如部分上游内核设备）先验证插件与自检链路，天玑设备等内核支持。

---

## 5. 风险登记（Top 7）

| ID | 风险 | 概率/影响 | 缓解 |
|---|---|---|---|
| R1 | stock 天玑内核无 panthor，PanVK 根本无法运行 | 高/致命 | P0-1 先验证；G1 不过则走 Alt 路线，不硬编 |
| R2 | Android 系统 loader 忽略 `VK_ICD_FILENAMES` | 中/高 | 自带 Khronos loader + 优先 dlopen；双路径开关 |
| R3 | PanVK 实验性，GPU hang/device lost | 高/高 | 默认关闭 + 崩溃回路自动回退 + 安全模式 |
| R4 | app 私有路径 dlopen/W^X/SELinux 限制 | 中/中 | 优先 nativeLibraryDir；导入驱动放 filesDir 并实测；错误可读 |
| R5 | 许可证/第三方声明不完整 | 低/中 | NOTICE + APK 内开源声明页；Mesa MIT、loader Apache-2.0 |
| R6 | Android 工具链/Compose 版本漂移 | 中/低 | pin 版本；CI 固定镜像；ccache |
| R7 | 宿主（FCL）插件模型限制（进程/入口/权限） | 中/中 | 双入口设计；与宿主约定 meta-data；CI 真机冒烟 |

---

## 6. 目录与文件规划（落地位置）

```
docs/panvk/
  PROJECT-PLAN.md          # 本文件
  LICENSE-NOTICE.md        # 许可证与第三方声明
  DEVICE-REPORT.md         # P0 设备/内核结论（待填）
  RESEARCH-SUMMARY.md      # 三份调研收口（待填）
  INTEGRATION-GUIDE.md     # 最终实施指南（后续）
tools/panvk/
  build-panvk-android.sh   # Mesa 交叉编译脚本（后续）
  verify-device.sh         # adb 闸门检查（后续）
  icd/panvk_icd.json.in    # ICD 模板（后续）
patches/panvk/             # Mesa 最小补丁（后续）
android-plugin/
  app/src/main/java/top/mobilegl/plugin/driver/   # 驱动管理/注入（后续）
  app/src/main/cpp/panvk/                          # JNI bridge（后续）
.github/workflows/
  build-panvk.yml          # 新增（后续）
  apk.yml                  # 扩展（后续）
```

---

## 7. 本轮已完成 / 下一步

### 已完成
- [x] 建立工作分支 `feat/panvk-android-plugin`（本地，未推送）。
- [x] 确认 Action 插件 APK 的构建方式与"驱动自检"实现位置（F1–F3）。
- [x] 许可证方案与第三方声明草案。
- [x] 本计划文档。

### 下一步（按顺序）
1. 执行 P0-1/P0-2：用户在自己的设备上运行探测命令，回传结果；决定 G1。
2. 收口三份调研（Mesa/loader/UI），落 `RESEARCH-SUMMARY.md`。
3. G1 通过则从 P1-2 开始搭交叉编译环境；不通过则与用户确认 Alt 路线。
4. 同步把 `android-plugin` 的构建与签名流程跑通（不改功能）。

---

## 8. 需要用户提供/确认的信息

- [ ] 设备具体型号、SoC、内核版本、是否解锁 BL/root（决定 Alt-B/C 可行性）。
- [ ] 宿主启动器（FCL？Pojav？）及版本，插件加载方式。
- [ ] 是否有 Linux/WSL2 构建机；GitHub Actions 是否可用（私有 fork 的 Actions 权限）。
- [ ] 签名密钥由谁管理（沿用上游 secrets 还是自建）。
- [ ] 若 G1 不通过，是否接受 Alt-A（厂商驱动增强）作为主线。

## 9. 方向调整（2026-10-31 用户指示）

- 用户设备已 **root**，可用 **KernelSU 模块**；主要场景是 **Minecraft Java（Pojav 系启动器）**。
- **优先做插件 APK**（配置 UI + 驱动自检 + 驱动管理，形态对齐 MobileGlues 2.0），PanVK/开源驱动暂时后置。
- 插件 MVP 已落库，见 [PLUGIN-MVP.md](../plugin/PLUGIN-MVP.md)；本文件中 P1–P3 的 PanVK 工作转为 P8 阶段，待插件稳定后重启。
- 关键前置结论（调研已收口）：
  - 系统 Android Vulkan loader 不读 `VK_ICD_FILENAMES/VK_DRIVER_FILES`，自捆绑 Khronos loader 必须 patch Android 构建分支；
  - 上游 PanVK 只支持 panthor/panfrost，stock 天玑需 kbase 补丁栈；换驱动不保证获得缺失扩展。
- 驱动注入在插件阶段只做"驱动包管理 + ICD 物化 + 配置持久化"，不改变渲染路径。
