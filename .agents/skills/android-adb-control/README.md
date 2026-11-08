# Android ADB Control

让 Agent 通过 ADB 操控安卓手机：观察界面、执行一步、验证结果，再决定下一步。

An instruction-only Agent Skill for Android phone control via ADB.

作者：范凯 and Agent（Friday） · [MIT License](LICENSE)

## 用来做什么

- 打开 App、查找页面、点击、滑动、长按和输入文字。
- 查看授权范围内的界面信息、处理有限列表、复现测试操作。
- 准备消息草稿；明确授权后执行指定发送动作。

这是给已有 Agent 加载的技能，不是独立程序、MCP Server 或后台服务。基础操作只需 ADB 和已授权设备；自绘界面需要截图工具与视觉模型，中文输入可选 ADBKeyboard。

不用于绕过锁屏、验证码、支付验证或企业策略，也不提供无人值守监控、自动交易或无限重试。

## 适用 Agent

| Agent | 用户级目录 | 调用方式 |
|---|---|---|
| Pi Agent | `~/.agents/skills/android-adb-control/` | `/skill:android-adb-control` |
| Codex CLI / IDE | `~/.agents/skills/android-adb-control/` | `$android-adb-control` 或 `/skills` |
| Claude Code | `~/.claude/skills/android-adb-control/` | `/android-adb-control` |
| OpenCode | `~/.agents/skills/android-adb-control/` | 请求加载同名技能，由原生 `skill` 工具调用 |

这里指技能格式与加载方式兼容，不代表已在四个宿主上完成真机验收。Agent 必须能执行本机 ADB；需要视觉时，还必须能把截图交给模型。云端会话或远程容器不会自动获得本地 USB 设备。

## 安装

以下为 macOS / Linux 的 Bash 示例；Windows 请使用官方 Platform-Tools 并适配路径、引号和截图输出。

### 1. 准备 ADB

先检查，避免重复安装：

```bash
command -v adb
adb version
```

缺失时安装 [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)。macOS 可使用：

```bash
brew install --cask android-platform-tools
```

### 2. 安装技能

先审阅本仓库。Pi、Codex、OpenCode 可共用一份：

```bash
mkdir -p "$HOME/.agents/skills"
git clone https://github.com/robbin/android-adb-control.git \
  "$HOME/.agents/skills/android-adb-control"
```

只用 Claude Code 时，将目标目录改为 `~/.claude/skills/android-adb-control`。若已装共享版本，可给 Claude Code 建兼容链接：

```bash
mkdir -p "$HOME/.claude/skills"
ln -s "$HOME/.agents/skills/android-adb-control" \
  "$HOME/.claude/skills/android-adb-control"
```

目标已存在时先检查，不要覆盖。OpenCode 也会扫描 `.claude/skills`，兼容链接可能引起同名告警；确认最终加载路径，不要再安装第二份副本。

只想在项目内使用时，安装到项目的 `.agents/skills/android-adb-control/`；Claude Code 使用 `.claude/skills/android-adb-control/`。不要无意重复安装用户级和项目级技能。父项目已有 Git 时，按团队规范处理嵌套仓库或子模块。

安装后，Pi 执行 `/reload`；其他宿主若未发现技能，检查技能权限并重开会话。OpenCode 的技能不一定注册为同名斜杠命令。

### 3. 连接手机

在自己的手机上开启开发者选项和 USB 调试，用数据线连接，手动解锁并确认电脑的 RSA 授权，然后运行：

```bash
adb devices -l
```

设备应显示为 `device`。多个设备时明确指定目标；遇到 `unauthorized`、`offline` 或 Linux `no permissions`，先解决连接问题。

无线调试是可选项，见[连接与故障处理](references/troubleshooting.md)。不要将 ADB 端口暴露到公网。

## 使用

从低风险任务开始，按上表使用对应宿主的调用方式，例如 Pi：

```text
/skill:android-adb-control 打开系统设置首页，确认页面，不修改设置。
```

也可以用自然语言请求加载技能，再描述目标和边界：

```text
使用 android-adb-control，在指定测试联系人会话里输入“我稍后回复”，
停在发送前。不要点击发送，也不要按可能触发发送的回车。
```

```text
查看这个测试列表的前 20 个项目，只记录名称和可见编号。
同名项目不要直接合并；到 20 个或预算用完就停止。
```

默认最多 30 个设备状态变更动作、5 分钟，并为恢复操作预留预算。长任务提前约定有限预算和检查点。发送状态不明时不会自动重发。

## 安全与限制

- 只操作获授权的设备、Android 用户和 App。发送、购买、删除、安装、导出个人资料及修改安全/网络设置需要明确授权。
- 截图和 UI XML 可能含私人内容，交给云端模型前应确认数据与日志策略；不要提交到本仓库或公开 Issue。
- ADBKeyboard 的部分实现允许其他本地应用发送控制广播。优先用于可信测试设备，不用于凭据输入；使用前说明风险，结束后恢复本次改变的输入法状态。
- 默认不改休眠时间、不切换 VPN、不安装辅助 App。无法验证或恢复时停止并报告。
- 技能规则不是技术沙箱，仍需宿主权限控制与人工监督。

本版本已做文档审查和静态检查，尚未完成修订版真机端到端验收。不同 ROM、App、输入法版本和视觉链路仍需测试。

## 文档与检查

- [SKILL.md](SKILL.md)：Agent 执行入口。
- [ADB 命令参考](references/adb-commands.md)：设备／用户选择、输入、捕获和恢复。
- [故障处理](references/troubleshooting.md)：USB、OEM、无线连接与 VPN。
- [DroidClaw 蒸馏笔记](docs/droidclaw-notes.md)：脱敏后的设计背景与历史经验。
- [验证清单](docs/testing.md)：检查方法和真机验收步骤。

静态检查不连接手机，需要 Bash、Git 和 uv：

```bash
uv run --no-project python scripts/check_docs.py
```

更新前检查安装目录的本地修改，确认可接受上游变更后再运行 `git pull --ff-only`，然后刷新 Agent 会话。

## 来源与许可

方法受到 [DroidClaw](https://github.com/unitedbyai/droidclaw) 启发，本项目不是其官方产品或运行时，也不分发第三方 APK。

参考：[ADB](https://developer.android.com/tools/adb) · [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard) · [Agent Skills](https://agentskills.io/specification) · [Pi](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md) · [Codex](https://learn.chatgpt.com/docs/build-skills) · [Claude Code](https://code.claude.com/docs/en/skills) · [OpenCode](https://opencode.ai/docs/skills/)

本仓库采用 [MIT License](LICENSE)，作者为范凯 and Agent（Friday）。第三方项目遵循各自许可证。
