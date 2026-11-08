---
name: "release-apk"
description: "通过打 v* tag 触发 CI 自动发布新版 APK"
version: 2
created: "2026-07-07"
updated: "2026-07-10"
---
## When to Use
当需要发布一个新版 APK（准备上架 F-Droid 或分发新版本）时使用。日常开发、修复 bug、提交代码**不**需要走此流程。

## Procedure
1. **更新 master 分支** —— `git checkout master && git pull origin master`
2. **检查 F-Droid 兼容性** —— 运行 `fdroid-release-check` 确认构建配置、AntiFeatures、依赖、权限是否有变更。如有需要，先更新 fdroid-data metadata。
3. **确定版本号** —— 决定本次发版的版本号，格式为 `x.y.z`
4. **升级版本号** —— 修改 `app/build.gradle.kts`：
   - `versionCode` 加 1
   - `versionName` 更新为新版本号
5. **提交版本变更** —— `git add app/build.gradle.kts && git commit -m "chore: bump version to x.y.z"`
6. **推送 master** —— `git push origin master`
7. **打 tag** —— `git tag vx.y.z`
8. **推送 tag** —— `git push origin vx.y.z`
9. **等待 CI 完成并创建 Release** —— 在 [Actions](https://github.com/AkariMarisa/Tumblr-Downloader/actions) 页面查看构建状态，CI 会自动生成双语 Release Notes 和 APK 附件。
10. **（可选）更新 F-Droid** —— 如 `fdroid-release-check` 提示有变更，更新 `fdroid-metadata.yml` 并在 GitLab fork 中提交 MR。
## Pitfalls
- **千万别从 tag 名称注入版本号** —— F-Droid 要求 checkut 源码时 `versionName` 已经硬编码在 `app/build.gradle.kts` 里。CI 里改版本号会导致 F-Droid 构建失败。
- **versionCode 必须递增** —— F-Droid 用 `versionCode` 判断版本新旧。每次发版记得 +1，不能回退。
- **不要在 master 直接 push tag** —— master 有 branch protection，但 `git push origin vx.y.z` 只推 tag，不受分支保护限制，可以正常推送。
- **tag 一旦推送不要删除** —— 如果发版后发现有问题，不要删 tag 重发，应该发一个新版本（versionCode +1，versionName 升级）。F-Droid 不会重构建已存在的 tag。
- **确保 tag 格式为 `v*`** —— CI 只响应 `v` 开头的 tag（如 `v1.2.0`），其他 tag 不会触发 Release。

## Verification
1. GitHub Actions 中 `Build APK` workflow 的 `release` job 成功完成。
2. GitHub Releases 页面出现新 Release，APK 可下载。
3. Release 的 Changelog 正确显示了从上一版本至今的提交记录。