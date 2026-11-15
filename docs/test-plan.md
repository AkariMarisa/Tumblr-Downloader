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
| 2.6 | Post that needs login | Login page opens; after login the parse retries and keeps the picker intent — 全链路实测：登出→ntn888 弹登录页→登录→按「完成」→自动重试→选择器自动弹出；redirect 目标识别补了 slug 规范化匹配（免手动完成） | 📱 |
| 2.7 | Adult-content interstitial | Relaxed JSON parse or login fallback, never a silent empty result — 素材未触发拦截页（登录/登出均直接解析成功），需真正出现年龄确认页的帖子 | ⬜ |
| 2.8 | Video post | Video row appears with type 视频, downloads as `.mp4` | ⬜ |
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
| 2.24 | Share text with surrounding words (blog) | Blog flow still starts (type dialog) | ❌ |
| 2.25 | `tmblr.co` short link | Field path shows the invalid-URL toast ✓; share path must not be a silent no-op (R28) | ❌ |
| 2.26 | URL with `?source=` / `utm_*` params | Parsed normally (`sourceParamRegex` strips Tumblr's own param) | ⬜ |
| 2.27 | Deleted post (404) / server error | Explicit error message, no silent empty result, no crash | ❌ |
| 2.28 | Text-only post with no media | Friendly "no media" message instead of an empty picker | ❌ |

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
| 5.18 | Network drops mid-scan | Card goes BLOCKED with an error code; 开始 after reconnect resumes from the cursor — 实测：165s+ 停在「解析中」不转出错，恢复后不自愈，暂停→开始后游标仍冻结（见 R36） | ❌ |
| 5.19 | Two blogs scanning at the same time | Two independent cards, counters and pause/remove never cross | 📱 |
| 5.20 | Empty blog (0 posts) / password-protected blog | Ends or errors cleanly, no hang, no crash — 实测真·空博客（akari-test-blog，0 帖）：类型选择 → 扫描 → 已完成 · 已扫描 0 帖 · 0 媒体，无崩溃；URL 为 /blog/<name> 形式，顺带修复解析（R38）；加密博客变体待素材 | 📱 |

> Note: 5.3/5.4 gate on ≥5000 posts (the lowest selectable threshold); no reachable blog is that large, so the on-device path is covered by unit tests only.

## 6. Queue & row actions

| ID | Case | Expected | Status |
|---|---|---|---|
| 6.1 | Row 暂停/继续 | Status and progress switch; partial file resumes | 📱 |
| 6.2 | Row 删除 (red ✕) | Row removed, file kept; re-adding the link does not create a `(1)` copy (#4) | 📱 |
| 6.3 | 全部暂停 / 全部继续 (toolbar) | Whole queue pauses/resumes | 📱 |
| 6.4 | Clear completed | Completed standalone **and orphan** rows are removed; a live card's rows stay; empty state returns | 📱 |
| 6.5 | Failed row → 续 | Retries from scratch without removing it first — 断网路径不再产生失败行（改为挂起），需服务器错误素材（404/5xx 媒体） | ⬜ |
| 6.6 | WiFi drops mid-download | Active rows fail after 3 retries, queued rows pause, no auto-resume — 实测：下载静默挂起（零日志零提示），挂死项占住并发槽位，恢复网络后队列冻结不自愈；手动全部暂停→全部开始可救活 | ❌ |
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
| 7.10 | The same media URL appearing in two posts | UX expectation (user): download it once per post (filenames differ by author) rather than silently dedupe — 实测：原帖行在队列时，转载帖同 media 被 URL 去重直接跳过（与预期相反） | ❌ |

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
| 8.8 | Clear cache / logout | Cookies removed, login state reset | ⬜ |
| 8.9 | Whole core flow while the UI is English | UI strings verified English (tabs/hint/buttons) and restored to system default after; toast/notification share the same resources | 📱 |

## 9. Sidebar, viewer, notifications

| ID | Case | Expected | Status |
|---|---|---|---|
| 9.1 | Login (WebView) → avatar/logout | Username shown, logout clears cookies — 侧栏显示账号名/头像 ✓；退出登录一键即清（无二次确认），清后接口/dashboard 均 logged-out ✓ | 📱 |
| 9.2 | About screen | Version, links open correctly | 📱 |
| 9.3 | Media viewer: image/video | Opens, swipe next changes image, swipe prev returns, back lands on the same row | 📱 |
| 9.4 | Notification while downloading | Posted with filename, progress bar and a 全部停止 action; lands in the **silent (低优先级) group** at the bottom of the shade — see R29 for the channel-importance note；实测动作：点「全部停止」→ 服务收到 ACTION_PAUSE_ALL（日志 21:54:13）→ 可见行全部「已暂停」、其后零新请求；静音分组根因确认为通道重要性=静音（R29），临时调「默认」后通知独立显示且动作可点，测完已调回 | 📱 |
| 9.5 | Notification after completion | Summary 「成功：X，失败：Y」 persists after the service stops, no stale 0% (#24) — 实测（Pixel 3 净净 Android 15 + 授权后）：格式 ✓、服务停止后仍留存（+60s 复查仍在）、无 0% ✓；**但计数错误**：显示「成功:3」而 4 行/4 文件全部完成（文件 23:16:28 前落盘，23:16:29 与 23:16:34 发布仍写 3）→ R40；pause-all 路径发布汇总本身已日志验证 ✓；主手机上还被 snooze 工件挡住不可见（R27） | ❌ |
| 9.6 | Android 13+ notification permission | POST_NOTIFICATIONS is declared but never requested at runtime — on a 13+ device the non-FGS summary would be silently blocked (R26) — 实测（Pixel 3, Android 15/SDK35 全新安装）：首启及全流程无任何权限弹窗、granted=false → 系统拦下**全部**通知：含 FGS 进度通知（numPostedByApp=0、app importance=NONE、通知栏完全不可见）；`pm grant` 后 id10001/id10002 立即可见（posted=1）→ 修复方向=运行时请求（R26） | ❌ |

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
| R25 | Removing a running blog card resurrected it and downloads resumed | Observed once, correlated with an Activity/window anomaly on this device; a plain removal of a running card stayed stable for 13 min — needs a reliable repro | ⬜ |
| R26 | POST_NOTIFICATIONS declared but never requested at runtime | On Android 13+ notifications are blocked until granted — 已在 Pixel 3（SDK35）实测确认：连 FGS 进度通知也被拦（importance=NONE、numPostedByApp=0、shade 完全不可见），且应用无任何「通知被禁用」提示（静默，违反 UX 规则）；`pm grant` 即全部恢复 → 修复：首启运行时请求 + `areNotificationsEnabled()` 禁用提示 | ⬜ |
| R27 | Completion summary notification is inconsistent | App-side code verified CORRECT via new logs: publishSummary is called repeatedly through the drain, no cancel follows it on the natural-completion path, and onStartCommand traces show ACTION_START cancels the summary *before* queuing new work (not after a drain). Current non-visibility is a TEST-ENVIRONMENT artifact: notification id 10002 sits in the system's Pending-snoozed store with a ~2094 timestamp from an earlier notification-clearing snooze loop, survives reboot (verified), and unsnooze cannot reach the pending store — the historical 1-present/3-absent observations predate that loop and remain partially unexplained | ⬜ |
| R28 | `tmblr.co` short links are not recognised | Share path confirmed SILENT; field path shows the invalid toast — expand the links or surface the toast on share too | ⬜ |
| R29 | Download notification sits in the silent group | Channel `tumblr_download_channel` is IMPORTANCE_LOW → EMUI shows the channel as「静音」and the OS folds download notifications into the collapsed chips of the bottom silent group (root cause confirmed on device); experiment: raising the channel to 默认 made the notification stand alone with an expandable 全部停止 action (tapped and verified), restoring 静音 buried it again — consider DEFAULT importance as a product change (affects alert behaviour). Stock Android 15 (Pixel 3) also files LOW into a 静音/silent group, but there the notification stays individually visible (no chip folding) — the worst burial is EMUI-specific | ⬜ |
| R30 | Text-only / deleted posts open a picker with stray page-level images | No post media exists, yet the parser scrapes the blog **cover (og:image)** — verified: text-only post yielded the blog header banner (1419×640), the 404 page yielded its imagery; restrict extraction to actual post media nodes (2.27, 2.28) | ⬜ |
| R31 | Sharing a blog link with surrounding words is a silent no-op | `isBlogUrl` tests the raw text, so a commented blog share falls into the post path and dies silently (2.24) | ⬜ |
| R32 | Re-downloading after clearing history creates numbered `(N)` copies | Blog sub-folder files pile up `(1)/(2)/…` per re-download while a flat single-post file overwrites in place — the existence lookup likely misses the sub-folder path (R4 inconsistency); 夜间实测扩展：平铺单帖文件在清史后同样复现（qqchurch 视频连下 5 次堆出 (1)–(5)），说明覆盖逻辑依赖历史行记录，清史后走 MediaStore 插入冲突 → `(N)`；反例：Android 15 全新设备（Pixel 3）同样清史后重下却**原地覆盖无 (N)** → 冲突行为疑与 OS/MediaStore 版本相关 | ⬜ |
| R33 | "Private Tumblr posts are not supported yet." is hardcoded English | Chinese UI shows an English toast — move the string into resources (2.6/私密帖) | ⬜ |
| R34 | Login state flips to logged-out; login bounces back with no error | Root cause: session invalidated server-side (the 401 was the session, not the token) plus stale cookie state in the WebView jar; `pm clear` + re-login succeeded and the round-trip is verified: cold start keeps `akarimarisa/在线` at +5 s and +18 s with `fetch: api ok`. Also shipped: transient failures keep the cached account (offline-verified), login rejection now toasts, redirect-target/root-URL detection fixed, main-frame request diagnostics | ✅ |
| R35 | Cross-post same-media URL deduped silently | With the original post's row in the queue, a reblog's identical media is URL-deduped and never enqueued — per the user's UX rule one file per post is preferred (filenames differ by author); drives the 7.10 ❌ | ⬜ |
| R36 | After a network drop the blog scan and queue become zombies | Scan card sticks at 解析中 forever (never errors/BLOCKED, never self-heals after reconnect; pause→start unfreezes downloads but the scan cursor stays frozen — suspected swallowed worker exception / leaked activeJobs). Download side: the hung transfer holds its concurrency slot, freezing the whole queue until 全部暂停→全部开始 (verified recovery) | ⬜ |
| R37 | Login redirect target missed the slug-canonicalised URL | Tumblr canonicalises `/<id>` to `/<id>/slug`, so the exact-match `isRedirectTarget` never auto-finished and the user had to tap 完成 by hand — fixed (prefix/slug match, commit 1ede60b) and verified in the full 2.6 chain | ✅ |
| R38 | `www.tumblr.com/blog/<name>` URLs rejected as invalid | Tumblr's own blog switcher shares URLs with the `blog/` prefix while the anchor regex only allowed a single path segment — fixed (optional `blog/` prefix) with unit tests, device-verified with akari-test-blog in 5.20 | ✅ |
| R39 | Orphaned `.pending-*` files are never swept | 8 legacy pending files (Nov 11–13, old `post-*` naming) sit in Downloads forever; today's flows (finish / ENOSPC-fail / remove-row mid-write) all clean their own pendings (verified) — add a startup sweep for pendings with no matching row | ⬜ |
| R40 | Completion summary can under-count | On a clean Android 15 device the summary read 成功:3 while all 4 rows/files were completed — files hit disk by 23:16:28 but the publishes at 23:16:29 (ACTIVE) and 23:16:34 (SUMMARY) both still said 3, and nothing re-published afterwards; one completion event is lost, suspected race with the duplicate START intents of R41 — the displayed 成功：X，失败：Y then misleads the user | ⬜ |
| R41 | Duplicate per-item ACTION_START intents | The same item id is started twice in a row (Pixel 3: 6 STARTs for 4 items at 23:16:22–27; main phone: item 2fea4fa7 started at 20:43:42.460 and 20:43:43.471) — every extra START also runs the "cancel summary" branch (R27-adjacent); root cause in the enqueue/start path | ⬜ |
