# Android Vulkan Loader：ICD 发现、环境变量、沙盒限制与自定义驱动注入

> 调研者：android-loader（Team Lead 的调研/实现成员）
> 调研日期：2026-10-31（Asia/Shanghai）
> 适用仓库：D:\Projects\MobileGL（空工作树，remote=MobileGL-Dev/MobileGL，分支 dev）
> 本文件只写本任务范围；PanVK 编译可行性见 docs/research/mesa-panvk.md，UI/配置见 docs/research/ui-app.md。
> 证据等级：**[已核实]** = 本次抓到一手源码/官方文档并逐行核对；**[推断]** = 由已核实事实推出但未直接验证；**[未验证]** = 未拿到证据，需实测或他人确认。
> 备注：本机 GitHub API 被代理限流（403），Khronos 仓库只按 raw 文件抓取，未记录精确 commit；凡未给 commit 的条目均标 [未验证]。

---

## 0. TL;DR（结论先行）

1. **Android 系统自带的 /system/lib64/libvulkan.so 是 AOSP 自有 loader（frameworks/native/vulkan/libvulkan），不是 Khronos 上游 loader。它完全不读 VK_ICD_FILENAMES / VK_DRIVER_FILES / VK_LAYER_PATH / VK_LOADER_DEBUG，只按系统属性发现唯一一个 ICD。** **[已核实]**
2. **系统 loader 的 ICD 选择顺序：**(1) updatable driver（GraphicsEnv 的 linker namespace，Android 14+ 的“可更新 GPU 驱动”机制）→ (2) ro.vulkan.apex 指向的 Vulkan APEX → (3) vulkan.ro.hardware.vulkan.so / vulkan.ro.board.platform.so（即 /vendor/lib64/hw/vulkan.*.so）。全部失败则用 stub HAL（不崩溃，但枚举不到设备）。**[已核实]**
3. **Android 上只能有一个 Vulkan ICD**（hw_module_t 单驱动模型，Android 7.0 起）。Khronos 官方文档原话：Android 由 hw_get_module(vulkan) 发现驱动，Due to security policies in Android, none of this can be modified under normal use. **[已核实]**
4. **所以“打包 PanVK 到 APK + setenv(VK_ICD_FILENAMES) 让系统 loader 加载”不可行**：不是权限问题，是系统 loader 根本没实现读取这些环境变量。**[已核实]**
5. **可行路线只有两条**：
   - **路线 A（推荐）：自带 Khronos Vulkan-Loader**，NDK 交叉编译成 libvulkan_panvk_loader.so；app 内 setenv(VK_DRIVER_FILES, ICD JSON 绝对路径) 后 dlopen 它，通过它的 vkGetInstanceProcAddr 走自定义 ICD；失败回退系统 loader。
   - **路线 B（兜底/更黑）：libadrenotools 式**——建私有 linker namespace，加载 hook 库劫持系统 loader 的驱动查找，再 dlopen(/system/lib64/libvulkan.so)。Adreno/Turnip 生态验证过，但对 PanVK + Mali 是额外风险。
6. **Khronos 上游 loader 的 CMake 明确拒绝 Android**：elseif(ANDROID) message(FATAL_ERROR "Android build not supported!")（1.4.367 的 CMakeLists.txt L127-130）。**[已核实]** 想自捆绑必须 patch CMake（删掉该分支/自定义 platform_wsi），且 Android WSI（VK_KHR_android_surface/swapchain）行为需要实测。
7. **app 加载 .so 的安全落点**：.so 放 jniLibs（安装后在 nativeLibraryDir，SELinux apk_data_file，classloader namespace 内，系统保证可加载）是主路径；/data/data/.../files 的 .so 在 targetSdk≥29 + Android 10 W^X 下**不可依赖**（官方文档要求“只加载 APK 内二进制”；libadrenotools 在 Q+ 改用 memfd 即因私有文件加载不可靠）。**[已核实文档 + 推断]**
8. **MobileGL 侧不要指望环境变量自动生效**：MobileGL 在 macOS 的文档用法是 VK_ICD_FILENAMES=... 交给系统 loader；Android 上必须由 app 注入自定义 loader，或给 MobileGL 的 DirectVulkan/Magma 后端加“显式 loader 路径/handle”入口（见 §5）。**[已核实 MobileGL README + 推断]**
9. **最大风险不在 loader，而在 PanVK 本身**：fillModeNonSolid、vertexPipelineStoresAndAtomics 是 VkPhysicalDeviceFeatures 的硬件特性，**Mesa PanVK 不一定支持**（尤其 Mali 的 polygon mode）。换 PanVK 就能补上这两个特性必须先实测，不能假设。**[未验证，最高优先级]**

---

## 1. AOSP 系统 Vulkan Loader（/system/lib64/libvulkan.so）

### 1.1 源码位置与版本

- 源码：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/vulkan/libvulkan/
- 本次核对的 main HEAD：4f463a6b1de9198963dc6aff74154a504ba3f8f6，提交时间 **2025-03-25**（+log/refs/heads/main?format=JSON）。**[已核实]**
  - 注意：本机经代理抓到的镜像 HEAD 时间是 2025-03，晚于该日期的 Android 16/17 改动未覆盖；结论在该 commit 上成立。
- 模块：libvulkan（Android.bp），产物 libvulkan.so，安装到 /system/lib[64]/libvulkan.so。
- 本次下载核对的源文件：driver.cpp/.h、api.cpp、layers_extensions.cpp、stubhal.cpp、Android.bp、libvulkan.map.txt。

### 1.2 环境变量支持：**零支持** [已核实]

在上述 7 个文件中 grep（区分大小写）：

| 关键字 | 命中数 |
|---|---|
| VK_ICD_FILENAMES | 0 |
| VK_DRIVER_FILES | 0 |
| VK_LAYER_PATH | 0 |
| VK_LOADER_DEBUG | 0 |
| getenv( | 0 |

- 整个 loader 没有 getenv 调用（更无 secure_getenv），因此 **app 里 setenv 对系统 loader 完全无效**。
- 层（layer）只由系统属性控制：api.cpp L166-209 读 debug.vulkan.layers（冒号分隔）与 debug.vulkan.layer.<priority>；L390 读 debug.vulkan.enable_callback。**[已核实]**
  - 这些是 debug.*/ro.* 系统属性（__system_property_get），app 无权限写；VK_LAYER_PATH 不存在。
  - layers_extensions.cpp 是 VK_EXT_layer_settings 相关实现，不是搜索路径。

### 1.3 ICD 发现与加载（driver.cpp）

关键代码（本次核对的行号）：

- driver.cpp L145-148：
```cpp
const std::array<const char*, 2> HAL_SUBNAME_KEY_PROPERTIES = {{
    "ro.hardware.vulkan",
    "ro.board.platform",
}};
```
- L149：constexpr int LIB_DL_FLAGS = RTLD_LOCAL | RTLD_NOW;（**ICD 用 RTLD_LOCAL 打开**，不会把驱动符号注入全局）
- L150：RO_VULKAN_APEX_PROPERTY = "ro.vulkan.apex"
- L157-204 LoadDriver()：
  - 依次取 ro.hardware.vulkan、ro.board.platform，拼成 vulkan.<值>.so；
  - 若传入 namespace（updatable driver / APEX）：android_dlopen_ext(..., ANDROID_DLEXT_USE_NAMESPACE, ns)；
  - 否则 android_load_sphal_library("vulkan.<值>.so", ...)（sphal namespace，搜索路径含 /vendor/lib[64]/hw，等价于 /vendor/lib64/hw/vulkan.<值>.so）；
  - dlsym(HAL_MODULE_INFO_SYM_AS_STR) 取 hw_module_t，校验 hmi->id == HWVULKAN_HARDWARE_MODULE_ID。
- L206-247：LoadDriverFromApex()（ro.vulkan.apex + 导出 namespace）、LoadUpdatedDriver()（GraphicsEnv::getInstance().getDriverNamespace()，即 Android 14 引入的 updatable GPU driver 机制）。
- L249-305 Hal::Open() 的顺序（**这就是全部发现逻辑**）：
```
LoadUpdatedDriver()   // 1) 可更新驱动（GraphicsEnv namespace）
  -> 若 -ENOENT:
LoadDriverFromApex()  // 2) ro.vulkan.apex
  -> 若 -ENOENT:
LoadBuiltinDriver()   // 3) 属性 -> /vendor/lib64/hw/vulkan.<name>.so
```
  全失败 → hal_.dev_ = &stubhal::kDevice（stub HAL，进程不崩，但 Vulkan 枚举为空）。
- L284-286：module->common.methods->open(&module->common, HWVULKAN_DEVICE_0, ...) —— **单驱动/单 ICD** 模型。

### 1.4 与 AOSP 官方文档互相印证 [已核实]

来源：https://source.android.com/docs/core/graphics/implement-vulkan（本次抓取，Vulkan loader / Driver enumeration and loading 段）

- loader 安装位置：/system/lib[64]/libvulkan.so；NDK 里是 stub，符号转到真 loader 的 trampoline。
- 驱动发现用 the existing HAL mechanism in hardware.h，首选路径：
  - /vendor/lib/hw/vulkan.<ro.hardware.vulkan>.so
  - /vendor/lib/hw/vulkan.<ro.board.platform>.so
  - /vendor/lib64/hw/vulkan.<ro.hardware.vulkan>.so
  - /vendor/lib64/hw/vulkan.<ro.board.platform>.so
- 原文：In Android 7.0 and higher, the Vulkan hw_module_t derivative wraps a single hw_module_t struct; **only one driver is supported** and the constant string HWVULKAN_DEVICE_0 is passed to open().
- 原文：WSI extensions are exported by the loader and primarily implemented in the loader rather than in the driver.（→ 想绕开 loader 直接调 ICD，Android WSI 会缺；见 §5）

### 1.5 单/多 ICD 结论

- 系统 loader：**只能 1 个 ICD**，且路径由系统属性/APEX/updatable-driver 决定，app 不可改（无 root、非 vendor 分区）。**[已核实]**
- Khronos 上游 loader：支持多 ICD（VK_DRIVER_FILES 用冒号分隔多个 manifest），但 Android 上只有“自带 loader”才能用。**[已核实 Khronos 文档]**
- 想在系统 loader 里再叠加一个 ICD（VK_ADD_DRIVER_FILES 式）在 Android 上不存在。

### 1.6 设备侧快速验证命令（无需 root）

```bash
adb shell getprop ro.hardware.vulkan
adb shell getprop ro.board.platform
adb shell getprop ro.vulkan.apex
adb shell ls -l /vendor/lib64/hw/vulkan.*.so 2>/dev/null
adb shell ls -l /system/lib64/libvulkan.so
# 证明系统 loader 不含环境变量支持（预期无输出）：
adb shell strings /system/lib64/libvulkan.so | grep -E 'VK_ICD_FILENAMES|VK_DRIVER_FILES|VK_LAYER_PATH|VK_LOADER_DEBUG'
```
> 更直接的对照：写一个只调用 vkCreateInstance + vkEnumeratePhysicalDevices 的 native 测试 APK，分别在 VK_ICD_FILENAMES 指向/不指向自定义 JSON 时跑；预期输出完全相同（系统 loader 不读该变量）。**[建议实测]**

---

## 2. 应用侧可行性：私有 .so、W^X、SELinux、linker namespace

### 2.1 Android 10（targetSdk 29）W^X 官方原文 [已核实]

来源：https://developer.android.com/about/versions/10/behavior-changes-10（Removed execute permission for app home directory 段）

- Execution of files from the writable app home directory is a **W^X violation**. **Apps should load only the binary code that's embedded within an app's APK file.**
- Untrusted apps that target Android 10 **cannot invoke execve() directly on files within the app's home directory**.
- In addition, apps that target Android 10 cannot **in-memory modify executable code from files which have been opened with dlopen()** and expect those changes to be written through to disk, because the library cannot have been **mapped PROT_EXEC through a writable file descriptor**. This includes any shared object (.so) files with **text relocations**.

要点：
- 禁令针对 **execve** 和 **可写 fd 映射 PROT_EXEC / text relocation**；**不是**“禁止 dlopen 任何 /data/data 下的 .so”。
- 但官方口径是“只加载 APK 内二进制”；实际设备上，**普通、只读、无 text relocation 的 .so** 从私有目录 dlopen 往往可行（见 §2.4/2.5），但**不同厂商/内核/SELinux 策略不同，不能作为产品依赖**。

### 2.2 Android 14（targetSdk 34）动态代码加载 [已核实]

来源：https://developer.android.com/about/versions/14/behavior-changes-14（Safer dynamic code loading 段）

- If your app targets Android 14 (API level 34) or higher and uses Dynamic Code Loading (DCL), **all dynamically-loaded files must be marked as read-only**. Otherwise, the system throws an exception.
- 建议做法：文件一打开就 setReadOnly()，再写内容（防 TOCTOU）；已存在的动态加载文件建议删除重建或校验后重标记只读。
- 对 native .so：**dlopen 前把文件设为只读（0444/0555）是最低成本合规做法**；Android 14 的强制点主要覆盖 DEX/JAR/APK 类 DCL，native dlopen 是否同样抛异常**未在本次验证**（[未验证]），但按官方建议做没有坏处。

### 2.3 SELinux：不同 targetSdk 的 app 域差异 [已核实]

来源：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/（本次抓取 untrusted_app_27.te、untrusted_app_29.te、untrusted_app_30.te；untrusted_app_34.te 不存在）

- untrusted_app_27.te（targetSdk 27 域）：
```
allow untrusted_app_27 { apk_data_file app_data_file asec_public_file }:file execmod;
allow untrusted_app_27 app_data_file:file execute_no_trans;
auditallow untrusted_app_27 app_data_file:file { execute execute_no_trans };
```
- untrusted_app_29.te / untrusted_app_30.te：**grep execute|app_data_file 为 0 命中**（targetSdk≥29 的 app 域被移除了对 app_data_file 的 execute_no_trans/execmod）。
- 基础策略 private/app.te L274-279：appdomain 对 app_data_file 只有 { getattr map read write }，**没有 execute**；apk_data_file 则由 untrusted_app 显式授予执行相关权限。

结论/推断：
- targetSdk≥29 时，**execve 私有目录文件** 和 **带 text relocation/被就地修改的 .so** 会被 SELinux 拒绝；**普通只读 .so 的 dlopen（mmap PROT_EXEC，走 map 权限）在 SELinux 策略层面仍有空间**（这也是 targetSdk 27 域没有 execute 却允许 dlopen 的原因）。**[推断，基于策略与内核挂钩语义]**
- 但 linker namespace 还有第二道门（§2.5），且厂商可加严；**工程上不要把私有目录 dlopen 当主路径**。
- 私有目录 .so 的 SELinux label 是 app_data_file；APK 解压出的 native lib 是 apk_data_file（可执行、系统保证 app 可加载）。**把 ICD/loader 放 jniLibs 是唯一“官方支持”的落点。** **[已核实策略 + 官方文档口径]**

### 2.4 已知项目的实际做法（旁证）

- **libadrenotools**（https://github.com/bylaws/libadrenotools ，README + src/driver.cpp 本次核对）：
  - README：A library for applying rootless Adreno GPU driver modifications/replacements. Currently supports loading custom GPU drivers such as turnip… Android 9+ / Arm64。
  - src/driver.cpp L19-93：先 android_create_namespace(adrenotools-libvulkan, hookLibDir, ...) 建**私有 linker namespace**，linkernsbypass_link_namespace_to_default_all_libs() 打通 default 库，预加载 libhook_impl.so，再 linkernsbypass_namespace_dlopen(libmain_hook.so, RTLD_GLOBAL, hookNs)，最后 linkernsbypass_namespace_dlopen_unique(/system/lib64/libvulkan.so, tmpLibDir, dlopenFlags, hookNs) —— **即劫持系统 loader 的驱动查找，而不是改环境变量**。
  - L24-26 注释：Always use memfd on Q+ since it's guaranteed to work（tmpLibDir=nullptr）——说明 Android 10+ 把（被 patch 过的）libvulkan.so 落地到私有目录再 dlopen 不可靠，改走 memfd。**[已核实代码注释]**
  - 该库依赖非 NDK 公开的 linker 符号（android_create_namespace/android_get_exported_namespace 等），通过其 linkernsbypass 组件获取，不是普通 app 能直接用的 NDK API。**[已核实]**

### 2.5 linker namespace 规则 [已核实]

- AOSP 官方文档 https://source.android.com/docs/core/architecture/vndk/linker-namespace（How it works）：
  > In both cases, the dynamic linker finds the **linker namespace where the caller resides** and tries to load the dependencies **into the same linker namespace**. If the dynamic linker can't load the shared library into the specified linker namespace, it asks the **linked linker namespace for exported shared libraries**.
- bionic 源码 platform/bionic/linker/linker.cpp（main，本次抓取）do_dlopen() L2116-2123：
```cpp
void* do_dlopen(const char* name, int flags,
                const android_dlextinfo* extinfo,
                const void* caller_addr) {
  ...
  soinfo* const caller = find_containing_library(caller_addr);
  android_namespace_t* ns = get_caller_namespace(caller);
```
  → **dlopen 用“调用者所在的 namespace”**。我们的 loader 若从 app 的 classloader namespace 里 dlopen，则它后续 dlopen ICD 也用 classloader namespace。
- app 进程（zygote 派生）的 namespace 结构（Android 7+ Treble）：classloader-namespace（app 私有，含 nativeLibraryDir）、default/system（public NDK 库）、sphal/vndk（vendor HAL，isolated，app 不可见/不可导出给 app）。因此：
  - **系统 libvulkan.so 去 dlopen app 私有路径的 .so 会受 namespace 限制**（它属于 default/system 侧，classloader 的私有路径不在其搜索/允许范围），这也解释了为什么 libadrenotools 必须建自己的 namespace + hook，而不是简单 setenv。**[推断，与官方 namespace 文档一致]**
  - android_dlopen_ext()（NDK 公开，android/dlext.h）支持 ANDROID_DLEXT_USE_NAMESPACE，但**创建/获取 namespace 的 API（android_create_namespace 等）不是 NDK 公开 API**；普通 app 只能用默认 classloader namespace。**[已核实：NDK 头文件只暴露 dlext 结构，不暴露 create_namespace]**
  - **APK 内未压缩 .so** 可用 ANDROID_DLEXT_USE_LIBRARY_FD 从 APK fd 直接 android_dlopen_ext（NDK 公开），但 ICD JSON 的 library_path 需要真实路径，不能写 base.apk!/lib/...；所以 ICD .so 仍应解压到 nativeLibraryDir。

### 2.6 结论表

| 位置 | SELinux label | 可执行/可 dlopen | 在 app classloader ns | 建议 |
|---|---|---|---|---|
| nativeLibraryDir（jniLibs 解压） | apk_data_file | 是 | 是（搜索路径内） | **主路径** |
| APK 内未压缩 .so | apk_data_file（经 fd/偏移） | 是 | 是（android_dlopen_ext + FD） | 可用，但 ICD JSON 需真实路径 |
| /data/data/<pkg>/files/*.so | app_data_file | 普通只读 .so 通常可以；text reloc/被修改的不行（targetSdk≥29） | 绝对路径可能被 ns 拒绝 | 不依赖，需逐机型实测 |
| /vendor/lib64/hw/vulkan.*.so | vendor HAL 标签 | 仅 vendor 进程可加载 | 否（sphal isolated） | 不可用（需 root/vendor） |

---

## 3. 自捆绑 Khronos Vulkan-Loader 路线

### 3.1 版本与现状 [已核实]

- 仓库：https://github.com/KhronosGroup/Vulkan-Loader （main，本次 raw 抓取，2026-10-31）
- CMakeLists.txt L21：project(VULKAN_LOADER VERSION 1.4.367 LANGUAGES C) → **loader 版本线 1.4.367**。
  - 精确 commit hash 未记录（GitHub API 403），[未验证]；以抓取日 main 为准。
- **Android 被上游明确禁用**：CMakeLists.txt L126-130
```cmake
add_library(platform_wsi INTERFACE)
if(WIN32)
    target_compile_definitions(platform_wsi INTERFACE VK_USE_PLATFORM_WIN32_KHR)
elseif(ANDROID)
    message(FATAL_ERROR "Android build not supported!")
```
  **[已核实]** → 直接 -DANDROID_ABI=... 交叉编译会立即失败。
- loader 源码里**没有** hw_get_module / ro.hardware.vulkan（grep 0 命中；hw_get_module 只出现在 Khronos 文档的 Driver Discovery on Android 说明段，描述的是 **Android 系统 loader**，不是 Khronos loader）。**[已核实]**
- loader 只认 ICD JSON manifest（VK_DRIVER_FILES / VK_ADD_DRIVER_FILES / 默认搜索目录），**不认 Android HAL 模块**。**[已核实]**

### 3.2 Khronos loader 的环境变量语义（自捆绑后有用）[已核实]

来源：docs/LoaderDriverInterface.md（本次抓取）

- VK_DRIVER_FILES（新）与 VK_ICD_FILENAMES（旧，deprecated）**行为相同**；同时存在时 VK_DRIVER_FILES 生效、VK_ICD_FILENAMES 被忽略（L116-122）。
- 值是 **ICD JSON manifest 的路径列表**（文件或目录），Linux/Android 用冒号 : 分隔（L124-131）。
- VK_ADD_DRIVER_FILES：追加而非替换；但**若 VK_DRIVER_FILES/VK_ICD_FILENAMES 存在，则 VK_ADD_DRIVER_FILES 被忽略**（L133-146）。
- VK_LOADER_DRIVERS_SELECT / VK_LOADER_DRIVERS_DISABLE：按 manifest 文件名 glob 过滤（L148-175，需 loader ≥ 1.3.234）。
- VK_LOADER_DEBUG=error,warn 打开日志（docs/LoaderInterfaceArchitecture.md L682-705）。
- 环境变量读取实现（loader/loader_environment.c L42-88）：Android 走 Linux 分支；若 HAVE_SECURE_GETENV/HAVE___SECURE_GETENV 都没有，则**回退到普通 getenv 并打日志**。
  - [推断] bionic 通常没有 secure_getenv，因此 NDK 构建的 loader 会退回 getenv → **app 内 setenv 会被读到**。这正是“自捆绑 loader + setenv”路线成立的基础（需在设备上确认 VK_LOADER_DEBUG 有输出）。
- 默认搜索目录由 CMake SYSCONFDIR/FALLBACK_DATA_DIRS（/etc/xdg、/usr/local/share:/usr/share）编死；Android 上这些目录不存在 → **必须显式 VK_DRIVER_FILES，或 patch 默认目录**。**[已核实 CMake]**

### 3.3 构建方案（NDK）

上游 CMake 需要 patch。最小 patch（示意，需实测）：

```cmake
# Vulkan-Loader/CMakeLists.txt 中替换 L129-130：
elseif(ANDROID)
    target_compile_definitions(platform_wsi INTERFACE VK_USE_PLATFORM_ANDROID_KHR)
    # 注意：loader 本体不含 Android WSI 实现；这个宏只影响平台 surface 扩展列表，
    # Android swapchain/WSI 的实际行为必须上机验证。
```

构建命令（NDK r27+ 自带 android.toolchain.cmake）：

```bash
export NDK=$ANDROID_NDK_ROOT
cmake -S Vulkan-Loader -B build-android-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTS=OFF \
  -DBUILD_WSI_XCB_SUPPORT=OFF -DBUILD_WSI_XLIB_SUPPORT=OFF \
  -DBUILD_WSI_WAYLAND_SUPPORT=OFF -DBUILD_WSI_DIRECTFB_SUPPORT=OFF
cmake --build build-android-arm64 -j
```

- 产物默认名 libvulkan.so（上游未设 OUTPUT_NAME/SOVERSION，本次 grep CMake 无命中）。**[已核实]**
- **必须改名**（否则与系统 libvulkan.so 的 SONAME 冲突，dlopen/依赖解析会混）：
```bash
cp build-android-arm64/libvulkan.so libvulkan_panvk_loader.so
# 或构建时 -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-soname,libvulkan_panvk_loader.so
$NDK/toolchains/llvm/prebuilt/*/bin/llvm-readelf -d libvulkan_panvk_loader.so | grep SONAME
```

- -DANDROID_PLATFORM 选择：MobileGL 最低 API 未知；建议 ≥ 26（AHardwareBuffer 需要 26+），与 MobileGL 的 minSdk 对齐。**[未验证 MobileGL minSdk]**

### 3.4 已知项目先例（证据强度不一）

| 项目/方案 | 机制 | 证据 | 对本案价值 |
|---|---|---|---|
| **libadrenotools**（bylaws） | 私有 namespace + hook 系统 loader 的驱动查找；Q+ 用 memfd 放 patch 过的 loader | README + src/driver.cpp 本次核对；README 声明 Android 9+/arm64 | 证明“无 root 换 Vulkan 驱动”在 Android 上可行，但路线是 hook，不是 env |
| AdrenoToolsTest（darksylinc） | 集成示例 | README 提供链接 [未验证代码细节] | 落地参考 |
| **Mesa Android NDK 文档** docs/android.rst | -Dvulkan-drivers=... 交叉编译；HAL 场景 patchelf --set-soname vulkan.<name>.so 后推到 /vendor/lib64/hw/ | 本次抓取（L32/L94/L121-130）；panvk 需 -Dtools=panfrost -Dinstall-precomp-compiler=true（L38/L100） | 证明 PanVK 可用 NDK 构建；但它示范的是 vendor HAL 安装（需 root），不是 app 内注入 |
| **Mesa panvk（Linux/开发）** | VK_DRIVER_FILES 指向 panfrost_icd.<arch>.json（devenv） | src/panfrost/vulkan/meson.build L206-293（本次核对）：产物 libvulkan_panfrost.so，ICD JSON panfrost_icd.*，devenv 设置 VK_DRIVER_FILES | 正是路线 A 的 ICD 形态 |
| **Winlator** | Wine/Box64 + Mesa Turnip（README Credits 列出 Mesa Turnip/Zink/VirGL） | README 本次核对；具体 loader 注入代码 [未验证] | 运行环境是 glibc/proot，与 app 内 bionic 注入不同 |
| **Termux / Vita3K / yuzu / Mobox** | — | 未在本轮拿到一手源码 | [未验证]，不要引用为结论 |

---

## 4. JNI C++ 参考实现（路线 A：自带 loader + 自定义 ICD）

### 4.1 设计要点

1. **先 setenv，后 dlopen loader**：loader 在第一次 vkCreateInstance 时读取 VK_DRIVER_FILES；环境变量是进程级，必须在任何 Vulkan 初始化之前设置。
2. **ICD JSON 写到 app 私有目录**（只读文本，非 .so，不受 W^X 影响）；library_path 写 **nativeLibraryDir 下的绝对路径**。
3. **loader 和 ICD .so 都放 jniLibs/arm64-v8a/**（安装后 nativeLibraryDir），这是唯一可靠的可 dlopen 位置。
4. **dlopen(loader, RTLD_NOW | RTLD_LOCAL)**：默认即 LOCAL，不污染全局符号；**不要 link -lvulkan**，不要 dlclose 已创建的 instance。
5. **用 dlsym(handle, vkGetInstanceProcAddr) 取入口**，再从它拿 vkCreateInstance / vkEnumeratePhysicalDevices；不要依赖 ELF 导入符号（否则会绑到系统 libvulkan.so）。
6. **失败回退系统 loader**：dlopen("libvulkan.so", RTLD_NOW|RTLD_LOCAL)（不加路径 → 走 classloader ns 搜索，成功即系统库）。
7. **不要 ship 一个叫 libvulkan.so 的文件到 jniLibs** 试图顶掉系统库：若进程已先加载系统 libvulkan（Java Vulkan/ANGLE/其他 SDK），按名解析会拿到已加载的那个，行为不可控。**改 MobileGL 代码显式接收 loader 路径才是可控方案。**
8. ICD JSON 格式（Khronos docs/LoaderDriverInterface.md L706-745，[已核实]）：
```json
{
  "file_format_version": "1.0.1",
  "ICD": {
    "library_path": "/data/app/.../lib/arm64/libvulkan_panfrost.so",
    "api_version": "1.1.0",
    "library_arch": "64"
  }
}
```
   - library_path：绝对路径、或相对 JSON 的相对路径、或纯文件名（走系统搜索路径）。
   - library_arch："32"/"64"，可选但建议写。
   - api_version：驱动支持的最高 Vulkan 版本（PanVK 实际值需查构建产物/驱动，[未验证]；宁低勿高，如 1.1.0，最终以 vkEnumerateInstanceVersion/vkGetPhysicalDeviceProperties 实测为准）。

### 4.2 参考代码 panvk_loader_jni.cpp

```cpp
// CMake: target_link_libraries(... dl log)  — 千万不要 link vulkan
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "PanVKLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Loader {
    void* handle = nullptr;                      // loader .so
    PFN_vkGetInstanceProcAddr gipa = nullptr;    // loader 的 vkGetInstanceProcAddr
    bool custom = false;                         // true=自带 loader+PanVK
};

std::once_flag g_once;
Loader g_loader;

bool writeIcdJson(const std::string& jsonPath, const std::string& driverSo) {
    FILE* f = fopen(jsonPath.c_str(), "wb");
    if (!f) { LOGE("open icd json failed: %s", jsonPath.c_str()); return false; }
    fprintf(f, R"json({
  "file_format_version": "1.0.1",
  "ICD": {
    "library_path": "%s",
    "api_version": "1.1.0",
    "library_arch": "64"
  }
})json", driverSo.c_str());
    fclose(f);
    chmod(jsonPath.c_str(), 0444);   // Android 14+ DCL 建议：加载前只读
    return true;
}

Loader openLoader(const char* path, bool custom) {
    Loader l;
    l.handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!l.handle) { LOGE("dlopen %s failed: %s", path, dlerror()); return l; }
    l.gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            dlsym(l.handle, "vkGetInstanceProcAddr"));
    if (!l.gipa) {
        LOGE("%s: missing vkGetInstanceProcAddr", path);
        dlclose(l.handle);
        l.handle = nullptr;
    }
    l.custom = custom;
    return l;
}

// 用 loader 的入口创建 instance，并枚举 physical device 做自检
bool probe(PFN_vkGetInstanceProcAddr gipa, bool wantSurface, std::string* outName) {
    auto create = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
    auto enumDev = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
            gipa(nullptr, "vkEnumeratePhysicalDevices"));
    auto enumExt = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            gipa(nullptr, "vkEnumerateInstanceExtensionProperties"));
    if (!create || !enumDev) return false;

    std::vector<const char*> exts;
    uint32_t extCount = 0;
    if (enumExt && enumExt(nullptr, &extCount, nullptr) == VK_SUCCESS) {
        std::vector<VkExtensionProperties> props(extCount);
        enumExt(nullptr, &extCount, props.data());
        auto has = [&](const char* n) {
            for (auto& p : props) if (strcmp(p.extensionName, n) == 0) return true;
            return false;
        };
        if (wantSurface && has(VK_KHR_SURFACE_EXTENSION_NAME))
            exts.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
        if (wantSurface && has(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME))
            exts.push_back(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
    }

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "MobileGL/panvk-probe";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = (uint32_t)exts.size();
    ci.ppEnabledExtensionNames = exts.data();

    VkInstance instance = VK_NULL_HANDLE;
    VkResult r = create(&ci, nullptr, &instance);
    if (r != VK_SUCCESS) { LOGE("vkCreateInstance -> %d", r); return false; }

    uint32_t n = 0;
    r = enumDev(instance, &n, nullptr);
    if (r == VK_SUCCESS && n > 0) {
        std::vector<VkPhysicalDevice> devs(n);
        enumDev(instance, &n, devs.data());
        auto getProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
                gipa(instance, "vkGetPhysicalDeviceProperties"));
        if (getProps) {
            VkPhysicalDeviceProperties p{};
            getProps(devs[0], &p);
            *outName = p.deviceName;
            LOGI("device[0]='%s' api=%u.%u.%u", p.deviceName,
                 VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion),
                 VK_VERSION_PATCH(p.apiVersion));
            // 关键特性自检（用户痛点的直接判据）
            auto getFeat = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures>(
                    gipa(instance, "vkGetPhysicalDeviceFeatures"));
            if (getFeat) {
                VkPhysicalDeviceFeatures f{};
                getFeat(devs[0], &f);
                LOGI("fillModeNonSolid=%d vertexPipelineStoresAndAtomics=%d",
                     f.fillModeNonSolid, f.vertexPipelineStoresAndAtomics);
            }
        }
    } else {
        LOGE("vkEnumeratePhysicalDevices -> %d count=%u", r, n);
    }

    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(gipa(instance, "vkDestroyInstance"));
    if (destroy) destroy(instance, nullptr);
    return !outName->empty();
}

bool initOnce(const std::string& filesDir, const std::string& nativeLibDir) {
    const std::string driverSo = nativeLibDir + "/libvulkan_panfrost.so"; // PanVK ICD
    const std::string loaderSo = nativeLibDir + "/libvulkan_panvk_loader.so";
    const std::string jsonPath = filesDir + "/panvk_icd.json";

    if (access(driverSo.c_str(), R_OK) != 0) {
        LOGE("PanVK driver missing: %s", driverSo.c_str());
    } else if (!writeIcdJson(jsonPath, driverSo)) {
        // fallthrough to system loader
    } else {
        // 1) 环境变量必须在任何 Vulkan 初始化前设置
        setenv("VK_DRIVER_FILES", jsonPath.c_str(), 1);
        unsetenv("VK_ICD_FILENAMES");              // 避免旧变量歧义
#ifdef NDEBUG
        unsetenv("VK_LOADER_DEBUG");
#else
        setenv("VK_LOADER_DEBUG", "error,warn", 1);
#endif
        Loader l = openLoader(loaderSo.c_str(), true);
        if (l.handle) {
            std::string name;
            if (probe(l.gipa, /*wantSurface=*/false, &name)) {
                g_loader = l;                       // 保留句柄，不 dlclose
                LOGI("using PanVK ICD via bundled loader: %s", name.c_str());
                return true;
            }
            LOGE("bundled loader could not enumerate PanVK device, fallback");
            dlclose(l.handle);                      // probe 已销毁 instance，可关
        }
        unsetenv("VK_DRIVER_FILES");                // 回退前清掉
    }

    // 2) 回退系统 loader / 系统驱动
    Loader sys = openLoader("libvulkan.so", false);
    if (!sys.handle) return false;
    std::string name;
    if (!probe(sys.gipa, /*wantSurface=*/false, &name)) {
        LOGE("system Vulkan also unusable");
        dlclose(sys.handle);
        return false;
    }
    g_loader = sys;
    LOGI("fallback to system Vulkan: %s", name.c_str());
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_mobilegl_panvk_VulkanBridge_nativeInit(JNIEnv* env, jclass,
                                                jstring filesDir, jstring nativeLibDir) {
    const char* fd = env->GetStringUTFChars(filesDir, nullptr);
    const char* nd = env->GetStringUTFChars(nativeLibDir, nullptr);
    std::string f(fd), n(nd);
    env->ReleaseStringUTFChars(filesDir, fd);
    env->ReleaseStringUTFChars(nativeLibDir, nd);

    std::call_once(g_once, [&] { initOnce(f, n); });
    return g_loader.handle ? JNI_TRUE : JNI_FALSE;
}

// 交给 MobileGL：loader 句柄 + vkGetInstanceProcAddr（MobileGL 只通过它取函数）
extern "C" JNIEXPORT jlong JNICALL
Java_org_mobilegl_panvk_VulkanBridge_nativeGetLoaderHandle(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(g_loader.handle);
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_mobilegl_panvk_VulkanBridge_nativeGetGetInstanceProcAddr(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(g_loader.gipa);
}
```

### 4.3 关键坑（实现 review checklist）

- **符号冲突/SONAME**：loader 改名为 libvulkan_panvk_loader.so，并检查 llvm-readelf -d 的 SONAME；**别让 MobileGL 或 JNI 库 DT_NEEDED libvulkan.so**（NDK stub 只用于编译，运行时别用）。
- **RTLD_LOCAL vs GLOBAL**：默认 RTLD_LOCAL。若 MobileGL 内部用 dlsym(RTLD_DEFAULT, "vkCreateInstance") 而不是走我们给的 handle，会拿到系统 loader；必须把 handle/gipa 传进 MobileGL。
- **env 时序**：进程内第一次 Vulkan 调用前 setenv；若已有组件建过 instance（如 Java Vulkan API 或 ANGLE），再 setenv 不一定生效。**在 Application.onCreate 或最早 native init 里做。**
- **PanVK ICD 的 DT_NEEDED 依赖**：app namespace 只能看到 public NDK 库 + nativeLibraryDir 自己的库；若 PanVK 链了 libhardware.so/libcutils.so/libsync.so 等平台私有库，dlopen 会失败（library not found / cannot locate symbol）。**必须让 ICD 只依赖 libc/libm/libdl/liblog/libandroid，或把依赖一起打进 jniLibs（符号/ABI 兼容）。这是本项目最可能翻车的点之一。** [未验证 PanVK 实际依赖]
- **library_path 不能是 APK 内路径**（base.apk!/lib/...）；必须是真实文件路径（nativeLibraryDir 或解压目录）。
- **Android 14 DCL**：ICD .so 是 APK 内解压产物，天然只读；若运行中要下载/替换驱动，落盘后必须 chmod 0444 再 dlopen。
- **32/64 位**：只支持 arm64-v8a（Mali-G925 机型 64 位）；abiFilters 只留 arm64-v8a。
- **VK_LOADER_DEBUG 只对 Khronos loader 有效**：系统 loader 不打印（现场判据）。

---

## 5. MobileGL 如何“优先”使用自定义 ICD

### 5.1 MobileGL 现状（据 README，本次核对）[已核实]

- 环境变量：MOBILEGL_BACKEND_TYPE=DirectVulkan|DirectGLES（默认 DirectGLES）；Magma 即 DirectVulkan 后端（README 有 MOBILEGL_MAGMA_DISABLE_SUBGROUP）。
- macOS 用法：env DYLD_INSERT_LIBRARIES=... MOBILEGL_BACKEND_TYPE=DirectVulkan VK_ICD_FILENAMES=/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json → **MobileGL 依赖“宿主系统 loader 读 env”** 来选 ICD。
- 仓库结构：MG_State / MG_Impl / MG_Backend（DirectGLES、Direct(Vulkan)）；构建 CMake，Android 上测试/benchmark 关闭。
- [未验证] DirectVulkan/Magma 后端具体在哪个文件 dlopen("libvulkan.so") / 链接 libvulkan；需要 lead 的 android-plugin 实现时在克隆后的仓库里 grep dlopen、vkGetInstanceProcAddr、VK_ICD_FILENAMES。

### 5.2 推荐方案（按优先级）

**方案 1（推荐）：给 MobileGL 加“显式 loader 注入”入口（lead 的 android-plugin 扩展）**

```cpp
// MobileGL 侧伪代码（DirectVulkan / Magma backend init）
struct VulkanLoaderApi {
    void* handle;
    PFN_vkGetInstanceProcAddr gipa;
};
// 优先级：显式传入的 handle > 环境变量指定的绝对路径 loader > 系统 libvulkan.so
```
- 环境变量建议新增（与现有 MOBILEGL_* 风格一致）：
  - MOBILEGL_VULKAN_LOADER=/data/app/.../lib/arm64/libvulkan_panvk_loader.so
  - MOBILEGL_VULKAN_DRIVER_JSON=/data/user/0/<pkg>/files/panvk_icd.json（若 MobileGL 想自己写 JSON）
- MobileGL 内所有 Vulkan 入口统一走 gipa(nullptr|instance, name)，**不要**用链接期符号；这样“系统 loader 还是自带 loader”完全由 app 决定。
- app 侧：nativeInit() 里按 §4 顺序做（自带 loader → 枚举自检 → 失败回退），把 handle/gipa 传给 MobileGL；MobileGL 不再自己 dlopen。

**方案 2（最小侵入，仅验证用）：app 在用 MobileGL 前把自己 loader 以“可解析的名字”预加载**

- 前提：MobileGL 里是 dlopen("libvulkan.so", ...)（按名）。
- 做法：把自带 loader 命名/安装为 nativeLibraryDir 下的 libvulkan.so，且在**任何代码加载系统 libvulkan 之前**由 app 先 dlopen 它，让按名 dlopen("libvulkan.so") 命中它。
- 风险高：1) 系统若因 Java Vulkan/ANGLE/其他 SDK 已加载系统 libvulkan.so，按名会返回系统库；2) 同名文件在 jniLibs 会与系统库同名，调试/符号解析容易混；3) 不同 Android 版本 linker 对“同名不同路径”的处理有差异。**只作为临时验证手段，不建议产品化。**

**方案 3（不推荐但记录）：libadrenotools 式 hook 系统 loader**

- 用 android_create_namespace + linkernsbypass 建私有 namespace，hook 系统 loader 的 hw_get_module/dlopen，让它加载我们的 PanVK HAL 模块。
- 优点：不动 MobileGL、不需要自带 loader；缺点：依赖非 NDK 符号与专属 hook 库、厂商/版本相关性高、PanVK 需要伪装成 hwvulkan_module_t（HAL_MODULE_INFO_SYM + HWVULKAN_HARDWARE_MODULE_ID），调试成本最高。只在方案 1/2 都失败时评估。

**方案 4（明确不推荐）：直接调 ICD 的 vk_icdGetInstanceProcAddr，不走任何 loader**

- PanVK 导出的是 ICD 接口（vk_icdGetInstanceProcAddr/vk_icdNegotiateLoaderICDInterfaceVersion），不是应用接口。
- Android 的 WSI（VK_KHR_android_surface/swapchain）在系统 loader 里实现（AOSP 文档原话），绕开 loader 会丢失呈现路径。仅适合纯离屏枚举/自检。

### 5.3 端到端建议链路

```
App Application.onCreate / 最早 native init
  └─ 1. 确保 libvulkan_panvk_loader.so + libvulkan_panfrost.so 已在 nativeLibraryDir
     2. 写 /data/user/0/<pkg>/files/panvk_icd.json（library_path=绝对路径，chmod 0444）
     3. setenv VK_DRIVER_FILES=<json>; 清空 VK_ICD_FILENAMES
     4. dlopen(loader, RTLD_NOW|RTLD_LOCAL) -> vkGetInstanceProcAddr
     5. 建临时 instance + vkEnumeratePhysicalDevices + vkGetPhysicalDeviceFeatures
        - 必须同时校验：fillModeNonSolid、vertexPipelineStoresAndAtomics、API/扩展是否覆盖 MobileGL 需求
     6. 成功 -> 把 handle/gipa 交给 MobileGL（方案 1）
        失败 -> unsetenv VK_DRIVER_FILES，回退 dlopen("libvulkan.so") 系统 loader
     7. 全流程日志写 UI（配置 App 的“驱动自检”页直接用这些结果）
```

### 5.4 风险清单（按优先级）

1. **PanVK 特性不一定覆盖用户痛点**：fillModeNonSolid（Mali 多边形填充模式）、vertexPipelineStoresAndAtomics（SSBO/atomic 支持）是 VkPhysicalDeviceFeatures，PanVK 可能为 false；换驱动 ≠ 自动拥有这两个特性。**必须先用 §4 的 probe 在设备上打印。** [未验证]
2. **PanVK ICD 的运行时依赖**：平台私有库（libhardware/libcutils/libsync 等）在 app namespace 不可见 → dlopen 失败或 vkCreateInstance 崩。需要“只依赖 NDK 公开库”的构建（可能正是用户提到的 PanVK-Mali-Android-Stubs 要解决的东西；[未验证] 该方案细节，见 mesa-panvk 文档）。
3. **Khronos loader 的 Android 构建/WSI 未经验证**：上游明确 FATAL_ERROR；patch 后能否稳定跑通 vkCreateInstance、VK_KHR_android_surface、swapchain 需要实测。若 MobileGL 只需离屏渲染到 GLES/ANativeWindow 纹理，可先绕开 swapchain。
4. **MobileGL 的 Vulkan 版本/扩展假设**：MobileGL 需要 OpenGL 4.2 语义（geometry/tessellation/SSBO/atomic 等），PanVK 的 Vulkan 特性覆盖需逐项核对；缺特性时 MobileGL 需要降级/模拟（不是 loader 能解决的）。
5. **系统 loader 与自带 loader 混用**：任何仍链接 libvulkan.so 的第三方 .so（Vulkan SDK、ANGLE、Java Vulkan）都会绕过我们的 loader；需要全链路统一（方案 1）。
6. **Android 版本/厂商差异**：linker namespace、SELinux、memfd/exec 限制随 OEM 变化；至少在目标机（天玑 + Mali-G925，Android 版本待确认）实测三条路径：nativeLibraryDir dlopen、filesDir dlopen、APK fd dlopen。
7. **license/合规**：Mesa (MIT)、Khronos loader (Apache-2.0)、MobileGL (LGPL-3.0) 的链接义务需在分发前确认。

---

## 6. 待办 / 需要其他人补的证据

- [ ] 目标设备 Android 版本、minSdk、ro.hardware.vulkan/ro.board.platform 实测值（lead 收集）。
- [ ] 克隆 MobileGL 后 grep：dlopen、vkGetInstanceProcAddr、libvulkan、VK_ICD_FILENAMES，确认 Magma/DirectVulkan 的加载点（lead/UI 成员）。
- [ ] PanVK 构建产物：libvulkan_panfrost.so 的 DT_NEEDED、导出符号（vk_icdGetInstanceProcAddr）、api_version（mesa-panvk 成员）。
- [ ] PanVK 的 VkPhysicalDeviceFeatures：fillModeNonSolid、vertexPipelineStoresAndAtomics、geometryShader/tessellationShader 等（mesa-panvk 成员）。
- [ ] Khronos loader patch 后交叉编译 + 设备上跑通 probe（lead 的实现任务）。
- [ ] PanVK-Mali-Android-Stubs 的确切内容/来源（本文件未找到一手证据；[未验证]）。

---

## 7. 参考链接（本次抓取，2026-10-31）

- AOSP loader 源码（main）：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/vulkan/libvulkan/
  - driver.cpp：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/vulkan/libvulkan/driver.cpp
  - api.cpp：https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/vulkan/libvulkan/api.cpp
- AOSP 文档 Vulkan 实现：https://source.android.com/docs/core/graphics/implement-vulkan
- AOSP linker namespace：https://source.android.com/docs/core/architecture/vndk/linker-namespace
- bionic linker：https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker.cpp
- SELinux：https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te
  - https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_27.te
  - https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_29.te
- Android 10 行为变更：https://developer.android.com/about/versions/10/behavior-changes-10
- Android 14 行为变更（DCL）：https://developer.android.com/about/versions/14/behavior-changes-14
- Khronos Vulkan-Loader：https://github.com/KhronosGroup/Vulkan-Loader
  - docs/LoaderDriverInterface.md：https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderDriverInterface.md
  - docs/LoaderInterfaceArchitecture.md：https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderInterfaceArchitecture.md
  - loader/loader_environment.c：https://github.com/KhronosGroup/Vulkan-Loader/blob/main/loader/loader_environment.c
- libadrenotools：https://github.com/bylaws/libadrenotools （src/driver.cpp）
- Mesa Android 构建文档：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/docs/android.rst
- Mesa panvk meson：https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/panfrost/vulkan/meson.build
- MobileGL：https://github.com/MobileGL-Dev/MobileGL

> 抓取方式备注：本机 web_fetch 直连失败，全部源码/文档经本机代理 http://127.0.0.1:18809 用 curl 获取；Gitiles 的 ?format=TEXT 为 base64，已本地解码核对。
