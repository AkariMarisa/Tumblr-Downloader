# 为 Tumblr Downloader 贡献代码

[🇬🇧 **English**](CONTRIBUTING.md)

首先，感谢您抽出时间为本项目贡献！🎉

无论您是修复 Bug、添加功能、改进文档还是回答问题，每份贡献都弥足珍贵。本指南说明项目的组织方式，以及如何让您的改动尽可能顺畅地被合并。

## 目录

- [行为准则](#行为准则)
- [贡献方式](#贡献方式)
- [快速上手](#快速上手)
  - [环境要求](#环境要求)
  - [构建应用](#构建应用)
  - [运行测试](#运行测试)
- [项目结构](#项目结构)
- [编码规范](#编码规范)
- [分支与提交规范](#分支与提交规范)
- [报告 Issue](#报告-issue)
- [提交 Pull Request](#提交-pull-request)
- [多语言](#多语言)
- [有疑问？](#有疑问)

## 行为准则

本项目以[贡献者行为准则](CODE_OF_CONDUCT_zh.md)（[English](CODE_OF_CONDUCT.md)）发布。参与本项目即表示您同意遵守其中的条款。

## 贡献方式

- **报告 Bug** —— 提交 Issue，附上清晰的复现步骤（见[报告 Issue](#报告-issue)）
- **提出功能建议** —— 描述您想解决的问题，而不只是您心中预设的解决方案
- **修复 Bug / 实现功能** —— Fork 仓库、创建分支并提交 Pull Request
- **改进文档** —— 本指南、README 或其他文档
- **参与本地化** —— 帮助保持英文与中文内容同步
- **参与代码审查** —— 有实质内容、彼此尊重的 Review 永远受欢迎

## 快速上手

### 环境要求

| 依赖 | 版本 |
|---|---|
| JDK | 17+ |
| Android Studio | Hedgehog (2023.1.1) 或更新 |
| Gradle | 8.4+（内置 wrapper，无需系统安装 Gradle） |
| Android SDK | platform 34 |
| 设备 / 模拟器 | Android 11（API 30）或更新 |

仓库内置 Gradle wrapper，无需系统安装 Gradle 即可构建。

### 构建应用

```bash
# 克隆您的 Fork
git clone https://github.com/<your-username>/Tumblr-Downloader.git
cd Tumblr-Downloader

# Debug 构建（输出：app/build/outputs/apk/debug/app-debug.apk）
./gradlew assembleDebug

# 或使用辅助脚本（为本地构建自动配置 JAVA_HOME / ANDROID_HOME）
./scripts/build_local.sh

# 安装到已连接的设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 运行测试

```bash
./gradlew test
```

本地单元测试在 JVM 上运行（JUnit4 + Robolectric + Room in-memory），覆盖 Room DAO、SharedPreferences → Room 迁移，以及模型和下载服务逻辑。请确保您的改动不会破坏现有测试，并在可行的情况下为新行为补充测试。

## 项目结构

本应用采用**单 Activity + Fragment** 架构，遵循 **MVVM** 模式：

```
app/src/main/java/io/github/akarimarisa/tumblrdownloader/
├── MainActivity.kt / MainViewModel.kt   # 单 Activity 宿主 + 共享 ViewModel
├── model/                               # DownloadItem、DownloadStateManager（顺序队列）
├── service/DownloadService.kt           # 负责实际下载的前台服务
├── ui/                                  # 各 Fragment/Activity：下载、登录、媒体、设置…
└── utils/                               # 解析器、Cookie/账户存储、历史/SAF 工具
```

完整文件清单与架构图见 [README](README_zh.md)。

## 编码规范

- **语言**：Kotlin，遵循[官方 Kotlin 代码风格](https://kotlinlang.org/docs/code-style-guide.html)（`gradle.properties` 中 `kotlin.code.style=official`）
- **架构**：MVVM，单 Activity + Fragment，共享 `MainViewModel`
- **UI**：AndroidX + Material Components + ViewBinding
- **异步**：Kotlin 协程 —— 所有队列状态变更都经由 `DownloadStateManager` 的单一命令通道
- **存储**：下载历史与 Cookie 使用 Room（已从 SharedPreferences 迁移）
- **风格**：与周边代码保持一致 —— 4 空格缩进、命名统一、提交聚焦。（以 Kotlin 官方风格为基线；CI 目前尚未强制 `ktlint`/`detekt`，请保持 diff 整洁）

## 分支与提交规范

分支命名遵循 `<类型>/<描述>`，例如：

- `feature/notification-actions`
- `fix/concurrent-downloads`
- `docs/fdroid-badge`

提交信息使用约定式前缀：`feat:`、`fix:`、`docs:`、`chore:`、`refactor:`、`test:`。保持每个提交小而聚焦、只做一件事。

## 报告 Issue

提交 Issue 前，请先搜索已有 Issue，避免重复。

**Bug 报告**应包括：

- 应用版本（设置 → 关于）以及使用的 Android 版本 / 设备型号
- 使用的 URL、期望的结果与实际结果
- 尽可能具体的复现步骤
- 相关日志或截图

**功能建议**应描述您想解决的问题以及设想的实现方式。能说明取舍与替代方案的提案尤其受欢迎。

## 提交 Pull Request

1. Fork 仓库并从 `master` 创建分支（见[分支与提交规范](#分支与提交规范)）
2. 进行修改 —— 保持改动小而聚焦
3. 本地构建并测试：

   ```bash
   ./gradlew assembleDebug test
   ```

4. 推送分支，并向 `master` 发起 Pull Request。CI 会为每个 PR 构建 debug APK。
5. 在 PR 描述中说明**改了什么**与**为什么**，并关联相关 Issue。

**PR 检查清单**

- [ ] 基于最新的 `master` 创建分支
- [ ] 构建通过（`./gradlew assembleDebug`）
- [ ] 测试通过，并视情况补充新测试（`./gradlew test`）
- [ ] 遵循现有代码风格与项目结构
- [ ] 涉及用户可见行为变化时同步更新了文档

## 多语言

本项目提供英文与简体中文的界面和文档，需要保持同步：

- 界面文案：`app/src/main/res/values/strings.xml` ↔ `app/src/main/res/values-zh/strings.xml`
- 文档：`docs/README.md` ↔ `docs/README_zh.md`、`CONTRIBUTING.md` ↔ `CONTRIBUTING_zh.md`、`CODE_OF_CONDUCT.md` ↔ `CODE_OF_CONDUCT_zh.md`

## 有疑问？

- 请提交 [Issue](https://github.com/AkariMarisa/Tumblr-Downloader/issues) —— 任何问题都不算太基础
- 当前进度与路线图见 README 的[待办](README_zh.md#待办)章节

再次感谢您的贡献！💜