# archive —— 已归档的鸿蒙构建通道

本目录只存放**已被取代、不再参与构建**的历史工程，保留用于追溯，不要在此继续开发，
也不要让根构建或脚本引用这里的路径。

## `ohos-build/`

- **来源**：原仓根 `ohos-build/`，是一套独立的 CPF（Compose Multiplatform for OpenHarmony）
  Kotlin 构建，用于索引/编译 `ohosMain` 并产出 `libdengying.so` + `libdengying_api.h`。
- **为何归档**：审计 B-17 认定它是遗留 fusion 构建，与 `DengYingOhos` 采用的 skia 路线矛盾；
  两条鸿蒙构建线并存会让共享层源集与产物口径分裂。
- **取代者**：`DengYingOhos`（独立仓库/工程，skia 渲染路线）。

## `harmony/`

- **来源**：原仓根 `harmony/`，是 ArkTS/ArkUI 壳 + 手写 NAPI 桥的早期载体。
- **为何归档**：工程缺少 `compose.har` 与 thirdparty `.so`，无法完成 HAP 打包；
  继续维护只会积累无法验证的壳代码。
- **取代者**：`DengYingOhos` 的 `harmony/` 目录（含完整依赖与可打包配置）。

## 注意

- 历史验证记录（如 `docs/04`）里出现的旧路径属于当时现场，记录内容不改写；
  按当前仓库布局找文件时，请加上 `DengYing/archive/` 前缀。
- 归档目录不进入任何 Gradle/Hvigor 构建，也不应被脚本引用。
