# TODO — 未来功能规划

> 本文档记录 Tumblr Downloader 计划实现的未来功能，附技术分析与实现方案。

---

## 功能一：关注博主频道 + 自动扫描下载

### 需求描述

用户可以配置关注的 Tumblr 博主频道，设置自动扫描间隔（如每 1 小时、每 6 小时、每天等），应用自动扫描这些博主的最新帖子并下载图片/视频，无需用户手动操作。

### 技术分析

#### 现有基础

- **TumblrParser** 已支持从帖子 URL 解析媒体内容（oEmbed + HTML 解析双通道）
- **DownloadService** 已实现前台服务 + 通知栏进度展示，基于 `ForegroundService` + 协程
- **AppDatabase (Room)** 已有 `DownloadHistoryEntity` 和 `CookieEntity`，可扩展新表
- 当前 `DownloadItem` 没有 `blogName` / `blogUrl` 字段，需扩展

#### 核心挑战：后台持续运行与自动触发

Android 对后台服务有严格限制（Doze Mode、App Standby、后台限制），仅靠前台服务无法在用户不活跃时长时间保活。需要以下方案之一或组合：

| 方案 | 原理 | 优缺点 |
|------|------|--------|
| **AccessibilityService** | 利用无障碍服务的长生命周期保活，系统几乎不会杀死 | ✅ 保活最稳定 ⚠️ Google Play 审核严格，需声明合理用途；F-Droid 无此限制 |
| **WorkManager + 定时任务** | 系统级调度，兼容 Doze Mode | ✅ 官方推荐 ⚠️ 间隔有下限（15 分钟），精确度一般 |
| **Foreground Service** | 当前已有，需用户保持应用前台 | ✅ 实现简单 ⚠️ 用户切到后台后可能被回收 |

**推荐方案：AccessibilityService（无障碍服务）为主 + WorkManager 为备**

- 无障碍服务提供持续保活能力，系统级长生命周期进程
- 通过无障碍服务内部的定时器触发扫描逻辑
- WorkManager 作为降级方案，在无障碍服务未启用时尝试定时唤醒
- F-Droid 分发不受 Play Store 政策限制，无障碍服务方案可行

#### 数据模型设计

```
-- 新增表：关注的博客
CREATE TABLE followed_blogs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    blog_name   TEXT NOT NULL UNIQUE,       -- 博主名称 (如 "nasa")
    blog_url    TEXT NOT NULL,              -- 博主主页 URL
    avatar_url  TEXT,                       -- 头像 URL
    added_at    INTEGER NOT NULL,           -- 添加时间戳
    enabled     INTEGER NOT NULL DEFAULT 1  -- 是否启用自动扫描
);

-- 新增表：自动扫描配置
CREATE TABLE scan_config (
    id              INTEGER PRIMARY KEY,
    interval_hours  INTEGER NOT NULL DEFAULT 6,   -- 扫描间隔（小时）
    enabled         INTEGER NOT NULL DEFAULT 0,    -- 全局开关
    last_scan_at    INTEGER,                       -- 上次扫描时间
    download_images INTEGER NOT NULL DEFAULT 1,    -- 是否下载图片
    download_videos INTEGER NOT NULL DEFAULT 1     -- 是否下载视频
);

-- 扩展 download_history 表
ALTER TABLE download_history ADD COLUMN blog_name TEXT;    -- 所属博主
ALTER TABLE download_history ADD COLUMN post_url TEXT;     -- 帖子原始链接
ALTER TABLE download_history ADD COLUMN tags TEXT;         -- JSON 数组，帖子标签
```

#### 实现步骤

1. **数据层**：新增 Room Entity（`FollowedBlogEntity`、`ScanConfigEntity`），扩展 `DownloadHistoryEntity`
2. **UI 层**：新增「关注频道」页面（列表 + 添加/删除/启用/禁用），新增「自动扫描」设置入口
3. **扫描逻辑**：实现 `BlogScanWorker`，遍历关注博主主页，对比已下载记录，提取新帖子的媒体 URL
4. **无障碍服务**：实现 `AutoScanAccessibilityService`，保活 + 定时触发扫描
5. **下载集成**：将扫描到的新媒体加入现有 `DownloadService` 队列
6. **WorkManager 降级**：当无障碍服务未启用时，通过 `PeriodicWorkRequest` 定时触发

#### 需要新增的权限

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- 无障碍服务声明 -->
```

```xml
<service
    android:name=".service.AutoScanAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

#### 注意事项

- 无障碍服务需要用户在系统设置中手动开启，需引导流程
- 扫描频率不宜过高，避免被 Tumblr 限流或封号
- 需复用现有 Cookie 登录态来访问私密帖子
- 扫描到已下载的媒体时需跳过（基于 `mediaUrl` 去重）

---

## 功能二：下载内容分类与 Tag 管理

### 需求描述

为已下载的图片和视频添加分类和标签功能：
- **按博主分类**：根据下载文件所属的 Tumblr 博主自动归类
- **按 Tag 显示**：展示图片/视频在原博客帖子中标注的 Tumblr tags
- 用户可以按分类和 tag 筛选、浏览已下载内容

### 技术分析

#### 现有基础

- **DownloadHistoryEntity** 已记录 `sourceUrl`，可从中提取博主名，但当前无 `blogName` 和 `tags` 字段
- **DownloadsFragment** 已有下载列表 UI，可扩展为分类视图
- **MediaViewerActivity** 已实现媒体查看，可扩展 tag 展示
- **TumblrParser** 解析帖子时已有 JSON payload，其中包含 tags 信息

#### Tumblr 帖子 Tag 数据来源

Tumblr帖子的 tags 在 SSR 渲染的 `__INITIAL_STATE__` 或 `application/json` 脚本块中以 JSON 数组形式存在。需在解析阶段提取并存储。

示例（从 Tumblr 页面 JSON 中）：
```json
{
  "tags": ["photography", "landscape", "nature"],
  "blogName": "nasa"
}
```

#### 数据模型设计

```
-- 新增表：标签
CREATE TABLE tags (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE
);

-- 新增表：下载与标签的多对多关系
CREATE TABLE download_tags (
    download_id TEXT NOT NULL REFERENCES download_history(id) ON DELETE CASCADE,
    tag_id      INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (download_id, tag_id)
);

-- 扩展 download_history（与功能一共享）
-- blog_name TEXT  -- 已在功能一中添加
-- tags TEXT       -- 已在功能一中添加（冗余 JSON，便于查询）
```

#### 实现步骤

1. **解析增强**：在 `TumblrParser` 中提取帖子的 `tags` 和 `blogName`，写入 `DownloadItem`
2. **数据层**：新增 `TagEntity`、`DownloadTagCrossRef`，新增 `TagDao`
3. **下载时写入**：下载完成时自动将 tags 写入关联表，`blogName` 写入 `download_history`
4. **UI — 分类视图**：
   - Downloads 页面顶部增加 Tab/Chip 按博主分组显示
   - 支持按博主名筛选
5. **UI — Tag 浏览**：
   - 媒体详情页底部展示 tags（Chip 列表）
   - 点击 tag 跳转到该 tag 的所有下载内容列表
   - 新增「标签」导航入口，展示所有已用 tag 及计数
6. **搜索增强**：支持按 tag 名搜索已下载内容

#### UI 设计要点

```
导航结构变化：
  下载 (Download)     ← 现有，保持不变
  下载列表 (Downloads) ← 扩展：增加博主 Tab 筛选
  标签 (Tags)         ← 新增：所有 tag 列表 + 计数
  设置 (Settings)     ← 增加：自动扫描配置入口
```

#### 注意事项

- `blogName` 从 `sourceUrl` 中提取（如 `https://blog-name.tumblr.com/post/123` → `blog-name`），需兼容自定义域名格式
- Tags 需做大小写不敏感处理（Tumblr tag `Photography` 和 `photography` 视为同一个）
- 大量 tag 的情况下需要考虑数据库查询性能，可对 `tags.name` 建立索引
- 已有下载历史的迁移：对缺少 `blogName` 的旧记录，从 `sourceUrl` 反向解析补充

---

## 实施优先级建议

| 优先级 | 功能 | 理由 |
|--------|------|------|
| P0 | 功能二：分类与 Tag | 改动较小，纯 UI + 数据层扩展，不涉及系统级权限，用户体验提升明显 |
| P1 | 功能一：自动扫描下载 | 涉及无障碍服务、后台保活等复杂系统交互，建议在分类功能稳定后再实现 |

---

## 通用注意事项

- 两个功能共享 `blogName` 字段的扩展，建议在同一个数据库迁移中完成
- 数据库版本升级需从 `version 1` → `version 2`，编写 `Migration(1, 2)` 并处理已有数据
- 无障碍服务方案仅适用于 F-Droid 分发；如上架 Google Play 需额外审核材料
- 所有新增 UI 需同时支持中英文（`strings.xml` / `strings_zh.xml`）
