# 骁龙 Turnip 驱动（内置，实验）

## 设计

- APK 内置 `libvulkan_freedreno.so`（Mesa Turnip，arm64-v8a，minApi 28），无需启动器安装驱动。
- MobileGL 不再链接系统 `libvulkan.so`：
  - Android 构建使用 volk 运行时加载；
  - 启用 Turnip 时，先通过静态链接的 libadrenotools 打开内置/外置 Turnip，再 `volkInitializeCustom()`；
  - 未启用、非 Adreno、API < 28 或加载失败时回退系统 Vulkan，不崩溃。
- 入口在 MobileGL UI：设置 / 驱动管理 / 骁龙 Turnip。不依赖 FCL/ZL2 的驱动下拉框。
- runtime.conf 键：
  - `MOBILEGL_TURNIP_ENABLED=0|1`（总是写，避免旧值残留）
  - `MOBILEGL_TURNIP_PATH=<绝对路径>`（启用时）
- 内置驱动路径：`<nativeLibraryDir>/libvulkan_freedreno.so`。
- 外置 .so 导入到 `/sdcard/MobileGL/turnip/`，由游戏进程复制到自己的 cache 后加载；该路径是实验性的，失败会回退系统 Vulkan。

## 测试前提

- 设备必须为 Qualcomm Adreno 6xx/7xx/8xx（Turnip 不支持 Mali）。
- Android 9+（API 28+）；16KB page 设备使用已重建的 16KB 对齐 hook。
- 将本 debug APK 安装到测试机，并授予“所有文件访问权限”（否则共享 runtime.conf 写不进去，UI 会显示警告）。

## 测试步骤

1. 打开 MobileGL UI → 设置 → 驱动管理 → 骁龙 Turnip。
2. 确认 GPU 显示 Adreno；打开“启用 Turnip”。
3. 在 FCL/ZL2 中**不需要**选择 Turnip 驱动；直接启动 MobileGL（Vulkan）。
4. 观察 logcat：
   ```
   adb logcat -s MobileGL RendererProxy hook_impl vulkan
   ```
   期望看到 `Turnip: using driver ... through ...`，
   以及 MobileGL 打印的 Vulkan device/driver 含 Turnip（不是 Adreno (TM)）。
5. 游戏内检查画面/光影/皮肤/区块加载；退出再启动一次。

## 回退测试

- 关闭开关 → 应使用系统 Vulkan。
- 删除/改坏 `MOBILEGL_TURNIP_PATH` → 回退系统 Vulkan，UI 不应崩溃。
- 非 Adreno 设备开关应为禁用。

## 当前限制

- 未在真实 Adreno 设备上端到端验证；构建/静态校验已通过。
- 外置 /sdcard Turnip 依赖游戏进程的存储读取权限；失败回退。
- 外部渲染器被 proxy 以 memfd 加载且同时使用外置 Turnip 时，nativeLibraryDir 推断可能失败并回退。
- 测试通过前不发布正式版 APK。
