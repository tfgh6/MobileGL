# Mesa PanVK × Mali-G925 × stock Android：源码级调研

- 归属：Team Lead 的 `task-1`（研究成员 `mesa-panvk`）
- 日期：2026-10-31（Asia/Shanghai）
- 一手来源：Mesa GitLab（`main`、`mesa-25.1.0`、`mesa-26.2.6` 的 raw 文件 + project API）、Linux upstream（torvalds/linux）、AOSP `kernel_common` GKI 配置、GitHub API（社区项目）
- Mesa `main` 版本：**27.0.0-devel**（https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/VERSION）
- 标注约定：**【已验证】**=直接读到源码/产物；**【未验证】**=推断、或仅有第三方声明；**【需基线核验】**=依赖其他调研（task-2）

---

## 0. TL;DR（施工队结论）

| # | 问题 | 结论 | 依据 |
|---|------|------|------|
| 1 | 2026-10-31 时点 Mesa 版本 | 稳定 **26.2.6**（2026-10-28）；**26.3.0-rc3**（2026-10-30，正式版未发）；`main` = 27.0.0-devel | GitLab tags API |
| 2 | PanVK 何时支持 G925 | **Mesa 25.1.0（2025-05-07）起**（arch v13、GPU ID `0xd830`/variant 4），当时设备名报成 `Mali-G725`；**26.3.0 起**报 `Mali-G925-Immortalis` | `panvk: Add v13 support` 7a8d0b78（2025-04-15）、`panfrost: Advertize Mali-G725 support` fb0a8488（2025-04-15）、`panfrost: correctly report immortalis` 7c648edb（2026-10-14） |
| 3 | 是否要 `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER` | **v13（G925）不需要**：main/26.2/25.1 的 gating 都放行 `case 13`。需要它的是 v6/v7/v11/v14 | `src/panfrost/vulkan/panvk_physical_device.c:454-477`（main）、`:462-484`（26.2.6）、`:1096-1117`（25.1.0） |
| 4 | PanVK 用什么内核接口 | **只认 DRM：panthor（CSF，v10–v14）或 panfrost（JM，v6/v7）**，经 libdrm 枚举 `/dev/dri/renderD*`；**没有任何 mali_kbase / `/dev/mali0` 代码路径** | `pan_kmod.c:31-50` 后端表；`panvk_physical_device.c:79`；`vk_instance.c:417` |
| 5 | stock 天玑内核（kbase）能否直接跑上游 PanVK | **不能**。panthor 在 Linux 6.10 才进主线；AOSP GKI 配置里没有 `CONFIG_DRM_PANTHOR`。stock 上 `vkEnumeratePhysicalDevices` 返回 0 | 见 §4 |
| 6 | Mesa 官方是否支持 NDK+Meson 构建 panvk | **支持**，`docs/android.rst` 给了 cross file 与 panvk 专用 host-tools 步骤；`-Dplatforms=android` 时编入 `panvk_android.c`（AHB/ANB） | `docs/android.rst`；`src/panfrost/vulkan/meson.build:202-204` |
| 7 | 「PanVK-Mali-Android-Stubs」是什么 | 只有 README + 预编译 zip 的**二进制分发页**；"source code" zip 实为上游 Mesa `src/panfrost` 快照（537 文件，无任何 kbase/stub 代码）。其"绕过 /dev/dri"机制**无法从代码证实** | 见 §6.2 |
| 8 | 真正可落地的 stock 路线 | 社区 **kbase 补丁栈**（`zenithblue-oss/panvk-kbase-android`），已在 **Immortalis-G925 MC12（MT6991）** 上验证 37/37；前置条件是 **app UID 能 open `/dev/mali0`** | 见 §6.1 |

---

## 1. Mesa 版本时间线（含日期，供选基线）

| 版本 | 发布日期 | 与 PanVK/G925 相关 |
|------|----------|--------------------|
| 25.1.0 | **2025-05-07** | 首个含 **PanVK v13**（G925 硬件）与 G725 model row 的 release |
| 25.2.0 | 2025-08-06 | — |
| 25.3.0 | 2025-11-14 | 含 PanVK Android ANB/AHB 系列提交（2025-08-22 那批） |
| 26.0.0 | 2026-02-11 | — |
| 26.1.0 | 2026-05-06 | — |
| 26.2.0 | 2026-08-05 | 含 `panvk: Build for v14`（0350d2d0，2026-05-13） |
| **26.2.6** | **2026-10-28** | **当前最新稳定版** |
| 26.3.0-rc3 | 2026-10-30 | 含 `panvk: add v11 support`（c66177bd）、G925 命名修正（7c648edb）；26.3 branchpoint = 2026-10-15 |
| 27.0.0-devel | — | `main` 当前 |

> 选基线建议：**要 G925 且要稳定 → 26.2.6**；**要 `Mali-G925-Immortalis` 名字/最新 Android 修复 → 26.3.0（等正式版）或 pin 26.3 分支 commit**；更老的 25.1–25.2 也能跑 v13，但缺后续 Android/AHB 修复。

---

## 2. G925 支持溯源（源码级）

### 2.1 GPU ID 解码（`src/panfrost/model/pan_model.h`）【已验证】

```c
#define PAN_ARCH_MAJOR(x)     (((x) & BITFIELD_RANGE(28, 4)) >> 28)
#define PAN_ARCH_MINOR(x)     (((x) & BITFIELD_RANGE(24, 4)) >> 24)
#define PAN_PRODUCT_MAJOR(x)  (((x) & BITFIELD_RANGE(16, 4)) >> 16)
...
#define PAN_PROD_ID(arch_major, arch_minor, prod_major)
```

社区在 Galaxy Tab S11 Ultra（MT6991，Immortalis-G925 MC12）实测 `gpu_id = 0xd8300015`【第三方数据】：`arch = 13.8`、`product = 0xd830`、variant = 4 —— 与 G725 **同 ID**。

### 2.2 model 表：G925 就是"≥10 核 + 光追"的 G725

`src/panfrost/model/pan_model.c`（`main`，行 126-132）【已验证】：

```c
/** G925 is a G725 with ray tracing and sufficiently many cores. Keep this before G725 to prefer it when both match. */
FIFTHGEN_MODEL(PAN_PROD_ID(13, 8, 0), 4, "G925-Immortalis", "G725", ... .min_core_count = 10,
                                           .required_gpu_features = GPU_FEAT_RAY_INTERSECTION),
FIFTHGEN_MODEL(PAN_PROD_ID(13, 8, 0), 4,  "G725", ...),
```

- 该 G925 行由 **7c648edb36af2c692d454a01c4dbf338756805b1**（"panfrost: correctly report immortalis"，author 2026-10-09，commit 2026-10-14）引入；commit message 原文："The G925-Immortalis GPU is technically speaking a different name for the G725 when it has at least 10 cores and ray-intersection support. But we previously didn't recognize this, and just reported it as a G725 instead."【已验证：GitLab commits API】
- **包含该 commit 的最早 tag：`mesa-26.3.0-rc2` / `26.3-branchpoint`**（`/repository/commits/7c648edb/refs?type=tag`）→ 即 **26.3.0** 起才有这个显示名。
- 26.2.6 的 `pan_model.c` 只有 `FIFTHGEN_MODEL(PAN_PROD_ID(13, 8, 0), 4, "G725", ...)`，**没有 G925 行**（但硬件照样可用，只是显示为 Mali-G725）【已验证】。

### 2.3 硬件启用（v13）

- 提交 **7a8d0b78 "panvk: Add v13 support"**，committed **2025-04-15**；`refs?type=tag` 显示其已在 `mesa-25.1.0` 及其 rc 中 → **25.1.0 是首个支持 G925 的稳定版**。
- 同步提交 **fb0a8488 "panfrost: Advertize Mali-G725 support"**（2025-04-15）在 25.1.0 的 `src/panfrost/lib/pan_props.c` 加行：`MODEL(0xd830, 4, "G725", "TKRx", HAS_ANISO, 65536, 65536, {})`。
- 25.1.0 的 gating 已放行 v13（见 §3），即 **不需要任何环境变量**。

### 2.4 PanVK 对 G925 的 API 上限

- `panvk_EnumerateInstanceVersion` 返回 **Vulkan 1.4**（`panvk_instance.c:84-92`，`VK_MAKE_API_VERSION(0,1,4,VK_HEADER_VERSION)`）【已验证】。
- `docs/drivers/panfrost.rst` 表格：G720（v12）、G725（v13）、G1-*（v14）均为 Vulkan **1.4**；G615/G715（v11）为 1.4；其中 **v14 与 v6/v7/v11 在文档 note 中被列为 experimental**（需 `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1`）【已验证】。

---

## 3. 实验性 gating 的确切代码与语义

### 3.1 `main`（27.0.0-devel）`src/panfrost/vulkan/panvk_physical_device.c:454-477`【已验证】

```c
   switch (arch) {
   case 6:
   case 7:
   case 11:
   case 14:
      if (!os_get_option("PAN_I_WANT_A_BROKEN_VULKAN_DRIVER")) {
         result = panvk_errorf(instance, VK_ERROR_INCOMPATIBLE_DRIVER,
                               "WARNING: panvk is not well-tested on v%d, "
                               "pass PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1 "
                               "if you know what you're doing.", arch);
         goto fail;
      }
      break;

   case 10:
   case 12:
   case 13:
      break;

   default:
      result = panvk_errorf(instance, VK_ERROR_INCOMPATIBLE_DRIVER,
                            "%s not supported", device->model->name);
      goto fail;
   }
```

要点：
- **v13 走 `case 13: break;`，不需要 env**。
- `default:` 分支会拒绝任何未列出的 arch（例如未来 v15），错误文案为 `"<model name> not supported"`。
- 早于本 switch，`pan_get_model()` 已按 `gpu_id/gpu_variant/core_count/gpu_features` 查表；查不到会报 `"Unknown gpu_id (0x...) or variant (0x...)"`（`panvk_physical_device.c:439-452`）。

### 3.2 版本差异（同文件同逻辑）

| 基线 | 被 gate 的 arch | 行号 |
|------|-----------------|------|
| `main`（26.3+） | 6, 7, **11**, 14 | 454-477 |
| `mesa-26.2.6` | 6, 7, 14 | 462-484 |
| `mesa-25.1.0` | 6, 7 | 1096-1117 |

> 即 **v11（G615/G715）在 26.3 才开始要求 opt-in**（c66177bd "panvk: add v11 support"，2026-08-27）；26.2 及更早不 gate v11。G925 全程不受影响。

### 3.3 Android 上怎么"设环境变量"

- `os_get_option` 在 Android 上回退到系统属性：名字 lowercase、`_`→`.`、必要时加 `mesa.` 前缀，再按 `debug.` → `vendor.` → （API<33 才）裸名 查找（`src/util/os_misc.c:178-216`，`main`）【已验证】。
  例：`PANVK_DEBUG=startup` ⇒ `debug.mesa.panvk.debug=startup`。
- 因此 gated arch 在 Android 上可用 `adb shell setprop debug.mesa.pan.i.want.a.broken.vulkan.driver 1` 或 `wrap.sh` 注入 env。
- **风险（未验证）**：该属性名 `mesa.pan.i.want.a.broken.vulkan.driver` 长 38 字节，超过 bionic `PROP_NAME_MAX=32`；Mesa 只在 `ANDROID_API_LEVEL>=26` 时把自己的缓冲区放大到 128（`os_misc.c:148-156`），系统属性服务端仍按 32 限制。**对 v13 无所谓（本就不需要）**；若将来要跑 v11/v14，优先用 `wrap.sh` 设环境变量，别依赖 setprop。

---

## 4. 内核接口：panthor/panfrost vs stock Mali kbase

### 4.1 PanVK 只认两个 DRM 后端【已验证】

`src/panfrost/lib/kmod/pan_kmod.c:31-50`：

```c
static const struct backend {
   const char *name;
   const struct pan_kmod_ops *ops;
} backends[] = {
   { "panfrost", &panfrost_kmod_ops },   /* JM：v6/v7 */
   { "panthor",  &panthor_kmod_ops  },   /* CSF：v10-v14 */
   ...
};
```

- 设备来源：Vulkan runtime 用 libdrm 枚举 → `vk_instance.c:417 drmGetDevices2(...)` → `try_create_for_drm`（`panvk_instance.c:258-260`）。
- panvk 取 **render node**：`panvk_physical_device.c:79 const char *path = drm_device->nodes[DRM_NODE_RENDER];` → 即 **必须有 `/dev/dri/renderD*`**。
- panthor 后端要求 DRM driver name 匹配：`panthor_kmod.c:232 if (!pan_kmod_drm_drv_match(fd, "panthor", &drv_info)) return NULL;`【已验证】。
- **全树没有 kbase / `/dev/mali0` 代码路径**（`src/panfrost/lib/kmod/` 只有 `panfrost_kmod.c`、`panthor_kmod.c`、virtio）。Mali kbase 是另一套厂商 UAPI，PanVK 不认。

### 4.2 panthor 的 UAPI 依赖

`panthor_kmod.c` 使用的 ioctl（节选，均带版本门槛）【已验证】：

| ioctl / 查询 | 最低 drm driver 版本 | 代码 |
|---|---|---|
| `DRM_IOCTL_PANTHOR_DEV_QUERY`（GPU_INFO、CSIF_INFO） | 1.0（无条件） | :249、:261 |
| `DRM_PANTHOR_DEV_QUERY_TIMESTAMP_INFO` | 1.1 | :283 |
| `DRM_IOCTL_PANTHOR_SET_USER_MMIO_OFFSET` | 1.5 | :298-303 |
| `DRM_IOCTL_PANTHOR_BO_QUERY_INFO` | 1.7 | :489-494 |
| `DRM_PANTHOR_DEV_QUERY_MMU_INFO` | 1.9 | :267-274 |
| `BO_CREATE/BO_MMAP_OFFSET/BO_SYNC/VM_BIND/GROUP_*/SUBMIT` | 1.0+ | 全文 |
| 版本宏 | `DRM_PANTHOR_VERSION_MAJOR/MINOR`（`include/uapi/drm/panthor_drm.h:293-295`） | — |

即：**内核里的 panthor 必须存在**（无则枚举不到），且越新越好（1.9 才拿到 MMU_INFO；老内核走 4K/2M 兜底）。

### 4.3 panthor 的合并时间与固件

- `drivers/gpu/drm/panthor/panthor_drv.c` 在 **v6.10 存在、v6.9 不存在**（raw 文件 HTTP 200/404 验证）→ **panthor 于 Linux 6.10 进入主线**（2024-07）。
- 固件：`panthor_fw.c:31 #define CSF_FW_NAME "mali_csffw.bin"`；`panthor_fw.c:808-816` 拼路径 `arm/mali/arch%d.%d/mali_csffw.bin` → **G925（arch13.8）需要 `arm/mali/arch13.8/mali_csffw.bin`**；`MODULE_FIRMWARE` 列出 arch10.8/10.10/10.12/11.8/12.8/13.8/14.8【已验证】。
- 固件搜索路径在 Android 上来自内核 cmdline 的 `firmware_class.path=`（AOSP `drivers/base/firmware_loader/main.c:470-540`，`fw_path_para[]`）→ 设备相关，需实机 `cat /proc/cmdline` 确认【已验证 AOSP 代码，具体设备值未验证】。
- **DT/设备模型**：panthor 需要 GPU 的 platform device 绑定到 panthor（stock 上该硬件被 kbase 占用）；换驱动意味着 **unbind kbase + 提供 DT 节点**，不是"塞个 .ko 就行"。【推断，未在真机验证】

### 4.4 stock Android 现状（关键否定证据）

- **AOSP GKI 配置不含 panthor**：拉取 `aosp-mirror/kernel_common` 的 `android16-6.12` 与 `android15-6.6` 分支 `arch/arm64/configs/gki_defconfig`，grep `PANTHOR|MALI` **零命中**【已验证】。
- 结论：**GKI 基线内核不编 panthor**；厂商内核通常用自家 `mali_kbase`（`/dev/mali0`）。因此 **stock 天玑（D9400/D9500 类）上，上游 PanVK 必然 `vkEnumeratePhysicalDevices == 0`**，除非设备恰好带 `/dev/dri` render node 且内核有 panthor（普通零售机不会有）【已验证 + 设备侧需按 §8 gate 复核】。

---

## 5. Android platform / WSI 现状（Mesa 官方）

### 5.1 构建层

- 只有 `-Dplatforms=android` 时才会编 `panvk_android.c`（`src/panfrost/vulkan/meson.build:202-204`）【已验证】。
- `panvk_android.c` 提供：
  - `VK_ANDROID_native_buffer` 的 ANB 解析 + deferred image（`panvk_android.c:113-219`）；
  - `AHardwareBuffer` 导入/导出内存（`panvk_android.c:223-392`，`VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID`）；
  - gralloc layout 解析走 Mesa 公共 `u_gralloc`：`vk_android.c:57-70 _gralloc = u_gralloc_create(U_GRALLOC_TYPE_AUTO)`、`vk_android_get_ahb_layout()`【已验证】。
- 物理设备初始化时会探测 gralloc：`panvk_vX_physical_device.c:42 const bool has_gralloc = vk_android_get_ugralloc() != NULL;`（并据此决定 external memory 能力）【已验证】。

### 5.2 WSI 分工（重要，避免误判）

- `panvk_instance_extensions` 里**没有** `VK_KHR_android_surface`（`panvk_instance.c:94-133`，只有 display/wayland/xcb/xlib/headless）【已验证】。
- 这与 Android 的分工一致：**`VK_KHR_android_surface` / `VK_KHR_swapchain` 由系统 `libvulkan` loader 实现**，ICD 侧提供 `VK_ANDROID_native_buffer` + gralloc/AHB。【推断；须与 task-2（Android loader 调研）交叉验证】
- 结论：即使 panvk 跑起来，**present 路径仍受 loader/gralloc 约束**；这也解释了社区 kbase 项目为何要专门打 `wsi/` 与 `app-loader/` 补丁（见 §6）。

### 5.3 官方 NDK+Meson 构建（`docs/android.rst`，main）【已验证】

官方文档明确给出 Android NDK 交叉构建流程，并点名 panvk 的额外要求：

```sh
# 1) 先做 host 侧工具（panvk 需要 precomp compiler）
meson setup build-compiler \
    -Dprefix=/tmp/mesa-compiler -Dbuildtype=release -Dstrip=true \
    -Dplatforms= -Dgallium-drivers= -Dvulkan-drivers= \
    -Dmesa-clc=enabled -Dinstall-mesa-clc=true \
    -Dtools=panfrost -Dinstall-precomp-compiler=true
meson install -C build-compiler
export PATH=/tmp/mesa-compiler/bin:$PATH

# 2) 交叉构建（示例为 freedreno；panvk 把 vulkan-drivers 换 panfrost）
meson setup build-android-aarch64 --cross-file android-aarch64 \
    -Dplatforms=android -Dplatform-sdk-version=34 \
    -Dandroid-stub=true -Dandroid-libbacktrace=disabled \
    -Degl=disabled -Dgallium-drivers= -Dvulkan-drivers=panfrost
meson compile -C build-android-aarch64
```

关键依赖/选项（来自根 `meson.build`）【已验证】：
- **libdrm ≥ 2.4.109**（`meson.build:775`）；panvk 链接 `dep_libdrm`（`panvk/meson.build:186-200`）。DRM 驱动需要 `-Dallow-fallback-for=libdrm`（用 subproject wrap 或系统 libdrm）。
- `-Dandroid-stub=true` 会**跳过** `dependency('cutils'/'hardware'/'log'/'sync'/'nativewindow')`（`meson.build:1110-1137`）→ NDK-only 环境必需。
- `android-libbacktrace=disabled`、`android-libperfetto=disabled` 可关。
- `dep_zlib >= 1.2.3`（`meson.build:1859`，NDK sysroot 自带 libz）；`dep_elf` 为 optional（`meson.build:2147`）。
- 传 `-Dgallium-drivers=` 空，避免编 GL 驱动（省时间，也避免 LLVM 依赖）。

### 5.4 产物

- 共享库：`src/panfrost/vulkan/libvulkan_panfrost.so`（meson `shared_library('vulkan_panfrost', ...)`，`panvk/meson.build:206`）【已验证】。
- ICD JSON：meson 生成 `src/panfrost/vulkan/panfrost_icd.<arch>.json`，内容由 `vk_icd_gen.py` 以 `--api-version 1.4 --icd-lib-path <libdir> --icd-filename libvulkan_panfrost.so` 生成（`panvk/meson.build:257-272`）【已验证】。
- ICD 入口符号：`vk_icdGetInstanceProcAddr`（`panvk_instance.c:322-327`）+ loader 协商符号由 `vulkan_icd_link_args` 导出。

---

## 6. 社区项目调研（重点：stock 上唯一现实路线）

### 6.1 `zenithblue-oss/panvk-kbase-android`（★95，2026-09-19 创建，2026-10-31 仍更新）

- URL：https://github.com/zenithblue-oss/panvk-kbase-android
- 定位（README 原文）："A standalone patch and build layer around pinned upstream Mesa. It produces an open Mesa PanVK driver that talks directly to Android's proprietary `mali_kbase` kernel interface (`/dev/mali0`)."
- 基线：Mesa **26.3.0-devel**，pin 在 commit **`5a07217f034b3e50d8c7c7794f97a2df1742613b`**（`sources.lock`，pinned 2026-09-18）；当前 release 为 **0.1.0 RC4 / build 115**。
- 补丁结构（`patches/README.md`）【已验证】：`kbase-common/`（pan_kmod/Kbase 抽象，JM+CSF 共用）、`csf/ csf-v10..v14/`（CSF 队列/组/寄存器/bind/kick 生命周期 + 各 arch quirk）、`jm-v7/ jm-v9/`、`android/`（Bionic/NDK、WSI、gralloc/AHB）、`app-loader/`（通用 ICD 符号 + Android WSI 共存）、`wsi/`（swapchain/acquire/present）。
- 平台覆盖（README 表格）【第三方数据，未独立复现】：
  - **v13 = Immortalis-G925 MC12 已测**：Galaxy Tab S11 Ultra（SM-X930，MT6991），Android 16 / kernel 6.6.102 / kbase **CSF 1.30**，`gpu_id 0xd8300015`，PanProbe **37/37**（beta.18 RC1），D3D11 cube 48.1 FPS。
  - v12 G720 MC12 38/38；v10/v11（G610/G710/G615/G715）38/38；v9（G57）38/38；v7 实验（14/38）；v14 编译但未测。
- 构建方式（`meson/android-aarch64.ini`）【已验证】：`aarch64-linux-android35-clang`、`system='android'`、独立 host-tools（`scripts/bootstrap-host-tools.sh`），Bionic target 不与 glibc host 混。
- 关键前置（`docs/KBASE-PROFILES.md`）【已验证】：driver 必须能 **以 app UID 打开 `/dev/mali0`**；"A driver that only opens `/dev/mali0` as root is not app-loadable." → 这是全项目最硬的 gate。
- 已知限制：无 Vulkan 一致性声明；RC4 的"源码未提交/从设备恢复"；v13 在 RC3 未复测；需要 gralloc/AHB 与 loader 配合。
- 同作者周边：`zenithblue-oss/panplay`（Winlator 类启动器）、`panprobe`（Vulkan 能力/自检 APK）。

**判断**：这是"stock 天玑 + G925 + 不 root"目标下**唯一有真机证据**的路线；本质是给 panvk 增加第三个 kmod 后端（kbase），不做内核替换。

### 6.2 `martuniykmisha012-rgb/PanVK-Mali-Android-Stubs`（★28，2026-07-22）

- URL：https://github.com/martuniykmisha012-rgb/PanVK-Mali-Android-Stubs
- 仓库内容：**只有 `README.md` + 2 个 release 资产**（`Funny._Mesa26.2_PanVK.zip` 10.6 MB 预编译、`PanVK.source.code.zip` 1.79 MB），2 个 commit（Initial + README）。
- 实测解包 `PanVK.source.code.zip`【已验证】：**537 个文件，全部在 `panfrost/` 下，是上游 Mesa `src/panfrost` 的快照**（含 `lib/kmod/panfrost_kmod.c`、`panthor_kmod.c`、`ci/deqp-panfrost-g925-vk.toml` 等）；**没有任何 kbase/DRM stub/构建脚本**；全树提到 `kbase` 的只有两处上游注释。
- README 声称："custom UMD stubs… fool the emulator's initial graphics system checks… compensate for the missing `/dev/dri` system paths"，并通过 `PAN_USE_KRAID=1` 启用 KRAID。**这些说法无法从代码证实，且与 §4 的 Mesa 源码矛盾**（没有 DRM render node 就没有 panthor fd，`pan_kmod_dev_create` 直接失败）。`PAN_USE_KRAID` 环境变量在 Mesa `main` 的 panfrost 代码中也未证实存在【未验证】。
- 结论：**不要把该仓库当作技术依据或可信二进制来源**；它最多说明"有人分发了预编译 panvk"。其成果若真实，最可能来自 §6.1 那一系 kbase 补丁（时间线吻合：7 月出现，社区 kbase 项目 8–9 月爆发）。

### 6.3 其他相关项目（同生态，供交叉参考）【第三方，未逐一审计】

| 仓库 | ★ | 说明 |
|------|----|------|
| `wonderkast02/panvk-g720-kbase-csf` | 45 | G720 CSF / kbase，beta.2，声称 tessellation/GS/WAIT64 已验证；是 §6.1 的 CSF 参考来源之一 |
| `FristOneRR/FristOneRR-Panvk-Driver` | 89 | G57（v9 JM）kbase；附 Mesa 头文件来源 |
| `Noysz/panvk-g99-jm` | 20 | G57 MC2 / Helio G99，v9 JM |
| `JimVulkan/mali-panvk` | 35 | Mali PanVK 移植合集（fillMode 参考） |
| `nangitagamer777-art/PanVK-kbase`、`.../Panvk_Kmod` | — | Mesa 26.2 era 的 kbase 历史实现 |
| `LukeValen/panvk-mali-g52` | 30 | G52（v7 JM） |

> 生态结论：**2026 年 7–10 月，社区已把 "PanVK → kbase" 做成可运行补丁栈**，v9–v13 都有真机数据；这比"换 panthor 内核"现实得多。

---

## 7. 可落地的 NDK + Meson 构建方案

> 两条产线，选项 90% 相同：
> - **A. 上游 Mesa**（只在有 panthor/panfrost + `/dev/dri` 的设备上有意义；可用于本机验证 ICD/loader 流程）
> - **B. 社区 kbase 补丁栈**（stock 天玑的现实路线；构建脚本沿用其 `scripts/`，不要自己重写）

### 7.1 环境

- **构建宿主：Linux / WSL2 推荐**（Mesa host tools 是 ELF，Windows 原生需 MSYS2 且坑多；本项目 Windows 上请用 WSL2 Ubuntu 24.04）。
- **NDK**：r28+（`aarch64-linux-android34-clang` 或 35）。NDK 路径示例 `/opt/android-sdk/ndk/29.x`【版本号未验证】。
- Python3 + meson ≥ 1.4 + ninja + pkg-config；ccache 可选。
- **不要**用 `--wrap` 或 Windows 路径混入；cross file 内全部绝对路径。

### 7.2 cross file（`meson/android-aarch64.ini`）

```ini
[constants]
ndk = '/opt/android-sdk/ndk/29.0.14206865'
tc  = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'

[binaries]
c       = tc / 'aarch64-linux-android34-clang'
cpp     = [tc / 'aarch64-linux-android34-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables']
ar      = tc / 'llvm-ar'
strip   = tc / 'llvm-strip'
c_ld    = 'lld'
cpp_ld  = 'lld'
pkg-config = 'pkg-config'

[host_machine]
system     = 'android'
cpu_family = 'aarch64'
cpu        = 'armv8'
endian     = 'little'

[properties]
needs_exe_wrapper = true
```

### 7.3 依赖交叉编译顺序

1. **libdrm（≥2.4.109）** → `$PREFIX`
2. **Mesa host tools**（`mesa-clc` + `precomp-compiler`，本机 Linux 编，装到 `$HOSTPREFIX`）
3. **Mesa target**（`-Dvulkan-drivers=panfrost`、`-Dplatforms=android`）
4. **ICD JSON** + 打包进 APK（`jniLibs/arm64-v8a/`）+ JNI 注入（task-2/task-4）

```sh
# 1) libdrm：只留 core，禁掉不需要的模块
meson setup build-libdrm --cross-file meson/android-aarch64.ini \
  --prefix="$PREFIX" --buildtype=release \
  -Dtests=false -Dman-pages=disabled -Dudev=false -Dlibkms=false \
  -Dintel=disabled -Dradeon=disabled -Damdgpu=disabled -Dnouveau=disabled \
  -Dvmwgfx=disabled -Dfreedreno=disabled -Dvc4=disabled -Detnaviv=disabled
ninja -C build-libdrm install
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:$PKG_CONFIG_PATH"

# 2) host tools（本机编译器，不要用 cross file）
meson setup build-host --prefix="$HOSTPREFIX" -Dbuildtype=release -Dstrip=true \
  -Dplatforms= -Dgallium-drivers= -Dvulkan-drivers= \
  -Dmesa-clc=enabled -Dinstall-mesa-clc=true \
  -Dtools=panfrost -Dinstall-precomp-compiler=true
meson install -C build-host
export PATH="$HOSTPREFIX/bin:$PATH"

# 3) Mesa target
meson setup build-android --cross-file meson/android-aarch64.ini \
  --prefix="$PREFIX" --buildtype=release -Dstrip=true \
  -Dplatforms=android -Dplatform-sdk-version=34 \
  -Dandroid-stub=true -Dandroid-libbacktrace=disabled -Dandroid-libperfetto=disabled \
  -Degl=disabled -Dglx=disabled -Dgbm=disabled -Dgallium-drivers= \
  -Dvulkan-drivers=panfrost \
  -Dmesa-clc=system -Dprecomp-compiler=system \
  -Dallow-fallback-for=libdrm \
  -Dbuild-tests=false -Dvalgrind=disabled
ninja -C build-android
# 产物: build-android/src/panfrost/vulkan/libvulkan_panfrost.so
```

> 注意：`-Dtools=panfrost` 在交叉侧可能仍会尝试编 host 工具；文档给的方案是 host 装好后交叉侧只用 `system`（如上）。若 meson 报 precomp/mesa-clc 找不到，检查 `PATH` 与 `$HOSTPREFIX/bin`。
> Android 上 `-Dandroid-stub=true` 是"能编过 NDK"的关键；运行时依赖由 Bionic 提供（gralloc/AHB 经 `u_gralloc` 动态发现）【部分未验证：stub 与运行时的具体绑定】。

### 7.4 ICD JSON（APK 内）

```json
{
  "file_format_version": "1.0.1",
  "ICD": {
    "library_path": "/data/app/~~<hash>/<pkg>-<hash>/lib/arm64/libvulkan_panfrost.so",
    "api_version": "1.4.0"
  }
}
```

- `library_path` 必须是**运行时绝对路径**：`ApplicationInfo.nativeLibraryDir + "/libvulkan_panfrost.so"`（APK 的 `jniLibs` 会被解压到该目录）。
- 也可直接把 meson 生成的 `panfrost_icd.aarch64.json` 拿来改 `library_path`。
- `api_version` 建议与构建时 Vulkan header 一致（如 `1.4.313`）；写 `1.4.0` 一般也可被 loader 接受【AOSP loader 细节待 task-2 核验】。
- **SONAME**：`libvulkan_panfrost.so`；如果走 `/vendor` 替换路线才需要 `patchelf --set-soname vulkan.<name>.so`（Mesa 文档明确提过 SONAME 必须匹配），APK 内不需要。

### 7.5 bash 脚本草案（可直接落成 `tools/build-panvk-android.sh`）

```bash
#!/usr/bin/env bash
# 用法: MESA_DIR=... ANDROID_NDK_ROOT=... API=34 ./build-panvk-android.sh
set -eo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MESA_DIR=$MESA_DIR
[ -n "$MESA_DIR" ] || MESA_DIR=$ROOT/third_party/mesa
NDK=$ANDROID_NDK_ROOT
[ -n "$NDK" ] || NDK=/opt/android-sdk/ndk/29.0.14206865
API=$API
[ -n "$API" ] || API=34
PREFIX=$ROOT/build/prefix-android
HOSTPREFIX=$ROOT/build/prefix-host
BUILD=$ROOT/build

command -v meson >/dev/null || { echo "need meson"; exit 1; }
[ -d "$MESA_DIR" ] || { echo "Mesa source missing: $MESA_DIR"; exit 1; }
[ -d "$NDK" ] || { echo "NDK missing: $NDK"; exit 1; }

# 0) cross file
mkdir -p "$BUILD"
cat > "$BUILD/android-aarch64.ini" <<EOF
[constants]
ndk = '$NDK'
tc  = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
[binaries]
c = tc / 'aarch64-linux-android$API-clang'
cpp = [tc / 'aarch64-linux-android$API-clang++', '-fno-exceptions', '-fno-unwind-tables']
ar = tc / 'llvm-ar'
strip = tc / 'llvm-strip'
c_ld = 'lld'
cpp_ld = 'lld'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOF

# 1) libdrm
if [ ! -f "$PREFIX/lib/libdrm.so" ]; then
  meson setup "$BUILD/libdrm" "$ROOT/third_party/libdrm" \
    --cross-file "$BUILD/android-aarch64.ini" --prefix "$PREFIX" \
    --buildtype=release -Dtests=false -Dman-pages=disabled -Dudev=false -Dlibkms=false \
    -Dintel=disabled -Dradeon=disabled -Damdgpu=disabled -Dnouveau=disabled \
    -Dvmwgfx=disabled -Dfreedreno=disabled -Dvc4=disabled -Detnaviv=disabled
  ninja -C "$BUILD/libdrm" install
fi

# 2) host tools（仅首次）
if [ ! -x "$HOSTPREFIX/bin/mesa-precomp-compiler" ]; then
  meson setup "$BUILD/host" "$MESA_DIR" --prefix "$HOSTPREFIX" \
    -Dbuildtype=release -Dstrip=true -Dplatforms= -Dgallium-drivers= -Dvulkan-drivers= \
    -Dmesa-clc=enabled -Dinstall-mesa-clc=true \
    -Dtools=panfrost -Dinstall-precomp-compiler=true
  meson install -C "$BUILD/host"
fi
export PATH="$HOSTPREFIX/bin:$PATH"

# 3) Mesa target
meson setup "$BUILD/android" "$MESA_DIR" \
  --cross-file "$BUILD/android-aarch64.ini" --prefix "$PREFIX" \
  --buildtype=release -Dstrip=true \
  -Dplatforms=android -Dplatform-sdk-version="$API" \
  -Dandroid-stub=true -Dandroid-libbacktrace=disabled -Dandroid-libperfetto=disabled \
  -Degl=disabled -Dglx=disabled -Dgbm=disabled -Dgallium-drivers= \
  -Dvulkan-drivers=panfrost -Dmesa-clc=system -Dprecomp-compiler=system \
  -Dallow-fallback-for=libdrm -Dbuild-tests=false -Dvalgrind=disabled
ninja -C "$BUILD/android"

SO="$BUILD/android/src/panfrost/vulkan/libvulkan_panfrost.so"
[ -f "$SO" ] || { echo "missing $SO"; exit 1; }
mkdir -p "$ROOT/dist/arm64-v8a"
cp "$SO" "$ROOT/dist/arm64-v8a/"
cat > "$ROOT/dist/panfrost_icd.json" <<'EOF'
{ "file_format_version": "1.0.1",
  "ICD": { "library_path": "libvulkan_panfrost.so", "api_version": "1.4.0" } }
EOF
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$SO" | head -20
echo "OK: dist/arm64-v8a/libvulkan_panfrost.so"
```

> 若走**社区 kbase 路线**：不要用上面的上游流程，直接用 `panvk-kbase-android` 的
> `scripts/fetch-mesa.sh` → `scripts/apply-patches.sh --profile <arch>-<gpu>-<frontend>-<uapi>` →
> `scripts/build-android.sh` → `scripts/package-android-adpkg.sh`，并按它的 `profiles/` 选 profile。
> 目前该仓库 `profiles/` 只到 `g720-v12-csf`，G925/v13 走 **universal ICD**（README 的 v13 行）。

---

## 8. 设备可行性 gate（先跑这些，再决定要不要动手）

### 8.1 命令（adb；root 项单独标注）

```sh
# 1. 有没有 DRM render node / kbase 节点
adb shell 'ls -l /dev/dri 2>&1; ls -l /dev/mali0 2>&1'

# 2. mali0 的 SELinux label 与权限（判断 app 能否打开）
adb shell 'ls -lZ /dev/mali0 2>&1'

# 3. 内核版本 / 平台
adb shell 'uname -a; cat /proc/version; getprop ro.board.platform; getprop ro.soc.model'

# 4. 是否有 panthor / panfrost / mali 模块
adb shell 'ls /sys/module | grep -Ei "panthor|panfrost|mali"'
adb shell 'ls /sys/bus/platform/drivers | grep -Ei "panthor|mali"'
adb shell 'cat /sys/module/panthor/version 2>/dev/null'

# 5. 内核配置（若开了 IKCONFIG_PROC）
adb shell 'zcat /proc/config.gz 2>/dev/null | grep -Ei "CONFIG_DRM_PANTHOR|CONFIG_MALI|CONFIG_DRM_GPUVM"'

# 6. dmesg（需要 root）
adb shell 'su -c "dmesg | grep -Ei \"panthor|mali|kbase\" | tail -50"' 2>/dev/null

# 7. 固件搜索路径与固件文件
adb shell 'cat /proc/cmdline | tr " " "\n" | grep -i firmware'
adb shell 'ls -l /vendor/firmware/arm/mali/arch13.8/ 2>&1; ls -l /vendor/firmware/mali_csffw.bin 2>&1'

# 8. 当前 Vulkan 驱动
adb shell 'getprop ro.hardware.vulkan; ls -l /vendor/lib64/hw/vulkan.*.so'

# 9. app UID 能否 open /dev/mali0 —— 最硬的 gate
#    用社区 tests/kbase-probe 或 PanProbe APK（会以目标 app UID 打开并打印 errno）
```

### 8.2 判定表

| 观测 | 结论 | 下一步 |
|------|------|--------|
| `/dev/dri/renderD*` 存在 + `/sys/module/panthor` 存在（内核 ≥6.10） | 上游 PanVK 可用 | 走 §7 上游构建 + task-2 的 ICD 注入 |
| 只有 `/dev/mali0`，无 `/dev/dri`，无 panthor | 上游 PanVK **必失败**（枚举 0） | 走 §6.1 kbase 补丁栈；先验 app UID 能 open `/dev/mali0` |
| `/dev/mali0` 权限/SELinux 不允许 app 打开 | kbase 路线也不通（非 root） | 只剩 Magisk/自编内核，或放弃 PanVK |
| 无 `/dev/dri` 且 `/dev/mali0` 也不存在 | 非常规设备，先确认是不是被 SELinux 隐藏 | 进一步排查；基本放弃 |

### 8.3 若 gate 不通过的现实替代路线

| 路线 | 可行性 | 代价/风险 |
|------|--------|-----------|
| **A. 社区 kbase 补丁栈**（`panvk-kbase-android` 等） | **高**（G925 已有 37/37 真机数据） | 第三方二进制/补丁，无一致性保证；需 `/dev/mali0` app 可读；WSI/AHB 依赖其 `wsi/`、`app-loader/` 补丁 |
| **B. Magisk 模块加载 `panthor.ko`** | **低** | 需与厂商内核 vermagic/符号匹配；需 DT 节点、解除 kbase 绑定、提供 `arm/mali/arch13.8/mali_csffw.bin`；SELinux；GKI 不允许随意加 DRM 模块 |
| **C. 自编内核（含 CONFIG_DRM_PANTHOR + DT）** | 中（有源码才行） | 天玑内核源码可得性差；解锁/刷机；与 MobileGL 目标（不 root）冲突 |
| **D. 放弃 PanVK，继续用原厂 Mali Vulkan** | 高（现状） | `fillModeNonSolid`、`vertexPipelineStoresAndAtomics` 仍缺；可在 MobileGL 侧做能力降级/软件模拟（wireframe 可模拟，VS 里 SSBO/atomic 不可模拟） |
| **E. 用 kbase 栈 + MobileGL** | 高 | 需要 MobileGL 把 Vulkan 设备创建指到自定义 ICD（task-2/4 的 JNI 注入方案） |

---

## 9. 风险与未验证点（必须由 lead/实机确认）

1. **【未验证】真机 gate 结果**：无法在本机确认用户 D9400/D9500 设备是否暴露 `/dev/dri`、是否有 panthor、`/dev/mali0` 是否允许 app UID 打开。§8 命令必须实机跑。
2. **【未验证】kbase 补丁栈在用户具体机型上的可用性**：G925 的 37/37 数据来自 Galaxy Tab S11 Ultra（MT6991，Android 16，kernel 6.6.102，CSF 1.30）；不同厂商 kbase UAPI/CSF 版本可能不同。
3. **【未验证】社区项目质量**：RC4 源码未提交（二进制从设备恢复）；patch 栈无一致性声明；v13 未在 RC3 复测；建议只信 release 的 sha256 与自建验证。
4. **【未验证】Android loader 侧**：`VK_ICD_FILENAMES`/`VK_DRIVER_FILES` 在 AOSP `libvulkan` 上是否可用、app 私有 .so 的 dlopen/SELinux 限制 —— 属 task-2；本文的 ICD JSON 方案依赖其结论。
5. **【未验证】`-Dandroid-stub=true` 的运行时行为**：stub 编译期可过，但 gralloc/AHB 在真机 app 进程内的可达性（`u_gralloc` 能否拿到 mapper / 是否能 fd 导入）需实机验证。
6. **【未验证】属性名长度**：`PAN_I_WANT_A_BROKEN_VULKAN_DRIVER` 映射成的系统属性名 38 字节 > 32，`setprop` 很可能失败；v13 不需要它，但 v11/v14 需要，届时用 `wrap.sh`。
7. **【未验证】`PAN_USE_KRAID`**：Mesa `main` 的 panfrost 代码中未找到该 env（KRAID 是 `src/panfrost/compiler/kraid` 的 Rust 编译器）；stubs 仓库的该说法不采信。
8. **时间性**：26.3.0 在 2026-10-31 尚未正式发布（仅 rc3）；`main` = 27.0.0-devel，代码会漂移；本文所有行号以 2026-10-31 的 `main` 为准（并给出 25.1.0/26.2.6 对照）。

---

## 10. 参考 URL（一手）

**Mesa**
- VERSION（main）：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/VERSION
- gating：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/panfrost/vulkan/panvk_physical_device.c
- model 表：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/panfrost/model/pan_model.c
- Android 文件：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/panfrost/vulkan/panvk_android.c
- panvk meson：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/panfrost/vulkan/meson.build
- Android 构建文档：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/docs/android.rst
- Panfrost/PanVK 驱动文档：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/docs/drivers/panfrost.rst
- os_get_option（Android 属性映射）：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/util/os_misc.c
- 26.2.6 gating：https://gitlab.freedesktop.org/mesa/mesa/-/raw/mesa-26.2.6/src/panfrost/vulkan/panvk_physical_device.c
- 25.1.0 model 行：https://gitlab.freedesktop.org/mesa/mesa/-/raw/mesa-25.1.0/src/panfrost/lib/pan_props.c
- 提交：`7a8d0b78`（v13）、`fb0a8488`（G725）、`7c648edb`（G925 命名）、`c66177bd`（v11 gate）、`0350d2d0`（v14 build）

**Linux / AOSP**
- panthor uAPI：https://raw.githubusercontent.com/torvalds/linux/master/include/uapi/drm/panthor_drm.h
- panthor 固件：https://raw.githubusercontent.com/torvalds/linux/master/drivers/gpu/drm/panthor/panthor_fw.c
- panthor 6.10 证明：`.../v6.10/drivers/gpu/drm/panthor/panthor_drv.c`（200）vs `v6.9/...`（404）
- GKI 配置（android16-6.12）：https://raw.githubusercontent.com/aosp-mirror/kernel_common/android16-6.12/arch/arm64/configs/gki_defconfig
- AOSP 固件搜索路径：https://raw.githubusercontent.com/aosp-mirror/kernel_common/android16-6.12/drivers/base/firmware_loader/main.c

**社区**
- https://github.com/zenithblue-oss/panvk-kbase-android （kbase 补丁栈，G925 已验证）
- https://github.com/wonderkast02/panvk-g720-kbase-csf
- https://github.com/FristOneRR/FristOneRR-Panvk-Driver
- https://github.com/Noysz/panvk-g99-jm
- https://github.com/JimVulkan/mali-panvk
- https://github.com/martuniykmisha012-rgb/PanVK-Mali-Android-Stubs （仅预编译，机制未证实）
