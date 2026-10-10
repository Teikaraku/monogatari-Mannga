# 物语

<img src="docs/assets/monogatari-icon.png" width="96" alt="物语的白底粉红漫字图标">

物语（Monogatari）是一款用来阅读漫画和电子书的应用。你可以在书库中找漫画，把喜欢的作品加入书架，接着上次的进度继续阅读，也可以导入自己的漫画文件和电子书。

这个仓库保存安卓版和 iPhone / iPad 版的完整源码。两端的书库页面、书架和设置按同一套设计编写；安卓版用 Kotlin 和 Jetpack Compose，iPhone 版用 Swift、SwiftUI 和 UIKit，修改一端不会自动改变另一端。

## 目前可以做什么

| 功能 | 使用方式 |
| --- | --- |
| 书库 | 查看漫画推荐、分类、排行榜、完结作品和专题，也可以搜索作品。内容来自拷贝漫画接口，页面由物语自己绘制。 |
| 书架 | 收藏作品、建立分类、查看更新，记录每本作品的阅读进度。 |
| 阅读 | 支持横向翻页、竖向阅读和连续滚动；可以调整阅读方向、图片裁边、显示方式和文字排版。 |
| 离线下载 | 下载章节后阅读，查看下载队列，暂停或取消任务。 |
| 本地文件 | 导入自己的漫画压缩包和 EPUB 电子书，不必把文件上传到服务器。 |
| 历史和统计 | 查看读过哪些作品、最近阅读的位置和阅读统计。 |
| 备份 | 保存或恢复书架、阅读记录和设置。 |
| 其他功能 | 词典、文字识别、收藏词汇、图片清晰度增强，以及原有阅读服务的连接功能。 |

在线内容取决于提供方当前返回的数据。不能读取的付费或锁定章节需要提供方相应的权限。

## 想修改页面，从哪里开始

| 你想修改的内容 | 安卓 | iPhone |
| --- | --- | --- |
| 首页、底部导航 | `android/app/src/main/java/app/monogatari/android/ui/MonogatariApp.kt` | `Monogatari/App/Root/TabBarController.swift` |
| 书库、推荐和排行榜 | `android/.../ui/BookstoreScreens.kt` | `Monogatari/Features/Bookstore/BookstoreView.swift` |
| 分类、完结、专题和搜索页 | `android/.../ui/BookstoreScreens.kt` | `Monogatari/Features/Bookstore/CopyCatalogView.swift` |
| 筛选、分类等底部面板 | `android/.../ui/OptionSheet.kt` | 各页面使用系统面板 |
| 漫画详情和章节列表 | `android/.../ui/MangaScreens.kt` | `Monogatari/Features/Manga/` |
| 阅读页面 | `android/.../ui/ReaderScreen.kt` | `Monogatari/Features/Reader/` |
| 设置和关于页面 | `android/.../ui/SettingsScreens.kt`、`OriginalSettingsScreen.kt` | `Monogatari/Features/Settings/` |
| 拷贝漫画的数据地址和读取方式 | `android/.../sources/CopySourceClient.kt` | `Monogatari/Core/Sources/BuiltIn/Copy/` |
| 应用名称和项目链接 | `android/.../AppBrand.kt` | `Monogatari/Monogatari.xcconfig`、`Monogatari/App/Resources/Localization/` |

表中的 `android/...` 指 `android/app/src/main/java/app/monogatari/android`。具体的修改方法见 [页面修改指南](docs/EDITING.md)。

## 自己编译和预览

1. 下载本仓库，或者运行 `git clone https://github.com/Teikaraku/monogatari-reader.git`。
2. 安装 Android Studio、JDK 17 或 21，以及 Python 3；在 Android Studio 中安装 Android 36 的开发工具。
3. 在仓库根目录运行 `python android/scripts/prepare-assets.py`。它会下载阅读器需要的模型文件，并检查文件是否完整。
4. 在 Android Studio 中打开 `android` 文件夹，等待项目准备完成。
5. 连接手机或启动安卓模拟器，点击运行按钮即可看到真正的应用页面。

iPhone 版不需要自己有 Mac：在本仓库的 **Actions** 页面选择 **构建 iPhone 安装包**，运行后下载未签名的 IPA。安卓安装包同样可以用 **生成安卓安装包** 构建。正式签名、命令行构建和 Windows 预览方法见 [构建与预览说明](docs/BUILDING.md)。

## 文件夹说明

| 文件夹或文件 | 内容 |
| --- | --- |
| `android/app` | 安卓应用的页面、阅读功能、数据保存方式，以及测试。 |
| `android/scripts` | 构建、预览、准备模型和导入资源的工具。 |
| `android/model-conversion` | 把图片增强模型转换成安卓可使用的文件的工具。 |
| `Monogatari` | iPhone / iPad 版的页面与功能。 |
| `MonogatariTests` | iPhone 版的测试。 |
| `Monogatari.xcodeproj` | 使用 Xcode 打开的 iPhone 项目。 |
| `docs` | 安装、构建、修改和本项目变化的说明。 |
| `LICENSE` | 本项目采用的开源许可。 |

更完整的两端功能对应关系见 [移植说明](android/MIGRATION.md) 和 [对应文件清单](android/MIGRATION_MATRIX.json)。本次安装包的检查结果见 [检查记录](docs/VERIFICATION.md)。

## 问题反馈与来源说明

使用中遇到问题，可以在 [问题反馈](https://github.com/Teikaraku/monogatari-reader/issues) 中描述发生问题的页面、操作步骤和设备情况。

本项目基于 [Aidoku](https://github.com/Aidoku/Aidoku) 开发，按 GPL 要求保留原项目的开源许可和作者信息。为了继续读取图源和备份文件，图源格式、备份格式和部分登录回调仍使用原有的协议名称。物语的改动说明见 [项目变化](docs/CHANGES.md)。第三方图片、词典、识别模型和依赖的许可随相应文件保留。

源码按 [GNU GPL v3](LICENSE) 发布。漫画内容由相应提供方返回，漫画本身不包含在本源码仓库中。
