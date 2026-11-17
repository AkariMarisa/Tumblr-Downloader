# Test Plan

Every user-visible flow of the app, grouped by area. Status legend:

* ✅ **automated** — covered by a JVM unit test (`./gradlew testDebugUnitTest`, 212 tests)
* 📱 **verified** — executed by hand on a real device (SHG01, Android 14, Chinese UI)
* ⬜ **open** — designed here, not executed yet
* ❌ **failed** — executed, did not meet the expected behaviour (tracked as a regression row)

Design rule: expectations are written from the user's perspective first — a clear message beats silence, and an honest duplicate download beats a silent dedupe the user cannot explain (see 7.10).

Run the automated suite with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
Device cases are scripted with `adb` (`uiautomator dump` for assertions, `logcat` for behaviour).

---

## 1. Build, install & first run

| ID | Case | Expected | Status |
|---|---|---|---|
| 1.1 | Fresh install → launch | Onboarding opens; 5 pages (clipboard, login, downloads, picker, blog cards); dots match; Skip/Get started work — 复验：Pixel 3（Android 15）全新安装，引导 5 页正常、跳过可用 | 📱 |
| 1.2 | Re-launch after onboarding | Onboarding does not open again | 📱 |
| 1.3 | Sidebar → 使用教程 | The same 5 pages replay with a single 关闭 button; leaving does not reset onboarding | 📱 |
| 1.4 | `./gradlew lintDebug` | 0 errors | ✅ |
| 1.5 | Release build (`assembleRelease`) installs over debug-signed build with the release key | Install succeeds, data preserved, no crash | 📱 |
| 1.6 | Fresh install → default settings | Auto-download off, clipboard auto-detect on, default download directory | 📱 |
| 1.7 | Fresh install → empty state | No history rows, no blog cards, no leftover state | 📱 |
| 1.8 | Fresh install → first clipboard check | Detected immediately (dedupe key empty) | 📱 |
| 1.9 | Upgrade from v1.0.7 (has history) → launch | No crash; history rows survive the Room 1→2 migration | 📱 |
| 1.10 | After upgrade → old files & duplicates | Files still on disk; pasting the old link still reports "already in the list" | 📱 |
| 1.11 | After upgrade → settings preserved | Auto-download switch, clipboard detect, language, theme all kept | 📱 |
| 1.12 | After upgrade → clipboard dedupe key | Old clipboard link does NOT re-trigger; 清空自动检测缓存 makes it detect again | 📱 |
| 1.13 | After upgrade → onboarding | Does not show again (flag carried over from v1.0.7) | 📱 |
| 1.14 | After upgrade → manage migrated history | Deleting a row and 清空已完成 both work on pre-upgrade data | 📱 |

## 2. Link input & parsing

| ID | Case | Expected | Status |
|---|---|---|---|
| 2.1 | Paste a single-post URL → 下载媒体 | Picker opens (switch off) / queues directly (switch on) | 📱 |
| 2.2 | Paste a multi-image post | Picker lists every fresh file with thumbnails, `已选 n/n` | 📱 |
| 2.3 | Paste a post whose media is already queued | Toast says where it is (`属于博客「x」` for blog rows) | ✅ 📱 |
| 2.4 | Paste an invalid URL | `无效的 Tumblr 链接` toast, no crash | 📱 |
| 2.5 | Paste a non-Tumblr URL | Invalid-URL toast | 📱 |
| 2.6 | Post that needs login | Login page opens; after login the parse retries and keeps the picker intent — 全链路实测：登出→ntn888 弹登录页→登录→按「完成」→自动重试→选择器自动弹出；redirect 目标识别补了 slug 规范化匹配（免手动完成）；追加素材 alexlatex（浏览器登出态打不开的成人帖）在应用登出态仍直接解析成功（单帖 2 图、博客扫描 238/8596）→ 应用取数路径比浏览器宽，真正的墙内素材仍未命中（登录页链路以 ntn888 那轮为准） | 📱 |
| 2.7 | Adult-content interstitial | Relaxed JSON parse or login fallback, never a silent empty result — 素材未触发拦截页（登录/登出均直接解析成功），需真正出现年龄确认页的帖子；alexlatex 再测：登出态单帖 + 博客扫描均直接成功（feed 238/8596），拦截页在应用解析路径下仍未复现；「不静默、回退登录」分支已由 ntn888 那轮实证；**长期挂起**（用户决定）—— 待找到应用内真正触发年龄确认页的素材再恢复测试 | ⬜ |
| 2.8 | Video post | Video row appears with type 视频, downloads as `.mp4` — **实测（主手机，qqchurch 视频帖分享）**：选择器 `已选 1/1 mediaType=视频` → 行「已完成 100%」→ 单文件 `qqchurch-482a589d….mov`（按源正确扩展名，mtime 09:03、**无 `(N)` 后缀 = R32 覆写再验证**）；`.mp4` 路径由批量行 `sexybeauty072-tumblr_t326egjHXf1vc0ove.mp4` 实证（扩展名跟随源，故 `.mov`/`.mp4` 均属正确） | 📱 |
| 2.9 | GIF post (`.gif` / `.gifv` / extensionless) | Picker groups it under GIF; saved file ends in `.gif` | ✅ 📱 |
| 2.10 | `.pnj` high-quality image | Saved as `.png` | ✅ |
| 2.11 | Share intent from another app (post) | Picker opens (switch off) | 📱 |
| 2.12 | Share intent (blog URL) | Type dialog opens (switch off) / scan starts (switch on) | 📱 |
| 2.13 | Clipboard: copy post link → switch back | Picker opens, field filled, Download tab selected | 📱 |
| 2.14 | Clipboard: same link again after 跳过 | Picker opens again (clip-description timestamp) | 📱 |
| 2.15 | Clipboard: copy the URL that is already in the input field | No re-trigger (#19) | ✅ 📱 |
| 2.16 | Clipboard: return from the media viewer with an unchanged link | Nothing happens (last-URL fallback) | 📱 |
| 2.17 | Clipboard auto-detect disabled in settings | No detection at all | 📱 |
| 2.18 | Cold start with a Tumblr link in the clipboard | Detected on launch too — the last-URL dedupe still blocks links that were handled before | 📱 |
| 2.19 | Chrome copy → switch back (same link re-copied) | Detected again: the clip description carries a per-copy timestamp (#1) | 📱 |
| 2.20 | Tumblr app copy → switch back | Detected | 📱 |
| 2.21 | Chrome share → the app | Type dialog opens; the stale clipboard is ignored for 5s (no second flow) | 📱 |
| 2.22 | Tumblr app share → the app | Picker opens (even for a one-file post); no clipboard conflict | 📱 |
| 2.23 | Share text with surrounding words / several URLs (post) | The first Tumblr URL in the text is used (`firstTumblrUrl`), rest ignored | 📱 |
| 2.24 | Share text with surrounding words (blog) | Blog flow still starts (type dialog) — 修复后实测：「推荐这个博客 https://www.tumblr.com/blog/akari-test-blog 空空如也」→ 类型选择弹窗（`findBlogUrlInText` 从文本提取后路由，R31） | 📱 |
| 2.25 | `tmblr.co` short link | Field path shows the invalid-URL toast ✓; share path must not be a silent no-op (R28) — 修复后：短链被识别 → 网络跟随重定向 → 失败给本地化 Toast「无法解析短链接。」（真机实拍）；成功腿待真实 tmblr 链接 E2E | 📱 |
| 2.26 | URL with `?source=` / `utm_*` params | Parsed normally (`sourceParamRegex` strips Tumblr's own param) — 实测：带 `utm_source/utm_medium/ref` 的分享直接弹选择器（已选 4/4） | 📱 |
| 2.27 | Deleted post (404) / server error | Explicit error message, no silent empty result, no crash — **修复后实测（Pixel 3，假删帖）**：Toast「该链接未识别到可下载媒体」；无载荷页另有新映射「该帖已被删除或不存在。」（parse_post_not_found），无选择器、无崩溃；单测覆盖无容器页 scope 归零 | 📱 |
| 2.28 | Text-only post with no media | Friendly "no media" message instead of an empty picker — 分层验证：单测（纯文字页含 og:image → 提取为空、封面不被抓）+ 真机（Empty 结果 → 友好 Toast「该链接未识别到可下载媒体」、无空选择器、无崩溃；假删帖同路径触发）；活素材三连试均偏（死帖/登录重定向/内嵌 GIF）已记录 | 📱 |

## 3. Pre-download picker

| ID | Case | Expected | Status |
|---|---|---|---|
| 3.1 | Row checkbox | Toggles one file, summary updates | 📱 |
| 3.2 | 图片/视频/GIF chip | Selects all of that type, count in label | 📱 |
| 3.3 | 清空 / 全选 | Clears/selects everything; 下载（0）disabled | 📱 |
| 3.4 | 下载（n） | Only the selected n are queued; list switches to 下载列表 | 📱 |
| 3.5 | 跳过 | Nothing queued, dialog closes | 📱 |
| 3.6 | Single fresh file | Picker still opens with `已选 1/1` (switch off) | ✅ 📱 |
| 3.7 | Rotation while the picker is open | Dialog re-opens with the same selection | 📱 |
| 3.8 | Switch on | No picker, everything queued immediately | 📱 |
| 3.9 | Already-downloaded file in the picker | Not listed (dedupe against non-FAILED rows) | ✅ |

## 4. Auto-download switch (设置 → 下载)

| ID | Case | Expected | Status |
|---|---|---|---|
| 4.1 | Default state after install | Off | ✅ 📱 |
| 4.2 | Off + post (paste/share/clipboard/login-retry) | Picker for every entry point | ✅ 📱 |
| 4.3 | Off + blog | Type dialog (图片和视频/仅图片/仅视频) | 📱 |
| 4.4 | On + post | Queued directly, no dialog | 📱 |
| 4.5 | On + blog | Scan starts immediately, no type dialog | 📱 |
| 4.6 | Toggle survives restart | Value persisted | 📱 |

## 5. Blog batch download

| ID | Case | Expected | Status |
|---|---|---|---|
| 5.1 | Blog URL → 下载媒体 (switch off) | Type dialog appears before anything is queued | 📱 |
| 5.2 | Choose 仅图片 | Only image rows are enqueued | 📱 |
| 5.3 | Long blog confirm | Dialog appears **before** the first media is queued (#15); verified on device twice (41748- and 8573-post blogs at threshold 5000) | ✅ 📱 |
| 5.4 | Long blog → 取消 | No rows queued, card ends CANCELLED (已扫描 0 帖 · 0 个媒体) | ✅ 📱 |
| 5.5 | Card while scanning | Stats `已扫描 x/y 帖 · n 个媒体`, bar shows media progress, summary `已下载 a/b 个媒体` (#8, #17) | 📱 |
| 5.6 | Card buttons | 详情/暂停/移除 always visible; 开始 when idle (#9) | 📱 |
| 5.7 | Card 暂停 → 开始 | Scan and downloads stop/start together | 📱 |
| 5.8 | Card 移除 (huge queue) | No crash/ANR; unfinished rows purged, completed kept (#13) | 📱 |
| 5.9 | Detail 全部暂停 → back | Card shows 已暂停 + 开始 (not 解析中) (#16) | 📱 |
| 5.10 | Detail 全部开始 → back | Card shows parsing/downloading again | 📱 |
| 5.11 | Same blog twice | One card, moved to the top, progress kept | ✅ 📱 |
| 5.12 | Remove card → re-add | Old completed files are counted by the new card (#10) | ✅ 📱 |
| 5.13 | Detail blocked while the long-confirm is pending | Modal confirm blocks the 详情 tap (no navigation); the toast path is only reachable when no dialog is up (#6) | 📱 |
| 5.14 | Back-pressure: queue > 3000 rows | Scan waits for downloads; no ANR (#12) | 📱 |
| 5.15 | Blog output folder | Files land in `TumblrDownloader/<blog>-<hash>/` (#14) | 📱 |
| 5.16 | Orphan rows (card gone) | Hidden from the main list; counted by the next card of that blog; clear-all takes them (#18, #24) | 📱 |
| 5.17 | Interruption (process death) → card 未完成 → 开始 | Resumes from the persisted cursor (410 → 598 → 735 posts); resume re-asks the long-blog confirm first (in-memory gate) | 📱 |
| 5.18 | Network drops mid-scan | Card goes BLOCKED with an error code; 开始 after reconnect resumes from the cursor — **修复后复测通过**：断网 18 秒内 `feed giving up after 4 IOExceptions → network BLOCKED`，卡片红字「出错」+「网络请求失败，请检查网络后重试」，游标 1513/8632 持久化；恢复网络 → 暂停 → 开始 → **从游标续扫**（pages 91→121 持续推进） | 📱 |
| 5.19 | Two blogs scanning at the same time | Two independent cards, counters and pause/remove never cross | 📱 |
| 5.20 | Empty blog (0 posts) / password-protected blog | Ends or errors cleanly, no hang, no crash — 实测真·空博客（akari-test-blog，0 帖）：类型选择 → 扫描 → 已完成 · 已扫描 0 帖 · 0 媒体，无崩溃；URL 为 /blog/<name> 形式，顺带修复解析（R38）；加密博客变体实测（akari-test-blog-pass）：卡片「出错 · 已扫描 0 帖 · 0 媒体」干净收场、不挂起不崩溃（仅显示「出错」未注明是密码墙，可读性可再进一步） | 📱 |

> Note: 5.3/5.4 gate on ≥5000 posts (the lowest selectable threshold); no reachable blog is that large, so the on-device path is covered by unit tests only.

## 6. Queue & row actions

| ID | Case | Expected | Status |
|---|---|---|---|
| 6.1 | Row 暂停/继续 | Status and progress switch; partial file resumes | 📱 |
| 6.2 | Row 删除 (red ✕) | Row removed, file kept; re-adding the link does not create a `(1)` copy (#4) | 📱 |
| 6.3 | 全部暂停 / 全部继续 (toolbar) | Whole queue pauses/resumes | 📱 |
| 6.4 | Clear completed | Completed standalone **and orphan** rows are removed; a live card's rows stay; empty state returns | 📱 |
| 6.5 | Failed row → 续 | Retries from scratch without removing it first — **已用 ENOSPC 场景实测通过**：压盘至满 → 行「下载失败：…已重试 3/3 次。可手动重试。」→ 释放空间后点「续」→ 从头重试完成、无需先删除 ✓；断网路径按设计改为秒级「已暂停 + 等待网络连接…」（见 6.6），不再依赖服务器 404/5xx 素材 | 📱 |
| 6.6 | WiFi drops mid-download | Active rows fail after 3 retries, queued rows pause, no auto-resume — 实测（修复后）：断网 10 秒内**双守卫**触发（`post-error network check → PAUSED` + `network guard → PAUSED`），活动行转「已暂停」并携带可读消息「等待网络连接…」；这是有意设计（秒级清晰暂停优于烧完 3 次重试才失败，符合 UX 规则），排队行保持排队、恢复后不自动续（符合本条预期）。昨日的「零日志零提示挂起」实为 R43 主线程 ANR 冻结所致 —— 日志与消息现均已可见 | 📱 |
| 6.7 | Concurrency setting (1–4) | At most N rows download at once — verified at N=2 via two overlapping in-flight requests in the log | 📱 |
| 6.8 | Rate limit | Speed stays under the configured cap | 📱 |
| 6.9 | Double-tap 下载媒体 / paste the same link twice quickly | Only queued once (`isParsing` guard + dedupe); second tap absorbed by the loading overlay, single toast | 📱 |
| 6.10 | Storage full mid-download (ENOSPC) | Row FAILED with a readable error; no 0-byte leftovers, no crash — 实测：dd 压盘至约 8MB 空闲后下载 12.4MB 视频 → 行「下载失败：write failed: ENOSPC (No space left on device)。已重试 3/3 次。可手动重试。」；删填充文件后手动重试正常完成，无 0 字节、无该文件 pending 残留（注：系统在压力下自动清了约 1GB 缓存，不影响结论） | 📱 |

## 7. Files & storage

| ID | Case | Expected | Status |
|---|---|---|---|
| 7.1 | Single-post file name | `<author>-<mediaId>.<ext>` matching the queue title | ✅ 📱 |
| 7.2 | Blog file name | `<blog>-<mediaId>.<ext>` (no `post-` prefix) (#14) | 📱 |
| 7.3 | Extensionless URL | Renamed from the response `Content-Type` after download (#7) | ✅ |
| 7.4 | `.gifv` / uppercase `.GIF` / `?format=gif` | Saved as `.gif` | ✅ |
| 7.5 | Re-download of an existing file | Overwrites in place, no `(1)` / `_1` copy (#4) | 📱 |
| 7.6 | Custom directory (SAF) | Files go there; blog sub-folder created on demand | 📱 |
| 7.7 | Killed mid-download → resume | Partial file resumed via Range, no duplicate — 实测：写到 5,890,048 B 时 force-stop → 重启后行显示「下载失败 · 应用重启后状态失效，请重试」→ 手动开始 → 请求 offset=5890048、HTTP 206、剩余 6,466,118 B 精确续上，同一 pending 文件写完 → 已完成 12,356,166 B；续传本身不产生重复副本（最终文件名带 (N) 属清史重下问题，见 R32） | 📱 |
| 7.8 | MediaStore visibility | Files appear in the gallery/Downloads with the right `relative_path` | 📱 |
| 7.9 | Killed mid-write | No stale `.pending-*` files left behind in Downloads after restart/finish — 实测：force-stop 后 `.pending-…`（5,890,048 B）作为续传状态保留，重试完成后 `ls -la` 确认无残留；下载中删行同样会清掉其 pending（22:40 实测：删前存在 6,069,300 B、删后消失）；但目录里有 8 个 11–13 日的遗留 `.pending`（旧版本产物）永不清理 → 见 R39 | 📱 |
| 7.10 | The same media URL appearing in two posts | UX expectation (user): download it once per post (filenames differ by author) rather than silently dedupe — 实测：原帖行在队列时，转载帖同 media 被 URL 去重直接跳过（与预期相反）；**修复后复测通过**：原帖行已完成时转载帖仍弹选择器，下载得 `ntn888-<id>.jpg` 与 `fetishes-i-like3-<id>.jpg` 两行两文件（同 media、不同作者前缀、无 (N)），同帖重分享仍正确跳过 | 📱 |

## 8. Settings

| ID | Case | Expected | Status |
|---|---|---|---|
| 8.1 | Category menu → each sub-screen | Only that category's cards are shown | 📱 |
| 8.2 | Download directory (default/custom) | Label updates (当前下载目录：TumblrTest); new files go there; 恢复默认 works | 📱 |
| 8.3 | Language 中文/English | Whole UI switches, including notifications and toasts | 📱 |
| 8.4 | Theme light/dark/system | Applies and survives restart | 📱 |
| 8.5 | Image/video quality tiers | Picker/parse use the chosen rendition — same video: 最高可用 12.4 MB vs 480p 3.7 MB | 📱 |
| 8.6 | Blog scan interval / long threshold | Affects pacing and when 5.3 fires | 📱 |
| 8.7 | 清空自动检测缓存 | Next clipboard link is detected again | 📱 |
| 8.8 | Clear cache / logout | Cookies removed, login state reset—— **实测（主手机，用户配合登出）**：① 抽屉实拍「登录/未登录」、退出项消失（登录态重置 ✓）；② 行为级：2011 老帖短链 → Toast 且无选择器、同刻两条公开帖照常解析（`tmblr.co/ZEH9lx2jYaZ25`→已选 7/7、`tmblr.co/Z5-RFwSEjDJR`→已选 1/1）证明非网络/解析器故障 —— **复核修正**：该链接登录与否都返回 403 人机挑战（见 R44），故其 Toast 不能单独证明 cookie 状态，「cookies 已清」的主证据为 ①+③；③ 代码路径核对：`TumblrCookieStore.clear()` = Room `cookieDao.deleteAll` + `TumblrAccountStore.clear` + `CookieManager.removeAllCookies/flush`。「Clear cache」半边由 8.7（清空自动检测缓存）覆盖（应用内仅此一个清缓存入口）。**翻转复核（用户重登后）**：抽屉恢复「akarimarisa/在线」✓、CookieJar 实拍请求携带 `sid`+`logged_in`（会话恢复 ✓）、公开帖照常解析 ✓；同链接不再弹登录提示而改为诚实的站点验证提示（R44）——探针链接本身被 bot 防护拦（登录无关），非会话失败 | 📱 |
| 8.9 | Whole core flow while the UI is English | UI strings verified English (tabs/hint/buttons) and restored to system default after; toast/notification share the same resources | 📱 |

## 9. Sidebar, viewer, notifications

| ID | Case | Expected | Status |
|---|---|---|---|
| 9.1 | Login (WebView) → avatar/logout | Username shown, logout clears cookies — 侧栏显示账号名/头像 ✓；退出登录一键即清（无二次确认），清后接口/dashboard 均 logged-out ✓ | 📱 |
| 9.2 | About screen | Version, links open correctly | 📱 |
| 9.3 | Media viewer: image/video | Opens, swipe next changes image, swipe prev returns, back lands on the same row | 📱 |
| 9.4 | Notification while downloading | Posted with filename, progress bar and a 全部停止 action; lands in the **silent (低优先级) group** at the bottom of the shade — see R29 for the channel-importance note；实测动作：点「全部停止」→ 服务收到 ACTION_PAUSE_ALL（日志 21:54:13）→ 可见行全部「已暂停」、其后零新请求；静音分组根因确认为通道重要性=静音（R29），临时调「默认」后通知独立显示且动作可点，测完已调回 | 📱 |
| 9.5 | Notification after completion | Summary 「成功：X，失败：Y」 persists after the service stops, no stale 0% (#24) — Pixel 3（干净 Android 15 + 授权后）复测：格式 ✓、服务停止后留存 ✓（+60s 复查）、无 0% ✓；计数曾错误（R40：成功:3 而实际 4 行）→ **R40 修复后实拍「成功: 4，失败: 0」置顶显示 ✓**（留存机制同路径未变）；pause-all 路径发布汇总日志验证 ✓；主手机的 snooze 工件今日已清除（unsnooze）→ **主手机同样实拍「成功: 4，失败: 0」** ✓（R27 已结案） | 📱 |
| 9.6 | Android 13+ notification permission | POST_NOTIFICATIONS is declared but never requested at runtime — on a 13+ device the non-FGS summary would be silently blocked (R26) — 实测（Pixel 3, Android 15/SDK35 全新安装）：首启及全流程无任何权限弹窗、granted=false → 系统拦下**全部**通知：含 FGS 进度通知（numPostedByApp=0、app importance=NONE、通知栏完全不可见）；`pm grant` 后 id10001/id10002 立即可见（posted=1）→ 修复方向=运行时请求（R26）；**修复后复测通过**：首启弹「要允许…发送通知吗？」→ 允许 → granted=true(USER_SET) → asked 标志无二次弹窗；下载中 id10001、完成后 id10002 均可见（SDK35 实测） | 📱 |

## 10. Lifecycle & performance

| ID | Case | Expected | Status |
|---|---|---|---|
| 10.1 | Rotate on every screen | No state loss (picker re-opens, fields kept) | 📱 |
| 10.2 | Background → foreground during downloads | UI refreshes, no duplicate detection | 📱 |
| 10.3 | Process death (swipe away) → relaunch | Queue restored, cards restored, no duplicates | 📱 |
| 10.4 | Huge blog (4k+ posts) | No ANR; queue capped at 3000 rows (#12) | 📱 |
| 10.5 | Removing a 30k-row card | No crash, no system_server death (#13) | 📱 |
| 10.6 | Downgrade install (new → old APK) | Old build must not crash-loop; known limitation: v1.0.7 opens the v2 database with `fallbackToDestructiveMigration` — **history/cookies are wiped on downgrade**, so back up first | 📱 |

## 11. Regression checklist (issues found in device testing)

| ID | Issue | Fix | Status |
|---|---|---|---|
| R1 | Single file downloaded without asking | Picker for single files | ✅ 📱 |
| R2 | Partially downloaded link never detected again | Clip-change signal | 📱 |
| R3 | Skip → re-copy did not re-open the picker | Clip-description timestamp signal | ✅ 📱 |
| R4 | Re-downloading created `(1)` copies | Overwrite in place | 📱 |
| R5 | Blog link only filled the field | Type dialog / direct start | 📱 |
| R6 | Long-blog dialog appeared after downloads started | Gate before enqueue | ✅ |
| R7 | GIFs saved as `.bin` | Shared inference + Content-Type rename | ✅ |
| R8 | Card progress frozen while parsing | Media progress wins | 📱 |
| R9 | Remove button only while paused | Always visible | 📱 |
| R10 | 46/86 after re-adding a blog | Blog-wide counts | ✅ 📱 |
| R11 | Switch on still showed the blog dialog | No dialog when on | 📱 |
| R12 | Huge blog ANR'd the app | Back-pressure | 📱 |
| R13 | Removing a big card killed the system | Chunked intents | 📱 |
| R14 | Blog files mixed with single-post files | Per-blog folder | 📱 |
| R15 | Downloads started before the long-blog confirm | Gate moved first | ✅ |
| R16 | Detail 全部暂停 left the card "parsing" | Card-level pause/resume | 📱 |
| R17 | Card lacked total/scanned posts | New stats line | ✅ 📱 |
| R18 | "Already in list" but nothing visible | Orphans visible + named toast | ✅ 📱 |
| R19 | Copying the field's own URL re-triggered detection | Ignore focused copies; own-copy snapshot via `ClipboardDedupe.isOwnFieldCopy` | ✅ 📱 |
| R20 | Stale clipboard restarted a removed blog | Deleting a row / clearing history no longer resets the clipboard dedupe key (only 设置 → 清空自动检测缓存 does) | ✅ |
| R21 | Clear-all did nothing while orphan rows were listed | Clear-all now also takes completed orphans; a live card's rows stay | ✅ 📱 |
| R22 | Re-copying the same link was never re-detected | Clip-description timestamp signal (`ClipboardDedupe`, three signals combined) | ✅ 📱 |
| R23 | Sharing a blog while a post link sat in the clipboard fired both flows | Clipboard ignored for 5s after a share | ✅ 📱 |
| R24 | Removing a blog card dumped its old rows into the main list | Orphans hidden again — only counted by the next card of that blog | ✅ |
| R25 | Removing a running blog card resurrected it and downloads resumed | Observed once, correlated with an Activity/window anomaly on this device; a plain removal of a running card stayed stable for 13 min — needs a reliable repro — **再尝试 2 轮均未复现**（主手机，固定坐标移除）：① 扫描起步 13s 移除 ② 扫描深入（page 30、discovered 422、enqueued 2547）时移除 —— 两轮均为 worker `cancelFlag=true` 干净退出 → REMOVE×6 → 之后 75s 监视**零新增 START/append 事件**、卡片未复活、列表无回填；原始观察绑定本机 Activity 窗口异常（不可控），维持待复现 | ⬜ |
| R26 | POST_NOTIFICATIONS declared but never requested at runtime | On Android 13+ notifications are blocked until granted — 已在 Pixel 3（SDK35）实测确认：连 FGS 进度通知也被拦（importance=NONE、numPostedByApp=0、shade 完全不可见），且应用无任何「通知被禁用」提示（静默，违反 UX 规则）；`pm grant` 即全部恢复 → 修复：首启运行时请求 + `areNotificationsEnabled()` 禁用提示 —— **已修复并真机验证**（SDK35）：onboarding 后弹「要允许…发送通知吗？」→ 允许 → granted=true(USER_SET)，asked 标志确保不二次弹窗，授权后通知恢复可见（9.6 复测通过）；`areNotificationsEnabled()` 禁用提示属可选增强、未包含 | ✅ |
| R27 | Completion summary notification is inconsistent | **已结案**：应用侧发布路径全部日志验证正确（publishSummary 排空期反复调用、自然完成路径无后续取消、ACTION_START 在入队前而非排空后取消汇总）。长期不出现的真身 = 测试环境污染：清理通知时的 snooze 循环把 id10002 写进系统 Pending-snoozed（时间戳约 2094、重启仍在），**今日 `cmd notification unsnooze` 成功清除该条目** → 主手机随即实拍「成功: 4，失败: 0」置顶显示 ✓；历史 1 现 3 不现的波动由 R41（重复 START 的多余取消汇总）+ R40（计数少计）解释，两者均已修复 | ✅ |
| R28 | `tmblr.co` short links are not recognised | Share path confirmed SILENT; field path shows the invalid toast — **已修复**：`shareUrlRegex` 识别 tmblr.co、`parseShareUrl` 网络跟随重定向后再分类、失败/无 URL 分支一律本地化 Toast（不再静默）；真机验证失败腿「无法解析短链接。」；**成功腿 E2E ✓**：真实短链 `tmblr.co/Z5-RFwSEjDJR` → 日志 `short link resolved → historicalmyths.tumblr.com/post/30311503067…` → 选择器「已选 1/1」 | ✅ |
| R29 | Download notification sits in the silent group | Root cause confirmed on device: the channel was IMPORTANCE_LOW → EMUI folds it into the collapsed 静音 chips (stock Android 15 keeps LOW individually visible in its silent group); on-device experiment (raise to 默认 → prominent with tappable actions, restore → buried) proved the fix direction. **Fixed**: ships a new channel id `tumblr_download_channel_v2` at IMPORTANCE_DEFAULT with one-time legacy-channel cleanup — a new id is required because an existing channel's importance can never be changed by its app, and deleting it does not stick while a notification still references it (both observed on device). Verified on Pixel 3: logs show `v2 importance=3`, the NotificationRecord reports importance=3, and the 成功：X，失败：Y summary displays at the top of the shade (was buried in the silent group). EMUI 主手机同日再验：迁移日志（legacy deleted / v2 importance=3）、NotificationRecord importance=3、进度通知「kthingz-… · 现在」以独立大行实拍于通知栏主区 | ✅ |
| R30 | Text-only / deleted posts open a picker with stray page-level images | No post media exists, yet the parser scrapes the blog **cover (og:image)** — verified: text-only post yielded the blog header banner (1419×640), the 404 page yielded its imagery; restrict extraction to actual post media nodes (2.27, 2.28) — **已修复并验证**：`extractFromHtml` 页面扫描限定在 `<article>/<figure>` 容器内（新 `scopeToPostContent`），彻底停抓 head 级 og:image；无载荷页（无 InitialState/json）直接报「Post not found or deleted」→ 本地化 `parse_post_not_found`；真机：假删帖出友好 Toast 无选择器、真实 kthingz 帖照常弹选择器（回归 ✓），3 条新单测覆盖 | ✅ |
| R31 | Sharing a blog link with surrounding words is a silent no-op | `isBlogUrl` tests the raw text, so a commented blog share falls into the post path and dies silently (2.24) — **已修复**：`BlogFeedParser.findBlogUrlInText` 从自由文本提取博客链接后路由，分享无 URL 时弹无效提示；真机 2.24 通过、单测覆盖 | ✅ |
| R32 | Re-downloading after clearing history creates numbered `(N)` copies | Blog sub-folder files pile up `(1)/(2)/…` per re-download while a flat single-post file overwrites in place — the existence lookup likely misses the sub-folder path (R4 inconsistency); 夜间实测扩展：平铺单帖文件在清史后同样复现（qqchurch 视频连下 5 次堆出 (1)–(5)），说明覆盖逻辑依赖历史行记录，清史后走 MediaStore 插入冲突 → `(N)`；**真根因（探针实锤）**：MediaStore 行会丢失 owner 归属（owner=NULL，疑与重启/重扫相关），无 READ 权限的本应用对这类行「查询不可见 → 漏检 → insert 冲突 → (1)」且「路径读/删全被拒」（exists=true canRead=false delete=false）——先前 Android15 反例结论作废（Pixel 实测同样会堆 (N)）。**已修复并 E2E 验证（用户选定 SAF 方案）**：首启引导一次「授权下载文件夹」（去授权/暂不，仅一次）→ 持久化树授权 → 下载时经 SAF 查找旧文件并原地覆写；真机证据 `R32: SAF overwrite name=qqchurch-….mov` + 重下后 count=1、mtime 刷新、无 (2)、行已完成。路径预删分支保留兜底（对归属完好文件有效） | ✅ |
| R33 | "Private Tumblr posts are not supported yet." is hardcoded English | Chinese UI shows an English toast — move the string into resources (2.6/私密帖) — 实为已覆盖：`DownloadStateManager.localizeError` 将英文字面量映射到 `R.string.parse_private_not_supported`（英文串只是内部 key），真机实拍中文 Toast「暂不支持下载私密帖子。」→ 结案 | ✅ |
| R34 | Login state flips to logged-out; login bounces back with no error | Root cause: session invalidated server-side (the 401 was the session, not the token) plus stale cookie state in the WebView jar; `pm clear` + re-login succeeded and the round-trip is verified: cold start keeps `akarimarisa/在线` at +5 s and +18 s with `fetch: api ok`. Also shipped: transient failures keep the cached account (offline-verified), login rejection now toasts, redirect-target/root-URL detection fixed, main-frame request diagnostics | ✅ |
| R35 | Cross-post same-media URL deduped silently | With the original post's row in the queue, a reblog's identical media is URL-deduped and never enqueued — per the user's UX rule one file per post is preferred (filenames differ by author); drives the 7.10 ❌ —— **已修复并 E2E 验证**：去重键改为文件级 `postScopedMediaKey`（作者前缀+media 身份，镜像 targetFileName 的命名选择），覆盖 offerMediaPreview/processAppend/processRestore 合并与 CompletedMediaStore（键语义 = 「该文件是否已落盘」）；范围化重下行为不变（同帖仍去重）。注意迁移：旧裸 identity 标记不再匹配 → 旧媒体首次重下会原地覆盖一次（R32 兜底）后重新标记 | ✅ |
| R36 | After a network drop the blog scan and queue become zombies | 昨日现象（解析中永冻、零日志、恢复不自愈）的**真根因 = R43 主线程 ANR 冻结**（进程冻住 → UI/日志全停），而非异常被吞：错误处理本就有界（attempts≥3 → BlockedException → 卡片出错）。**已修 + 双侧复测**：① 扫描侧 —— 断网 18s 内 BLOCKED、红字可读错误、游标持久化、恢复后暂停→开始从游标续扫（5.18 ✅）；② 下载侧 —— 双守卫 10s 内转已暂停+「等待网络连接…」、日志可见、按设计不自动续（6.6 ✅）；③ 加固 —— 网关客户端 callTimeout 20s（含 DNS 封顶）保险 + 全套 BlogDM/守卫可观测日志 | ✅ |
| R37 | Login redirect target missed the slug-canonicalised URL | Tumblr canonicalises `/<id>` to `/<id>/slug`, so the exact-match `isRedirectTarget` never auto-finished and the user had to tap 完成 by hand — fixed (prefix/slug match, commit 1ede60b) and verified in the full 2.6 chain | ✅ |
| R38 | `www.tumblr.com/blog/<name>` URLs rejected as invalid | Tumblr's own blog switcher shares URLs with the `blog/` prefix while the anchor regex only allowed a single path segment — fixed (optional `blog/` prefix) with unit tests, device-verified with akari-test-blog in 5.20 | ✅ |
| R39 | Orphaned `.pending-*` files are never swept | 8 legacy pending files (Nov 11–13, old `post-*` naming) sat in Downloads forever — **已修复并真机验证**：每次 restore 后双源清扫（MediaStore 行 + 应用目录文件走查），按 mediaId 与行集合比对决定孤儿；真机三段证据：行存在 → `removed=0`（Range 续传受保护，#7.9）→ UI 删行且服务未运行时 pending 残留（真实孤儿配方，顺带发现 remove 的清理依赖服务在跑）→ 再启动 `delete=true removed=1 PENDING_GONE`。能力边界：`pm clear` 会清掉 owner 归属与 provider 行，此时僵尸文件对应用完全不可见（文件/API 双盲），无法自查（本次由 shell 手工清理） | ✅ |
| R40 | Completion summary can under-count | Summary read 成功:3 (and later 成功:1) while all 4 rows were 已完成. **Root cause**: the ACTION_START handler's "service idle ⇒ fresh session" check cleared `sessionCompletedIds` whenever the NEXT item's START landed in a momentary all-idle window of the serial queue — timeline proof: `add→size=1 / CLEAR sizeBefore=1 / add→size=2 / CLEAR sizeBefore=2 / publish completed=1`. **Fixed**: a new session opens only from SUMMARY state (previous batch finished+summarized); mid-batch STARTs never clear. **Verified**: `add 1,2,3,4` with zero mid-batch CLEARs and `publishSummary completed=4` for a 4-item run (the notification text uses this same count; on this device its visibility stays R27-artifact-blocked) | ✅ |
| R41 | Duplicate per-item ACTION_START intents | The same item id is started twice+ in a row (Pixel 3: 6 STARTs for 4 items; main phone: up to 3 for one item) — every extra START also ran the "cancel summary" branch (R27-adjacent). **Root cause found via mapping.txt stack retrace**: the service emits `status=QUEUED` during the rate-limit gap wait (line 668「等待 …ms」), processProgress's `else -> incoming` writes that straight back to the row (bypassing replaceItem), and the line-1112 pump re-picked the same row → new ACTION_START per gap emission. **Fixed**: `sendStartIntent` keeps a non-explicit in-flight set (released on COMPLETED/FAILED/PAUSED, kept through gap-QUEUED; explicit user starts bypass); duplicates are logged as SKIP. **Verified**: 4-item run = START counts 1/1/1/1 + SKIP-dup blocked the re-pick attempts | ✅ |
| R42 | Unit suite is intermittently flaky in full runs | 4 sightings (completedProgress_isPersistedImmediately, pauseBatch_pausesOnlyBatchItems, migrate_cookies_skips_if_room_has_data, DownloadHistoryDaoTest insert_and_read_back) — each passes in isolation, suite green on re-run — **已修复（根因实锤，非时序猜测）**：复现抓到真凶 `UncaughtExceptionsBeforeTest` + suppressed 栈指向 `TumblrCookieStore.migrateFromSharedPrefs` 在真实 IO 线程 NPE（sandbox 拆除后 `ContextImpl.sSharedPrefsCache=null`）—— manifest 注册的 `TumblrDownloaderApplication.onCreate` 把启动迁移 fire-and-forget 到真实 `Dispatchers.IO`，测试类结束时协程仍在跑 → 无 handler → coroutines-test 全局 `ExceptionCollector` 收尸 → **下一个** `runTest` 吞枪（4 条「偶发」全是随机受害邻居）。**修复**：`appScope` 挂 `CoroutineExceptionHandler`（context handler 短路全局路径，同时修掉生产隐患：启动迁移失败原本会崩进程）+ 暴露 `startupJob` 供 `MigrationTest.setUp` 先 `join()` 再播种（消除 migrate_cookies_* 的真实数据竞态）。**验收**：修复前 11 轮炸 1 → 修复后连续 21 轮 0 失败 | ✅ |
| R43 | Main-thread ANR from per-call regex compilation | 3 historical ANRs on the main phone (00:08:28 Input dispatch, 00:17:08 & 00:19:38 executing-service) captured via bugreport: main thread sat in `java.util.regex.Pattern.compile` (ICU native) with 32 s CPU — `sanitizeFileName` compiled TWO patterns per call and #R35 put it in the enqueue dedupe hot path (candidates×rows×appends on the main cmd loop); ICU compile also takes a cross-thread lock. **Fixed**: hoisted every in-function `Regex(...)` to precompiled vals (sanitize ×2, isSquareRendition ×2, jsonMediaUrl shared) + **re-verified**: full storm (9,886-media card resuming) with 90 s of continuous UI hammering → am_anr stayed at 3 (all pre-fix) | ✅ |
| R44 | Bot-challenge page reported as a login problem | The 403 `Checking your browser` JS challenge (HASHCASH proof-of-work, caught live on `uuquotes.tumblr.com` with cookies fully attached + `hasTumblrCookies=true`) was classified as LoginRequired → users were told「登录后可重试。」for a state logging in can never fix — misleading per the UX bar. **已修**：`TumblrParser.isBrowserChallenge()` recognises the challenge face (`Checking your browser` / `HASHCASH_CONFIG` / `/__challenge/`) in both the page-HTML and oEmbed paths → explicit `Browser verification required` → localized `parse_browser_check`（站点正在执行本应用无法通过的自动访问验证…）。Device-verified: same link now shows the honest toast, public-post regression 已选 7/7 ✓, +1 unit test (challenge recognised, normal/login pages not) | ✅ |
| R45 | DNS blackhole parks the parse as a zombie (callTimeout ineffective) | Observed on the Pixel 3 (v2ray tunnel DNS dead: ICMP 1.1.1.1 ok, DNS queries time out): native `getaddrinfo` is not interruptible by OkHttp's `callTimeout(20s)` → first share hung >3 min with `isParsing` stuck, every later share rejected as「already parsing」. **已修**：custom `boundedDns` runs resolution on a daemon pool with an 8 s cap → stall surfaces as a normal IOException. Device-verified on the same blackhole: `DNS lookup timed out` at 8 s → toast at 9 s, `isParsing` released (immediate second share not rejected) | ✅ |
