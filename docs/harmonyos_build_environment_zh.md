# HarmonyOS 可复现构建环境

仓库只固定工具版本和 ABI，不提交某台电脑的 DevEco Studio 或 OpenCV 绝对路径。首次构建时复制 `config/harmonyos-local.example.json` 为 Git 忽略的 `config/harmonyos-local.json`，并填写本机路径。本机约定把大型工具、SDK、模拟器、缓存与 OpenCV 构建放在 D 盘。

配置项：

- `toolchainRoot`：DevEco Studio 根目录，必须包含配套 SDK、Hvigor、Node 和 JBR；
- `opencv.sourceRoot`：固定 OpenCV 4.13.0 源码目录；
- `opencv.buildRoots.arm64-v8a`：HarmonyOS ARM64 静态库构建目录；
- `opencv.buildRoots.x86_64`：HarmonyOS 模拟器静态库构建目录。
- `tools.ffprobePath`：可选的 MP4 验收工具路径，仅录屏媒体验收脚本需要。

CI 或临时环境可以用下列变量覆盖本机 JSON，而不改仓库文件：

```text
HARMONY_TOOLCHAIN_ROOT
HARMONY_OPENCV_SOURCE
HARMONY_OPENCV_BUILD_ARM64
HARMONY_OPENCV_BUILD_X64
HARMONY_FFPROBE_PATH
```

运行 `npm.cmd run harmonyos:doctor` 会校验 DevEco Studio、SDK、Hvigor、ohpm、Node、Java、OpenCV 源码以及两个 ABI 的静态库。构建脚本把解析后的路径导出给 CMake；CMake 自身不包含本机 D 盘 fallback，缺少配置时会明确失败。

Debug 和 Release 的 standard/replay 使用不同 module target，并在每次变体构建前强制清理 Native/ArkTS 增量缓存，防止另一变体的源码、符号或页面混入。
