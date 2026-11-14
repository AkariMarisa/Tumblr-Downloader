# Test Plan

Every user-visible flow of the app, grouped by area. Status legend:

* ✅ **automated** — covered by a JVM unit test (`./gradlew testDebugUnitTest`, 198 tests)
* 📱 **verified** — executed by hand on a real device (SHG01, Android 14, Chinese UI)
* ⬜ **open** — designed here, not executed yet

Run the automated suite with `./gradlew testDebugUnitTest lintDebug assembleDebug`.
Device cases are scripted with `adb` (`uiautomator dump` for assertions, `logcat` for behaviour).

---

## 1. Build & first run

| ID | Case | Expected | Status |
|---|---|---|---|
| 1.1 | Fresh install → launch | Onboarding opens; 5 pages (clipboard, login, downloads, picker, blog cards); dots match; Skip/Get started work | 📱 |
| 1.2 | Re-launch after onboarding | Onboarding does not open again | ⬜ |
| 1.3 | Sidebar → 使用教程 | The same 5 pages replay with a single 关闭 button; leaving does not reset onboarding | 📱 |
| 1.4 | `./gradlew lintDebug` | 0 errors | ✅ |
| 1.5 | Release build (`assembleRelease`) installs over debug-signed build with the release key | Install succeeds, data preserved, no crash | 📱 |

## 2. Link input & parsing

| ID | Case | Expected | Status |
|---|---|---|---|
| 2.1 | Paste a single-post URL → 下载媒体 | Picker opens (switch off) / queues directly (switch on) | 📱 |
| 2.2 | Paste a multi-image post | Picker lists every fresh file with thumbnails, `已选 n/n` | 📱 |
| 2.3 | Paste a post whose media is already queued | Toast says where it is (`位于博客「x」的卡片下` for blog rows) | ✅ 📱 |
| 2.4 | Paste an invalid URL | `请输入有效的 Tumblr 链接` toast, no crash | ⬜ |
| 2.5 | Paste a non-Tumblr URL | Invalid-URL toast | ⬜ |
| 2.6 | Post that needs login | Login page opens; after login the parse retries and keeps the picker intent | ⬜ |
| 2.7 | Adult-content interstitial | Relaxed JSON parse or login fallback, never a silent empty result | ⬜ |
| 2.8 | Video post | Video row appears with type 视频, downloads as `.mp4` | ⬜ |
| 2.9 | GIF post (`.gif` / `.gifv` / extensionless) | Picker groups it under GIF; saved file ends in `.gif` | ✅ 📱 |
| 2.10 | `.pnj` high-quality image | Saved as `.png` | ✅ |
| 2.11 | Share intent from another app (post) | Picker opens (switch off) | 📱 |
| 2.12 | Share intent (blog URL) | Type dialog opens (switch off) / scan starts (switch on) | 📱 |
| 2.13 | Clipboard: copy post link → switch back | Picker opens, field filled, Download tab selected | 📱 |
| 2.14 | Clipboard: same link again after 跳过 | Picker opens again (clip-description timestamp) | 📱 |
| 2.15 | Clipboard: copy the URL that is already in the input field | No re-trigger (#2) | ⬜ |
| 2.16 | Clipboard: return from the media viewer with an unchanged link | Nothing happens (last-URL fallback) | ⬜ |
| 2.17 | Clipboard auto-detect disabled in settings | No detection at all | ⬜ |
| 2.18 | Cold start with a Tumblr link in the clipboard | Detected on launch too — the last-URL dedupe still blocks links that were handled before | 📱 |
| 2.19 | Chrome copy → switch back (same link re-copied) | Detected again: the clip description carries a per-copy timestamp (#1) | 📱 |
| 2.20 | Tumblr app copy → switch back | Detected | 📱 |
| 2.21 | Chrome share → the app | Type dialog opens; the stale clipboard is ignored for 5s (no second flow) | 📱 |
| 2.22 | Tumblr app share → the app | Picker opens (even for a one-file post); no clipboard conflict | 📱 |

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
| 4.6 | Toggle survives restart | Value persisted | ⬜ |

## 5. Blog batch download

| ID | Case | Expected | Status |
|---|---|---|---|
| 5.1 | Blog URL → 下载媒体 (switch off) | Type dialog appears before anything is queued | 📱 |
| 5.2 | Choose 仅图片 | Only image rows are enqueued | ⬜ |
| 5.3 | Long blog confirm | Dialog appears **before** the first media is queued (#15) | ✅ |
| 5.4 | Long blog → 取消 | No rows queued, card ends CANCELLED | ⬜ |
| 5.5 | Card while scanning | Stats `已扫描 x/y 帖 · n 个媒体`, bar shows media progress, summary `已下载 a/b 个媒体` (#8, #17) | 📱 |
| 5.6 | Card buttons | 详情/暂停/移除 always visible; 开始 when idle (#9) | 📱 |
| 5.7 | Card 暂停 → 开始 | Scan and downloads stop/start together | 📱 |
| 5.8 | Card 移除 (huge queue) | No crash/ANR; unfinished rows purged, completed kept (#13) | 📱 |
| 5.9 | Detail 全部暂停 → back | Card shows 已暂停 + 开始 (not 解析中) (#16) | ⬜ |
| 5.10 | Detail 全部开始 → back | Card shows parsing/downloading again | ⬜ |
| 5.11 | Same blog twice | One card, moved to the top, progress kept | ✅ 📱 |
| 5.12 | Remove card → re-add | Old completed files are counted by the new card (#10) | ✅ 📱 |
| 5.13 | Detail blocked while the long-confirm is pending | Toast `博客仍在解析中…` instead of navigating (#6) | ⬜ |
| 5.14 | Back-pressure: queue > 3000 rows | Scan waits for downloads; no ANR (#12) | 📱 |
| 5.15 | Blog output folder | Files land in `TumblrDownloader/<blog>-<hash>/` (#14) | 📱 |
| 5.16 | Orphan rows (card gone) | Shown in the main list, removable (#18) | 📱 |
| 5.17 | Interruption (process death) → card 未完成 → 开始 | Resumes from the persisted cursor | ⬜ |

## 6. Queue & row actions

| ID | Case | Expected | Status |
|---|---|---|---|
| 6.1 | Row 暂停/继续 | Status and progress switch; partial file resumes | ⬜ |
| 6.2 | Row 删除 (red ✕) | Row removed, file kept; re-adding the link does not create a `(1)` copy (#4) | 📱 |
| 6.3 | 全部暂停 / 全部继续 (toolbar) | Whole queue pauses/resumes | ⬜ |
| 6.4 | Clear completed | Completed standalone **and orphan** rows are removed; a live card's rows stay; empty state returns | 📱 |
| 6.5 | Failed row → 续 | Retries from scratch without removing it first | ⬜ |
| 6.6 | WiFi drops mid-download | Active rows fail after 3 retries, queued rows pause, no auto-resume | ⬜ |
| 6.7 | Concurrency setting (1–4) | At most N rows download at once | ⬜ |
| 6.8 | Rate limit | Speed stays under the configured cap | ⬜ |

## 7. Files & storage

| ID | Case | Expected | Status |
|---|---|---|---|
| 7.1 | Single-post file name | `<author>-<mediaId>.<ext>` matching the queue title | ✅ 📱 |
| 7.2 | Blog file name | `<blog>-<mediaId>.<ext>` (no `post-` prefix) (#14) | 📱 |
| 7.3 | Extensionless URL | Renamed from the response `Content-Type` after download (#7) | ✅ |
| 7.4 | `.gifv` / uppercase `.GIF` / `?format=gif` | Saved as `.gif` | ✅ |
| 7.5 | Re-download of an existing file | Overwrites in place, no `(1)` / `_1` copy (#4) | 📱 |
| 7.6 | Custom directory (SAF) | Files go there; blog sub-folder created on demand | ⬜ |
| 7.7 | Killed mid-download → resume | Partial file resumed via Range, no duplicate | ⬜ |
| 7.8 | MediaStore visibility | Files appear in the gallery/Downloads with the right `relative_path` | 📱 |

## 8. Settings

| ID | Case | Expected | Status |
|---|---|---|---|
| 8.1 | Category menu → each sub-screen | Only that category's cards are shown | 📱 |
| 8.2 | Download directory (default/custom) | Label updates; new files go there | ⬜ |
| 8.3 | Language 中文/English | Whole UI switches, including notifications and toasts | ⬜ |
| 8.4 | Theme light/dark/system | Applies and survives restart | ⬜ |
| 8.5 | Image/video quality tiers | Picker/parse use the chosen rendition | ⬜ |
| 8.6 | Blog scan interval / long threshold | Affects pacing and when 5.3 fires | ⬜ |
| 8.7 | 清空自动检测缓存 | Next clipboard link is detected again | ⬜ |
| 8.8 | Clear cache / logout | Cookies removed, login state reset | ⬜ |

## 9. Sidebar, viewer, notifications

| ID | Case | Expected | Status |
|---|---|---|---|
| 9.1 | Login (WebView) → avatar/logout | Username shown, logout clears cookies | ⬜ |
| 9.2 | About screen | Version, links open correctly | ⬜ |
| 9.3 | Media viewer: image/video | Opens, next/prev, back returns to the same row | ⬜ |
| 9.4 | Notification while downloading | Progress + speed; pause/cancel actions work | ⬜ |
| 9.5 | Notification after completion | Summary transitions correctly, no stale 0% (#24) | ⬜ |

## 10. Lifecycle & performance

| ID | Case | Expected | Status |
|---|---|---|---|
| 10.1 | Rotate on every screen | No state loss (picker re-opens, fields kept) | 📱 |
| 10.2 | Background → foreground during downloads | UI refreshes, no duplicate detection | ⬜ |
| 10.3 | Process death (swipe away) → relaunch | Queue restored, cards restored, no duplicates | ⬜ |
| 10.4 | Huge blog (4k+ posts) | No ANR; queue capped at 3000 rows (#12) | 📱 |
| 10.5 | Removing a 30k-row card | No crash, no system_server death (#13) | 📱 |

## 11. Regression checklist (issues found in device testing)

| ID | Issue | Fix | Status |
|---|---|---|---|
| R1 | Single file downloaded without asking | Picker for single files | ✅ 📱 |
| R2 | Partially downloaded link never detected again | Clip-change signal | 📱 |
| R3 | Skip → re-copy did not re-open the picker | Clip-change signal | ⬜ |
| R4 | Re-downloading created `(1)` copies | Overwrite in place | 📱 |
| R5 | Blog link only filled the field | Type dialog / direct start | 📱 |
| R6 | Long-blog dialog appeared after downloads started | Gate before enqueue | ⬜ |
| R7 | GIFs saved as `.bin` | Shared inference + Content-Type rename | ✅ |
| R8 | Card progress frozen while parsing | Media progress wins | 📱 |
| R9 | Remove button only while paused | Always visible | 📱 |
| R10 | 46/86 after re-adding a blog | Blog-wide counts | ✅ 📱 |
| R11 | Switch on still showed the blog dialog | No dialog when on | 📱 |
| R12 | Huge blog ANR'd the app | Back-pressure | 📱 |
| R13 | Removing a big card killed the system | Chunked intents | 📱 |
| R14 | Blog files mixed with single-post files | Per-blog folder | 📱 |
| R15 | Downloads started before the long-blog confirm | Gate moved first | ✅ |
| R16 | Detail 全部暂停 left the card "parsing" | Card-level pause/resume | ⬜ |
| R17 | Card lacked total/scanned posts | New stats line | ✅ 📱 |
| R18 | "Already in list" but nothing visible | Orphans visible + named toast | ✅ 📱 |
| R19 | Copying the field's own URL re-triggered detection | Ignore focused copies | ⬜ |
| R20 | Stale clipboard restarted a removed blog | Deleting a row / clearing history no longer resets the clipboard dedupe key (only 设置 → 清空自动检测缓存 does) | ✅ |
| R21 | Clear-all did nothing while orphan rows were listed | Clear-all now also takes completed orphans; a live card's rows stay | ✅ 📱 |
| R22 | Re-copying the same link was never re-detected | Clip-description timestamp signal (`ClipboardDedupe`, three signals combined) | ✅ 📱 |
| R23 | Sharing a blog while a post link sat in the clipboard fired both flows | Clipboard ignored for 5s after a share | ✅ 📱 |
| R24 | Removing a blog card dumped its old rows into the main list | Orphans hidden again — only counted by the next card of that blog | ✅ |
