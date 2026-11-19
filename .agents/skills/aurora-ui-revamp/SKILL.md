---
name: "aurora-ui-revamp"
description: "Aurora Glass 界面改版的三阶段执行蓝本：隐形铺垫 → 原子切换 → 打磨，含分支/同步/发布纪律与回归测试清单"
version: 1
created: "2026-11-18"
updated: "2026-11-18"
---

## When to Use

- 开始或推进 Aurora Glass 界面改版（用户说「开工 / 继续界面改版 / 上 aurora」）
- 改版期间遇到分支同步、发布冻结、回归范围的决策
- 改版中途中断后需要恢复上下文

设计定稿与 Token 规范见 `docs/design/README.md`（13 张双主题设计稿 + `overview.png`，生成器 `docs/design/gen_design_svg.py`）。本 skill 只管**怎么落地**。

## Background

- 栈：纯 Views + ViewBinding + Material 1.11，**minSdk 30**（RenderEffect 真模糊需 31+，30 降级）、不迁移 Compose
- 三态边界：**线上 = tag 发布物**；master 开发态割裂无所谓；纪律是「切换窗口内不打 tag」，紧急发布从**最后一个 tag** 拉 `release/x.y.z` 分支走（v1.0.8 已验证此流程）
- 冲突热点文件：`MainActivity.kt`、`fragment_download.xml`、`fragment_downloads.xml`、`item_download.xml`、`item_blog_job.xml`、`strings.xml`（末尾追加型，琐碎）

## Procedure

### Phase 0 · 隐形铺垫（直上 master，2~3 个短 PR，零割裂）

纯新增资源，**不被任何 layout 引用**，界面纹丝不动：

- **P0-1 设计 token**：`values/` + `values-night/` 双套色板（渐变 `#FF5C8A→#8B5CF6`、ink/secondary/accent、玻璃卡 fill+stroke）、shape appearance、字重
- **P0-2 组件层**：玻璃卡/渐变按钮样式、光斑背景 drawable（layer-list + radial gradient）、**渐变进度条 + 环形进度两个自定义 View（约 200 行）**、底栏胶囊指示器结构（约 150 行）
- **P0-3 素材**：设计所需新矢量图标、新增 strings

每个 PR 的门槛：`./gradlew testDebugUnitTest lintDebug assembleDebug` 全绿 + `grep` 确认新样式**零引用**（隐形成立）+ 冒烟看一眼界面未变。**不跑设备行**（无行为变化）。

### Phase 1 · 原子切换（一条短分支，一次可见变更，4~6 天窗口）

1. **工作区隔离**：用 worktree 开工（`worktree_create` name=`aurora-ui`），主工作区留 master 应对日常杂活；分支 `feature/aurora-ui`
2. **范围**（全部换用 Phase 0 已铺好的样式）：
   - 16 个 layout 逐屏换肤（对照 `docs/design/svg/` 逐屏核对）
   - **信息架构（✅ 已拍板 2026-11-19，动 `MainActivity` 前以此为准）**：抽屉移除后 —— 首页右上**头像圆圈 → 账号底部浮层**（用户名 / 登录态 / 退出登录）；**`使用教程`、`关于` 作为设置页列表行**；**`关于` 页按设计稿完整升级**（GitHub / F-Droid / Ko-fi 三链接 chip + Ko-fi 支持卡，内容不缩水）
   - 顶部 TabLayout+ViewPager2 保留结构，指示改设计稿样式；或按设计稿评估是否需要 Fragment 切换
   - `MediaPreviewDialog` → `BottomSheetDialogFragment`（复用现有状态/回调，只动 UI 壳；**旋转行 3.7 是高危回归点**）
   - 设置页加 Theme 分段选择器（`settings_theme` 已存在，接 `AppCompatDelegate`）
3. **窗口纪律**：
   - **冻结打 tag**（不发版）；真要紧急发版 → 从最后一个 tag 拉 release 分支摘修复
   - 期间 master 若进功能 PR → **每周** `git merge origin/master` 进本分支（只 merge 不 rebase，历史已推送）；冲突当场解决不积压
   - 若 master 需要持续高频进件 → 降级为集成分支变体：开 `aurora-integration`，小 PR 短命合入它，最后 integration→master 原子合并
4. **测试节奏**：每换完一屏跑**受影响行**（见下表），合并前跑**全量 163 行 + lint 0**
5. **合并**：单个原子 PR → master，CI 绿后删分支

受影响行速查（完整定义见 `docs/test-plan.md`）：

| 工作区 | 需重跑的设备行 |
|---|---|
| 导航重构（抽屉→底栏） | 1.3（教程入口）、8.8（退出登录）、9.1（头像/退出）、8.9（语言切换后 UI） |
| 选择器→浮层 | 2.1/2.2/2.6/2.9、2.11~2.14（各入口）、3.5~3.9（**3.7 旋转**）、4.2~4.5（开关门控） |
| 类型对话框 | 4.3、5.1、5.3、5.13 |
| 设置换肤 | 1.6、1.11、8.5、8.7、Theme 新增行 |
| 下载列表/博客卡 | 5.x 扫描卡、6.x 状态行、1.7 空状态 |
| 其余屏（引导/关于/登录/查看器） | 1.1/1.2、9.2、2.6 登录链路、媒体查看器相关 |

### Phase 2 · 收尾打磨（切完后的小 PR，回归短命模式）

- 可选真模糊：API 31+ `RenderEffect` / BlurView 库；**API 30 降级为提高不透明度**（设计本身不依赖模糊）
- 动效抛光（激活胶囊、页面过渡）
- **测试计划文案更新**：1.3 / 8.8 / 9.1 等含「侧边栏/抽屉」的行改为底部导航流程（双语两份文件）
- 下个 tag 前全量回归；发布走 `release-apk` + `fdroid-release-check` 既有 skill

## Pitfalls

- Phase 0 的「隐形」要**用 grep 验证**（新样式零引用），否则号称隐形实际已割裂
- 只 merge 不 rebase 已推送分支；同步按周做，不要攒到最后一天
- 窗口内不打 tag；F-Droid metadata 只在新 tag 后动（见 `fdroid-release-check`）
- 对话框改浮层后，**旋转（3.7）、入口多路复用（2.11~2.14）、开关门控（3.8/4.4）** 是三个历史验证过的回归高发点
- 双主题都要看：light/dark 各自跑一遍受影响行的截图核对（对照 `docs/design/png/`）
- 换肤不动逻辑：adapter/ViewModel/解析层保持原样，diff 里出现业务逻辑变更要警惕
- 测试计划是双语双文件（`test-plan.md` / `test-plan_zh.md`），改文案要两边同步

## Verification

- Phase 0：CI 绿 + 新样式零引用 + 冒烟界面未变（每个 PR）
- Phase 1 合并前：全量 163 行 0 失败 + `lintDebug` 0 + 两台设备（sharp / Pixel 3）双主题目视对照设计稿 + CI 绿
- Phase 2：全量回归绿 + 测试计划文案已更新 + 无 tag 提前泄漏
- 终态：master 上单次合并呈现完整新界面；线上版本在合并并发版前始终为旧 UI
