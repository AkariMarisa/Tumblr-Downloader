---
name: "fdroid-release-check"
description: "检查新版本是否需要手动更新 fdroid-data metadata"
version: 1
created: "2026-07-10"
updated: "2026-09-26"
---
## When to Use
发布新版本前，检查构建配置、AntiFeatures、依赖是否有变更，决定是否需要手动更新 fdroid-data

## Procedure
1. 运行以下命令对比当前版本与上一个 tag 的变更：
  - `git diff <上一个tag>..HEAD -- app/build.gradle.kts` 检查构建配置变更
  - `git diff <上一个tag>..HEAD -- app/src/main/AndroidManifest.xml` 检查权限变更
  - `git diff <上一个tag>..HEAD -- app/build.gradle.kts | grep -E 'implementation|api '` 检查依赖变更
  - `git diff <上一个tag>..HEAD -- gradle/libs.versions.toml` 检查版本目录变更
2. 判断是否需要更新 fdroid-data：
  - **修改构建配置**（如 gradle 参数、product flavors、minSdk、targetSdk）→ 需要更新
  - **修改 AntiFeatures**（如新增权限导致需要标记 NonFreeNet、Tracking 等）→ 需要更新
  - **添加新依赖**（检查是否引入非 FOSS 许可证的库）→ 需要更新
  - **只修改 versionCode/versionName** → 不需要更新，F-Droid 自动检测
3. 如需手动更新，基于 fdroid-data 仓库现有的 metadata 文件修改：
  - 新版本需要手动添加 `Builds` 时，保留已有的历史构建条目，在末尾追加新条目；按 `versionCode` 从旧到新排序，不要用新版本覆盖旧版本
  - 新旧 `Builds` 条目之间留一个空行，保持 `fdroid rewritemeta` 的标准格式
  - 构建配置：在相应 `Builds` 条目中更新 `gradle`、`prebuild`、`sudo` 等字段；同时更新 `CurrentVersion` 和 `CurrentVersionCode` 为最新版本
  - AntiFeatures：更新顶层 `AntiFeatures` 列表
  - 新依赖：检查许可证，可能需要添加 `AllowedAPKSigningKeys` 或修改 `AntiFeatures`
4. 在 fdroid-data 仓库运行 `fdroid lint <package-name>`，并运行 `fdroid rewritemeta <package-name>` 检查格式；确认后者不会产生 diff（若产生 diff，核对并按其要求修正，再重新检查）：
  - 确认 `AntiFeatures` 在最前面
  - 确认 `versionName` 和 `CurrentVersion` 不带引号
5. 提交到 fdroid-data 仓库并创建 MR

## Pitfalls
- versionName 和 CurrentVersion 不要加引号，YAML 会解析为字符串
- AntiFeatures 必须放在 metadata 文件的最前面（Categories 之前）
- gradle 值为 'yes' 表示无 product flavor，不要写成 flavor 名称
- 自动更新或手动添加新版本时，都不要删除已有的历史 `Builds`；手动添加时按旧到新排序并在条目间留空行
- F-Droid 构建服务器使用自己的签名密钥，不需要上传我们的 keystore
- 添加新依赖前检查许可证，非 FOSS 依赖会导致 F-Droid 拒绝

## Verification
1. 检查 git diff 确认变更内容
2. 确认历史 `Builds` 均保留、新构建位于末尾且 `CurrentVersion`/`CurrentVersionCode` 指向新版本；`fdroid rewritemeta` 无格式 diff
3. 确认所有 pipeline 通过（test + build）
4. 确认 metadata 中 AntiFeatures 在最前面、versionName 无引号
