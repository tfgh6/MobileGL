# 许可证与第三方声明（草案）

> 适用范围：本分支新增的脚本、文档、Android 插件代码、以及打包进 APK 的第三方二进制。
> 本文件不是法律意见；发布前请按实际依赖复核。

## 1. 本项目新增代码

与 MobileGL 上游保持一致：**GNU Lesser General Public License v3.0 only**（SPDX：`LGPL-3.0-only`）。

- 新文件头统一使用：

```c
// SPDX-License-Identifier: LGPL-3.0-only
```

- 仓库根目录已有 `COPYING` / `COPYING.LESSER`，发布 APK 时随源码提供。
- Kotlin/Java/脚本文件同样标注 `LGPL-3.0-only`。

## 2. 随 APK 分发的第三方组件（计划）

| 组件 | 许可证 | 说明 |
|---|---|---|
| MobileGL | LGPL-3.0-only | 上游主体 |
| Mesa（PanVK / libdrm） | MIT（部分文件 SGI Free License B） | 编译为 `libpanvk.so`；保留版权与许可文本 |
| Vulkan-Loader（Khronos，若自带） | Apache-2.0 | 编译为独立 SO；附 NOTICE |
| AndroidX / Jetpack Compose | Apache-2.0 | UI 运行时 |
| Miuix（若采用） | Apache-2.0 | 可选 UI 主题 |
| Gson / kotlinx.coroutines | Apache-2.0 | 配置与协程 |
| MobileGlues-plugin（仅参考设计，不复制代码） | LGPL-2.1 | 若后续复制代码，该部分须保留 LGPL-2.1 并单独声明 |

## 3. 分发要求清单

- [ ] APK 内"关于/开源许可"页面可查看上述许可全文与源码地址。
- [ ] 发布源码包包含 `COPYING`、`COPYING.LESSER`、Mesa MIT 文本、Vulkan-Loader NOTICE。
- [ ] `libpanvk.so` 与自带 loader 的构建脚本、commit、补丁公开可复现（LGPL 要求）。
- [ ] 不使用与 LGPL-3.0 不兼容的第三方代码（如需引入，先评审）。

## 4. 备注

- Mesa 的 MIT 许可与 LGPL-3.0 兼容；作为独立 `.so` 动态链接不改变 MobileGL 的许可义务。
- 若未来把插件拆分为独立仓库，建议沿用 LGPL-3.0-only，避免许可证碎片化。
