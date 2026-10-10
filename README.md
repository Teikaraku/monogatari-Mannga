# 物语

<img src="docs/assets/monogatari-icon.png" width="96" alt="物语的白底粉红漫字图标">

物语（Monogatari）是一款阅读漫画和电子书的应用。你可以在书库中找漫画，把喜欢的作品加入书架，接着上次的进度继续阅读，也可以导入自己的漫画文件和电子书。

这个仓库包含安卓版和 iPhone / iPad 版的完整源码。安卓版使用 Kotlin 和 Jetpack Compose，iPhone 版使用 Swift、SwiftUI 和 UIKit。

## 下载

安装包在 [Releases](https://github.com/Teikaraku/monogatari-reader/releases) 中：安卓下载 APK 直接安装；iPhone / iPad 下载 IPA，用 AltStore、SideStore 等工具签名后安装。

## 功能

| 功能 | 说明 |
| --- | --- |
| 书库 | 查看漫画推荐、分类、排行榜、完结作品和专题，也可以搜索作品。 |
| 书架 | 收藏作品、建立分类、查看更新，记录每本作品的阅读进度。 |
| 阅读 | 支持横向翻页、竖向阅读和连续滚动；可以调整阅读方向、图片裁边、显示方式和文字排版。 |
| 离线下载 | 下载章节后阅读，查看下载队列，暂停或取消任务。 |
| 本地文件 | 导入自己的漫画压缩包和 EPUB 电子书。 |
| 历史和统计 | 查看读过的作品、最近阅读的位置和阅读统计。 |
| 备份 | 保存或恢复书架、阅读记录和设置。 |
| 其他功能 | 词典、文字识别、收藏词汇、图片清晰度增强和阅读服务同步。 |

## 从哪里开始修改

| 内容 | 安卓 | iPhone |
| --- | --- | --- |
| 首页、底部导航 | `android/.../ui/MonogatariApp.kt` | `Monogatari/App/Root/TabBarController.swift` |
| 书库首页 | `android/.../ui/BookstoreScreens.kt` | `Monogatari/Features/Bookstore/BookstoreView.swift` |
| 分类、排行、完结、专题和搜索页 | `android/.../ui/BookstoreScreens.kt` | `Monogatari/Features/Bookstore/CopyCatalogView.swift` |
| 漫画详情和章节列表 | `android/.../ui/MangaScreens.kt` | `Monogatari/Features/Manga/` |
| 阅读页面 | `android/.../ui/ReaderScreen.kt` | `Monogatari/Features/Reader/` |
| 设置 | `android/.../ui/SettingsScreens.kt`、`OriginalSettingsScreen.kt` | `Monogatari/Features/Settings/` |
| 菜单和底部面板 | `android/.../ui/NativeMenu.kt`、`OptionSheet.kt` | 系统菜单和面板 |
| 书库数据 | `android/.../sources/CopySourceClient.kt` | `Monogatari/Core/Sources/BuiltIn/Copy/` |

`android/...` 指 `android/app/src/main/java/app/monogatari/android`。更多说明见 [页面修改指南](docs/EDITING.md)。

## 编译

1. 克隆仓库：`git clone https://github.com/Teikaraku/monogatari-reader.git`。
2. 安装 Android Studio、JDK 17 或 21 和 Python 3，并在 Android Studio 中安装 Android 36 开发工具。
3. 在仓库根目录运行 `python android/scripts/prepare-assets.py`，准备阅读器需要的模型文件。
4. 用 Android Studio 打开 `android` 文件夹，连接手机或启动模拟器后运行。

iPhone 版可以在仓库的 **Actions** 中运行 **构建 iPhone 安装包** 得到未签名的 IPA；安卓安装包对应 **生成安卓安装包**。正式签名和命令行构建见 [构建说明](docs/BUILDING.md)。

## 文件夹

| 文件夹 | 内容 |
| --- | --- |
| `android/app` | 安卓应用和测试。 |
| `android/scripts` | 构建、预览、准备模型和导入资源的工具。 |
| `android/model-conversion` | 图片增强模型的转换工具。 |
| `Monogatari` | iPhone / iPad 应用。 |
| `MonogatariTests` | iPhone 版测试。 |
| `Monogatari.xcodeproj` | Xcode 项目。 |
| `docs` | 构建和修改说明。 |

## 反馈与许可

问题反馈请到 [Issues](https://github.com/Teikaraku/monogatari-reader/issues)，写明页面、操作步骤和设备。

本项目基于 [Aidoku](https://github.com/Aidoku/Aidoku)，按 [GNU GPL v3](LICENSE) 发布，并保留原项目的许可和作者信息。第三方图片、词典、识别模型和依赖的许可随相应文件保留。漫画内容由提供方返回，不包含在本仓库中。
