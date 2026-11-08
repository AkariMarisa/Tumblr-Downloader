# BlogFeedParser 解析规范(定稿)

> 状态:设计定稿,不改代码。全部结论基于 2026-09-27 实抓实测(bonesbunns / paokai / tempestrune / parkayquarts / fetishes-i-like3 / kthingz / michyrod / rubberporn2 / rubbergenjutsu / giflatex)。
> 本目录 `.agents/` 未纳入 git,文档仅本地保留。

## 一、模块划分

```
TumblrParser (既有)          — 新增 isBlogUrl / parseBlogIdentifier / isTaggedUrl
BlogFeedParser (新增)        — 纯解析+抓取,无 UI 依赖
 ├── loadBlogBootstrap(blog)      → BlogInfo + ANON_TOKEN + 第1页 FeedPage
 ├── fetchFeedPage(href, token)   → FeedPage(nextLink?/totalPosts/posts)
 └── extractMediaFromPost(post)   → List<MediaCandidate>   ← 核心提取规则
BlogDownloadManager (新增,协调器) — 状态机、游标、统计、入队(经 DownloadStateManager.Cmd.Append)
```

## 二、数据模型

```kotlin
data class BlogInfo(
    val blogName: String, val title: String?, val avatarUrl: String?, val blogUrl: String,
    val topTags: List<String>, val isAdult: Boolean,
    val isPasswordProtected: Boolean,        // true → BLOCKED,不抓
    val isHiddenFromBlogNetwork: Boolean
)
data class FeedPage(val posts: List<FeedPost>, val nextLink: String?, val totalPosts: Int?)
data class FeedPost(val postUrl: String, val id: Long, val isNsfw: Boolean, val media: List<MediaCandidate>)
data class MediaCandidate(
    val sourceUrl: String,          // postUrl → 写入下载条目的 sourceUrl
    val mediaKey: String?,          // 跨档位去重 identity
    val imageUrl: String?,          // media[j].url(静态图)
    val videoUrl: String?,          // gifv → media[j].video[](mp4) 或原生视频 url;有则优先
    val width: Int?, val height: Int?,
    val kind: IMAGE | GIF_VIDEO | NATIVE_VIDEO
)
```

## 三、提取规则(定稿)

```
extractMediaFromPost(post):
  blocks = post.content + sum(post.trail[].content)        # trail 重链内容必须收(实测存在)
  候选   = []
  for block in blocks (type ∈ {image, video}):
    # ── 1. media 形状归一化:数组 / 单 dict 两种(实测 465 list : 9 dict)
    mediaList = (block.media is List) ? block.media : [block.media]
    for m in mediaList (dict):
      if m.video 非空:                                       # gifv:mp4 渲染
        候选 += MediaCandidate(mediaKey=m.mediaKey, videoUrl=selectBest(m.video, tier), kind=GIF_VIDEO)
      elif m.url:                                            # 静态图(media.tumblr.com)
        候选 += MediaCandidate(mediaKey=m.mediaKey, imageUrl=m.url, w,h, kind=IMAGE)
    # ── 2. 原生视频 block(provider=tumblr):block 顶层 url ← va.media.tumblr.com/...mp4
    if block.type == video && block.url:
      候选 += MediaCandidate(mediaKey=block.media.mediaKey?, videoUrl=block.url, kind=NATIVE_VIDEO)
  过滤: url 的 host 必须以 media.tumblr.com 结尾        # 外部 embed(youtube/spotify/audio)自然剔除
  去重: 同一 post 内按 mediaKey 去重(无 key 用 DownloadUtils.normalizeMediaIdentity)
  输出按媒体类型选择(options):见下
```

**媒体类型选择(与面板选项对应)**:

| 选项 | gifv(media[j].video 存在) | 原生视频 | 静态图 |
|---|---|---|---|
| 仅图片 | 跳过(动图不算静态图) | — | ✓ |
| 仅视频 | **mp4**(选档后) | mp4 | — |
| 全部(默认) | **mp4**(✅ 已拍板:有 mp4 就下 mp4) | mp4 | ✓ |

- gifv mp4 档位从 `m.video[]`(实测 s100x200 … s540x810)用现有 `MediaQualitySelector` 按档位选;
- 静态图档位从 `media[]` 各 dict 的 width/height 选(现有逻辑)。

## 四、分页与终止(定稿)

```
① bootstrap: GET https://www.tumblr.com/<blog>
   解析 id="___INITIAL_STATE___"(3 下划线,现有正则 __+…__+ 命中 ✓)
   → apiFetchStore.API_TOKEN / PeeprRoute.initialTimeline(第1页 20帖+nextLink) / queries[1].state.data(博客信息)
② 翻页:  GET https://www.tumblr.com/api + links.next.href         # href 原样使用,不拆改
   头:    Authorization: Bearer <token>  /  Accept: application/json;format=camelcase
   同 token 跨博客通用(实测),但每次 job 启动重新 bootstrap 拿新 token 更稳
③ 终止:  response.links 为空(或缺少 links.next) → Done
   不依赖每页帖数(实测 17~20 不定);totalPosts 仅作「约 N 帖」展示,非精确
```

已知反例:kthingz 首页仅 5 帖;paokai totalPosts 148 但实际 131 帖——均不影响游标逻辑。

## 五、状态机(定稿)

```
IDLE
 └─ enqueueBlog(blogUrl, options)                # 校验为博客 URL
BOOTSTRAPPING → emit BlogInfoReady(info)
 │   ├─ info.isPasswordProtected → BLOCKED("该博客需要密码")→ 结束(不计入历史)
 │   └─ nextLink=第1页
 │       超长判断:totalPosts ≥ THRESHOLD(默认 10,000,可调)→ emit ConfirmDialog(约N帖/约M次请求)
 │           待用户确认 / options.confirmLong=true 才继续(确定性:UI 层决定,协调器只是事件方)
FETCHING → fetchFeedPage(nextLink)               # 节流:两次请求最小间隔(设置项,默认 500ms)
PARSING → 逐帖过滤历史 + extractMediaFromPost → Cmd.Append
   │   per post: existsBySourceUrl(postUrl) ? skipped++ : (media.empty? noMedia++ : enqueue++, postsDiscovered++)
   │   每帖之间检查 cancelled
NEXT_PAGE → nextLink 非空则继续 FETCHING
DONE / CANCELLED
异常:API 401 → 重新 bootstrap 一次后重试当前页(幂等,靠去重兜底)
     API 429 / 5xx → 退避重试,连续 N 次(默认3)失败 → BLOCKED(明确错误分类)
```

**进度事件**(新 `ParseEvent` 子类或独立 Flow):`BlogInfoReady / BlogProgress(page, discovered, skipped, noMedia, enqueued) / BlogDone / BlogError(code) / ConfirmLong(约定)`,UI 据此驱动卡片与确认框。

## 六、持久化与续扫(定稿)

新表 `blog_download_jobs`(v1→v2 真实 Migration):

| 列 | 说明 |
|---|---|
| jobId(PK) | = batchId,写入每个下载条目的 `batchId` |
| blogName | 卡片标题与过滤 |
| state | running / done / cancelled / blocked |
| **resumeLink** | **尚未消费的 nextLink**(重建 job 时直接接着抓,这就是"50页断了从51页续") |
| pagesCompleted / postsDiscovered / postsSkipped / postsNoMedia / mediaEnqueued | 统计(重启后可重建卡片) |
| createdAt / updatedAt | |

- `DownloadHistoryEntity` 增 `batchId`、`blogName`(一次迁移,为 Tag 管理/自动扫描铺路);
- 续扫时重新 bootstrap(拿新 token + 博客信息),**不能复用旧 token/旧 href**(token 可能轮换),resumeLink 仅指示"从哪一页继续",去重防重复入队;
- 重启后:未完成 job → 卡片「未完成」态 → 点击「继续」→ `resume(jobId)`。

## 七、错误分类(定稿)

| 状态 | 含义 | 处理 |
|---|---|---|
| 404 | 博客不存在 | BlogError(not_found),Toast |
| password_protected | 需要密码 | BlogError(blocked_password),不计入失败重试 |
| network / timeout | 网络 | 退避重试 ×3 |
| 429 / 5xx | 限流/服务端 | 退避重试 ×3,连续失败停 |
| 401(期中) | token 失效 | 重新 bootstrap 1 次,重试当前页 |
| per-post | 无媒体(外部 embed/text 帖) | 计数 noMedia,继续 |

## 八、节流与确认框

- API 请求最小间隔:设置项「博客扫描请求间隔」(默认 500ms)——与下载限速设置区分;
- 超长确认阈值:默认 **10,000 帖**,展示「该博客约有 N 帖,预计需 M 次请求,是否继续?」;设置项可改/关闭;
- 大请求量时的轻量化:单页 17~20 帖 → 10,000 帖 ≈ 500 次请求,默认间隔下约 4 分钟扫描期,可接受。

## 九、测试计划(fixtures 已备)

从已抓数据选 6 份脱敏 JSON:
- `paokai`(小博客+完整终止)· `tempestrune`(超长)· `parkayquarts`(极端)· `giflatex`(gifv→mp4)· `michyrod`(原生视频+单 dict media)· `fetishes`(超大+NSFW 匿名)

用例:
1. bootstrap:`___INITIAL_STATE___` 解析、token、博客信息、isPasswordProtected 分支;
2. 提取规则:gifv→mp4、原生视频 url、单 dict/数组 media、trail 媒体、host 过滤、mediaKey 去重、类型选择矩阵;
3. 状态机:正常跑完 / 终止(`links` 空)/ 取消 / 401 重 bootstrap 重试 / 429 退避;
4. 续扫:resumeLink 从光标页继续且不重复入队;
5. 超长阈值:确认框触发与关闭。

## 十、既有代码接入点(实现时清单)

| 位置 | 改动 |
|---|---|
| `TumblrParser` | +`isBlogUrl`/`parseBlogIdentifier`/`isTaggedUrl`(博客 URL 不命中现有 `shareUrlRegex`,天然隔离) |
| `MainViewModel` | +`enqueueBlog(url, options)`;`isUrlFullyCompleted` 增加博客 URL 分支 |
| `DownloadStateManager` | 复用 `Cmd.Append`;`isParsing` 单锁不动,批量用自己的状态占位 |
| `DownloadHistoryDao` | +`existsBySourceUrl()`,+`loadByBatchId()`(明细页) |
| `AppDatabase` | v1→v2 真实 Migration;新表 `blog_download_jobs`;实体加 `batchId`/`blogName` |
| `DownloadFragment` | 输入分流:博客 URL → 选项面板 → `enqueueBlog` |
| `DownloadsFragment` | 顶部博客任务卡片区 + 点击进 `BlogDetailActivity`(byBatchId 明细) |
| `SettingsActivity` | +扫描请求间隔 / 超长阈值 / 页数上限(保险,默认不限) |

## 附:验证数据快照(2026-09-27 实测)

| 博客 | totalPosts | 首页帖子 | 关键验证点 |
|---|---|---|---|
| paokai | 148(实际 131) | 17~20 | 终止信号:links 空 |
| tempestrune | 91,741 | 17 | 超长确认路径 |
| parkayquarts | 151,927 | 17 | 极端超长 |
| giflatex | 372 | 17 | gifv→mp4(24 处 mp4)|
| michyrod | 4,822 | 17 | 原生视频 va.media mp4 / 单 dict media |
| fetishes-i-like3 | 36,688 | 17 | 超大 + NSFW(Adult=True)匿名 API 200 |

关键事实:
- 老格式帖(photo/video/audio 时代)经 API 全部转为 NPF `blocks`,提取路径与新版一致;
- 原生视频 block:顶层 `url` 或 `media.url` = `va.media.tumblr.com/tumblr_<key>.mp4`;
- `media` 字段形状:465 处数组 / 9 处单个 dict(video block),必须归一化;
- NSFW(Adult=True)博客匿名 bearer token 可完整取数,批量下载无须登录;
- `*.tumblr.com` 子域对直连 HTTP 返回 JS 挑战页(403,MD5 2eb1d93c…),**批量方案全程只走 `www.tumblr.com` 不受影响**。

## 附:待办/后续

1. 单帖修复方向(独立 bug,不阻塞批量):输入帖子 URL 统一归一为 `https://www.tumblr.com/<blog>/<id>`(oEmbed 响应 `url` 即 canonical);页面兜底改用 Bearer-token API `GET /v2/blog/<blog>/posts/<id>`(需验证端点)。
2. 实现开始时:更新 README_zh 待办「批量下载」、回复 issue #22。