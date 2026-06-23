# Tumblr Downloader

## 概述

这个 Android 应用程序允许用户从 Tumblr 帖子中下载图片和视频。它支持 Android 11 及更高版本的设备，并使用 Kotlin 构建。

## 功能

- 从 Tumblr 帖子中下载图片和视频
- 将媒体保存到本地目录
- 在应用内查看下载的媒体
- 自动检测并从剪贴板下载 Tumblr 链接

## 项目结构

```plaintext
TumblrDownloader/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/
│   │   │   │       └── example/
│   │   │   │           └── tumblrdownloader/
│   │   │   │               ├── MainActivity.kt
│   │   │   │               ├── DownloadService.kt
│   │   │   │               ├── MediaViewerActivity.kt
│   │   │   │               └── utils/
│   │   │   │                   ├── TumblrParser.kt
│   │   │   │                   └── DownloadUtils.kt
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   └── activity_media_viewer.xml
│   │   │   │   ├── values/
│   │   │   │   │   └── strings.xml
│   │   │   │   └── drawable/
│   │   │   │       └── ic_launcher_background.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── java/
│   │           └── com/
│   │               └── example/
│   │                   └── tumblrdownloader/
│   │                       └── ExampleUnitTest.kt
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## 技术

- **Kotlin**: 主要编程语言
- **Android SDK**: 用于构建 Android 应用程序
- **Retrofit**: 用于网络请求
- **Glide**: 用于图片加载和缓存
- **ExoPlayer**: 用于视频播放
- **Room**: 用于本地数据库存储

## 界面原型

### 主活动

![主活动](docs/prototypes/main_activity.html)

主活动包含两个标签页：

**下载标签页** (默认显示):
- **输入字段**: 用于手动输入 Tumblr 分享链接
- **下载按钮**: 用于手动开始下载

**下载列表标签页**:
- **媒体列表**: 显示当前正在下载和已完成下载的文件
- 支持点击查看图片或视频

### 媒体查看器活动

![媒体查看器活动](docs/prototypes/media_viewer_activity.html)

- **媒体显示**: 显示选定的图片或视频
- **返回按钮**: 用于返回主活动

## 设置说明

1. 克隆仓库
2. 在 Android Studio 中打开项目
3. 在 Android 设备或模拟器上构建并运行应用程序

## 贡献

欢迎贡献！请打开一个问题或提交一个拉取请求。

## 许可证

这个项目在 MIT 许可证下授权。