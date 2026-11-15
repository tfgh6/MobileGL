# GitHub Actions（fork 可直接使用）

push 到 `dev` / `feat/panvk-android-plugin`、PR 或手动 `workflow_dispatch` 会触发：

| 工作流 | 内容 | 产物 |
|---|---|---|
| `plugin-debug.yml` | 从源码编译 debug APK（Espryt + Magma） | `MobileGL-*-Plugin-debug.apk`，约 26 MB |
| `apk.yml` | release APK（core Release 构建）+ retrace 矩阵 | `MobileGL-*-Plugin-release.apk`、retrace APK |
| `test.yml` | Linux 构建 + ctest + benchmark + retrace（仅 dev/Feat/* 分支） | — |

## 无需 secrets

release 构建在没有 `SIGNING_*` secrets 时使用仓库内的 `android-plugin/debug.keystore` 签名
（`app/build.gradle` 的 release 回退分支），因此 fork 也能产出可安装的 release APK；有 secrets
时仍用正式签名。正式分发前请提供 release keystore。

## 两个已修的环境问题

1. `android-actions/setup-android@v3` 因 Google 移除 SDK `tools` 包而失败
   （`Failed to find package 'tools'`）。工作流改为直接使用 runner 自带的 `sdkmanager`
   接受许可并安装 `ndk;27.3.13750724`。
2. `include/FastSTL` 的本地补丁不随子模块分发。CI 在 checkout 后执行
   `git -C include/FastSTL apply ../../patches/faststl/0001-*.patch`，保证与本地二进制一致。

## 取产物

Actions → 对应 run → Artifacts。也可用 CLI：

```sh
gh run list --repo tfgh6/MobileGL
gh run download <run-id> --repo tfgh6/MobileGL -D artifacts
```

## 本地等价构建

```sh
cmake -S . -B build_android_volk_loader -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DMOBILEGL_BUILD_TEST=OFF -DMOBILEGL_BUILD_BENCHMARK=OFF
cmake --build build_android_volk_loader --target MobileGL
```
