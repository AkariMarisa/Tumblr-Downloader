# Interface Revamp · Aurora Glass 设计稿

> **状态 / Status**: 方向已定稿（暗 + 亮双主题），实现未开始。
> Direction finalized (dark + light dual theme); implementation not started yet.

一次性看全貌：**[overview.png](overview.png)**（11 张定稿稿 + 2 张淘汰探索稿的拼图）。
For a single-image overview of every artboard, see **[overview.png](overview.png)**.

## 定稿方向 / Chosen direction

**Aurora Glass** —— 玻璃分层设计语言，日夜双主题：

- **暗色**：OLED 近黑 `#0B0E14` + 紫/粉柔光斑，浮卡为白色低透明度 + 亮边
- **亮色**：白底 + 同款柔光斑，浮卡为白磨砂（72~98% 不透明 + 发丝边 + 软阴影）
- 导航：主页用**悬浮胶囊底部导航**（3 项），推入页用返回箭头 + 大标题
- 沉浸屏（媒体查看器）固定纯暗色，不随主题

淘汰 / Rejected: Material 3 Expressive（备选方向）、Bento Calm（冷静结构风）。
两张淘汰稿保留在 `svg/`、`png/` 中留档（`design-a-*`、`design-c-*`）。

## 设计 Token / Design tokens

| Token | 值 |
|---|---|
| 渐变主色 Gradient | `#FF5C8A → #8B5CF6`（按钮 / 进度 / 激活态 / 选中勾） |
| 墨色 Ink | `#1D1B20` (light) / `#EDEEF2` (dark) |
| 次级文字 Secondary | `#9A939E` (light) / `#6E7486` (dark) |
| 玻璃浮卡 Glass panel | light: 白 72~98% + `#E7E0EF` 发丝边 + 软阴影；dark: 白 7~14% + `rgba(255,255,255,.14)` 亮边 |
| 柔光斑 Aurora blobs | 紫 `#8B5CF6`（右上）/ 粉 `#FF5C8A`（左下），4-stop 径向渐变平滑衰减 |
| 强调/链接 Accent | `#B02E5C` (light) / `#FF8FAC` (dark) |
| 成功 / 状态 | 绿 `DONE`；进度态用渐变 + 数字 |
| 圆角 Shape | 手机屏 33 / 浮卡 16~28 / 按钮胶囊 999 |
| 字体 Type | 标题 800 字重；数据（大小、百分比、版本）等宽字体 |

坐标即 dp：所有 SVG 按 312×692 屏幕坐标绘制，可直接对照翻译为 XML 布局。

## 屏幕清单 / Screen inventory（13 张 × 双屏）

| 文件 | 覆盖的 app 界面 | 主题 |
|---|---|---|
| `design-b-glass-dark` | 下载主页 + 下载列表 | DARK |
| `design-b2-aurora-light` | 下载主页 + 下载列表 | LIGHT |
| `design-s1-settings` | 设置主页（4 类目 + Theme 切换器） | DARK + LIGHT |
| `design-s5-settings-detail` | 设置子页（开关/滑杆/值行/破坏性操作） | DARK + LIGHT |
| `design-s2-media-picker` | 下载前媒体选择器（底部浮层） | DARK + LIGHT |
| `design-s3-blog-detail` | 博客详情（扫描进度 + 文件列表） | DARK + LIGHT |
| `design-s9-filter-dialog` | 扫描类型对话框（全部/图片/视频） | DARK + LIGHT |
| `design-s4-onboarding` | 首次引导（5 页，示例第 1 页） | DARK + LIGHT |
| `design-s6-about` | 关于页（版本/链接/Ko-fi） | DARK + LIGHT |
| `design-s7-media-viewer` | 媒体查看器（图片态 + 视频态） | 沉浸暗色 |
| `design-s8-login` | Tumblr 登录（应用 chrome + WebView） | DARK + LIGHT |
| `design-a-m3-expressive` | 备选：Material 3 Expressive | LIGHT（留档） |
| `design-c-bento-calm` | 淘汰：Bento Calm | LIGHT（留档） |

文案均对照 `app/src/main/res/values/strings.xml` 与实际布局，不是虚构内容。

## 再生 / Regenerating

```bash
python3 gen_design_svg.py            # 重新生成 svg/*.svg（改坐标后重跑）
for f in svg/*.svg; do
  rsvg-convert -z 2 -o "png/$(basename "${f%.svg}").png" "$f"
done
montage png/design-{b-glass-dark,b2-aurora-light,s1-settings,s2-media-picker,\
s3-blog-detail,s4-onboarding,s5-settings-detail,s6-about,s7-media-viewer,\
s8-login,s9-filter-dialog}.png -tile 3x4 -geometry 480x+6+6 overview.png
```

依赖：`librsvg`（`rsvg-convert`）、ImageMagick（拼图，可选）、Noto Sans 字体。
Dependencies: `rsvg-convert` (librsvg), ImageMagick (overview, optional), Noto Sans fonts.

---

**实现路线 / Implementation roadmap**（待启动 / to start）:
色板与主题（`values-night` 双套）→ 玻璃卡片样式 → 底部导航替换抽屉+顶部 Tab → 逐屏落地。
每步跑 `docs/test-plan.md` 回归，保持 0 失败。
